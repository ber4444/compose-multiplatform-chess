package com.example.evals

import com.example.coachapi.OpeningExplainRequest
import com.example.coachapi.OpeningExplainResponse
import com.example.coachapi.Passage
import com.example.coachserver.Embedder
import com.example.coachserver.LlmComposer
import com.example.coachserver.OpeningQueryBuilder
import com.example.coachserver.PassageRepository
import com.example.coachserver.RequestRateLimiter
import com.example.coachserver.ServerDependencies
import com.example.coachserver.TemplateComposer
import com.example.coachserver.TextComposer
import com.example.coachserver.openingCoachModule
import com.example.coachserver.selectComposer
import com.example.ondeviceai.AiTokenOrFinal
import com.example.ondeviceai.FakeTextGenerator
import com.example.ondeviceai.MoveCoachFallback
import com.example.ondeviceai.MoveCoachPromptBuilder
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.roundToInt

fun main() {
    val cases = GoldenCaseLoader.load(Path.of("golden/candidates.json"))
    val openingCases = cases.filter { it.eco != null }

    // Deterministic + local-HTTP routes run inside testApplication (they need the in-process
    // test server). testApplication wraps its body in runTestWithRealTime, which has a HARD 60s
    // ceiling that can't be extended — neither passing a CoroutineContext nor an inner withTimeout
    // overrides it (Ktor's testApplication calls runTestWithRealTime$default with the timeout arg
    // defaulted). These routes are all fast/deterministic, so the ceiling is fine for them.
    val deterministicStats = runTestApplicationRoutes(cases, openingCases)

    // The optional LLM-composed route makes real blocking HTTP calls to an external provider
    // (~1-5s each across ~10 cases, easily > 60s total). It calls composer.compose() directly —
    // NOT the test server — so it doesn't need testApplication at all. Run it in a plain
    // runBlocking with no ceiling, then merge its stats into the scorecard.
    val llmStats = runBlocking { evaluateLlmComposed(openingCases) }

    val stats = deterministicStats + llmStats
    val scorecard = ScorecardWriter.render(cases.size, openingCases.size, stats)
    Files.writeString(Path.of("scorecard.md"), scorecard)

    val regressions = stats.filter { it.available && it.collection == CollectionMode.AUTOMATED }
        .filter { it.groundingViolations > 0 }
    check(regressions.isEmpty()) {
        "Grounding violations detected: ${regressions.joinToString { "${it.route}=${it.groundingViolations}" }}"
    }
}

/**
 * Runs the deterministic + local-HTTP eval routes inside [testApplication]. These routes are all
 * fast (in-process fakes + a local test HTTP server) so they comfortably finish within
 * testApplication's hardcoded 60s ceiling. Returns their [RouteStats] for merging with the
 * optional LLM route (which runs outside testApplication — see [main]).
 */
private fun runTestApplicationRoutes(
    cases: List<GoldenCase>,
    openingCases: List<GoldenCase>,
): List<RouteStats> {
    // testApplication returns Unit, so thread the collected stats out via a captured holder.
    val collected = mutableListOf<RouteStats>()
    testApplication {
        val dependencies = caseSpecificOpeningDependencies(openingCases)
        application { openingCoachModule(dependencies, rateLimiter = RequestRateLimiter { true }) }
        val localClient = createClient { install(ContentNegotiation) { json() } }

        collected += evaluateFake(cases)
        collected += evaluateFallback(cases)
        collected += evaluateOpeningRoute("local-template", openingCases) { request ->
            localClient.post("/v1/openings/explain") {
                contentType(ContentType.Application.Json)
                setBody(request)
            }.body()
        }
        collected += evaluateDeployed(openingCases)
    }
    return collected
}

/**
 * Deterministic retrieval fake keyed by the production opening query. Each case gets only its own
 * passage, so a query-construction regression or mismatched retrieval produces a grounding failure
 * instead of being hidden by one passage containing every concept in the dataset.
 */
internal fun caseSpecificOpeningDependencies(
    cases: List<GoldenCase>,
    composer: TextComposer = TemplateComposer(),
): ServerDependencies {
    val requests = cases.map(GoldenCase::toOpeningRequest)
    val indexByQuery = requests.mapIndexed { index, request ->
        OpeningQueryBuilder.build(request) to index
    }.toMap()
    val passages = cases.map { case ->
        Passage(
            sourceId = "eval-${case.id}",
            title = "${case.eco} opening concepts",
            text = case.expectedConcepts.joinToString(", ").ifBlank { "development and center control" } + ".",
        )
    }
    return ServerDependencies(
        embedder = Embedder { query ->
            FloatArray(384).also { embedding ->
                embedding[0] = (indexByQuery[query]?.plus(1) ?: 0).toFloat()
            }
        },
        passageRepository = object : PassageRepository {
            override fun retrieve(embedding: FloatArray, limit: Int): List<Passage> {
                val index = embedding[0].toInt() - 1
                return passages.getOrNull(index)?.let(::listOf).orEmpty().take(limit)
            }

            override fun upsert(passage: Passage, embedding: FloatArray) = Unit
        },
        composer = composer,
    )
}

private suspend fun evaluateFake(cases: List<GoldenCase>): RouteStats {
    val stats = RouteStats(route = "fake-generator", collection = CollectionMode.AUTOMATED)
    cases.forEach { case ->
        val generator = FakeTextGenerator(
            response = "${case.bestMoveUci} develops a piece and controls the center.",
        )
        val request = case.toMoveCoachRequest()
        var text = tokenText(generator.generate(MoveCoachPromptBuilder.build(request)).toList())
        var score = EvalScorer.scoreMove(case, text)
        var retried = false
        var fellBack = false
        if (!score.grounded) {
            retried = true
            text = tokenText(generator.generate(MoveCoachPromptBuilder.buildRetry(request, text)).toList())
            score = EvalScorer.scoreMove(case, text)
        }
        if (!score.grounded) {
            fellBack = true
            text = MoveCoachFallback.build(request)
            score = EvalScorer.scoreMove(case, text)
        }
        stats.record(score, retried, fellBack)
        generator.close()
    }
    return stats
}

private fun evaluateFallback(cases: List<GoldenCase>): RouteStats {
    val stats = RouteStats(route = "deterministic-fallback", collection = CollectionMode.AUTOMATED)
    cases.forEach { case ->
        val text = MoveCoachFallback.build(case.toMoveCoachRequest())
        stats.record(EvalScorer.scoreMove(case, text), retried = false, fellBack = true)
    }
    return stats
}

private suspend fun evaluateOpeningRoute(
    name: String,
    cases: List<GoldenCase>,
    request: suspend (OpeningExplainRequest) -> OpeningExplainResponse,
): RouteStats {
    val stats = RouteStats(route = name, collection = CollectionMode.AUTOMATED)
    cases.forEach { case ->
        val response = request(case.toOpeningRequest())
        stats.record(
            EvalScorer.scoreOpening(case, response.text),
            retried = false,
            fellBack = response.composerId.contains("fallback"),
        )
    }
    return stats
}

private suspend fun evaluateDeployed(cases: List<GoldenCase>): RouteStats {
    val baseUrl = System.getenv("COACH_DEPLOYED_URL")?.trim()?.trimEnd('/')
        ?: return RouteStats(
            route = "deployed-cloud",
            collection = CollectionMode.OPTIONAL,
            available = false,
            note = "COACH_DEPLOYED_URL not set",
        )
    val client = HttpClient(CIO) {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = false }) }
        install(HttpTimeout) {
            requestTimeoutMillis = 3_000
            connectTimeoutMillis = 3_000
        }
    }
    return try {
        client.get("$baseUrl/health")
        evaluateOpeningRoute("deployed-cloud", cases) { request ->
            client.post("$baseUrl/v1/openings/explain") {
                contentType(ContentType.Application.Json)
                setBody(request)
            }.body()
        }.copy(collection = CollectionMode.OPTIONAL)
    } catch (exception: CancellationException) {
        throw exception
    } catch (exception: Exception) {
        RouteStats(
            route = "deployed-cloud",
            collection = CollectionMode.OPTIONAL,
            available = false,
            note = "unreachable: ${exception::class.simpleName}",
        )
    } finally {
        client.close()
    }
}

/**
 * Optional LLM-composed route: constructs an [LlmComposer] from env vars via [selectComposer] and
 * scores it against the same case-specific retrieval as the template route. This produces the
 * `local-llm-compose` scorecard row alongside `local-template`, so the two-row comparison (does
 * LLM composition measurably beat the deterministic template on the judge criteria, at what cost)
 * is a concrete eval finding.
 *
 * The route is OPTIONAL: it only runs when `COACH_LLM_API_KEY` + token prices are set, so the CI
 * grounding gate never depends on a live LLM provider. When unavailable, the scorecard shows the
 * row as optional.
 *
 * Note: the composer is called directly (not via the HTTP server) because the template route
 * already owns the single `testApplication` server config. Both routes exercise the same retrieval
 * → composition → validation pipeline; the only difference is the composer.
 */
private suspend fun evaluateLlmComposed(cases: List<GoldenCase>): RouteStats {
    val composer = selectComposer(System.getenv(), TemplateComposer())
    if (composer !is LlmComposer) {
        return RouteStats(
            route = "local-llm-compose",
            collection = CollectionMode.OPTIONAL,
            available = false,
            note = "COACH_LLM_API_KEY or token prices not set",
        )
    }
    val stats = RouteStats(route = "local-llm-compose", collection = CollectionMode.OPTIONAL)
    val (_, passagesByCase) = caseSpecificRetrieval(cases)
    cases.forEach { case ->
        val request = case.toOpeningRequest()
        val passages = passagesByCase[case.id].orEmpty()
        // LlmComposer.compose() does a blocking java.net.http.HttpClient.send() to a real LLM
        // endpoint (PROVIDER_TIMEOUT_MS per request). This route runs in a plain runBlocking in
        // main(), NOT inside testApplication — testApplication's body is wrapped in
        // runTestWithRealTime with a hard 60s ceiling that ~10 sequential network calls blow
        // past (UncompletedCoroutinesError: After waiting for 1m). Hop to the IO dispatcher so
        // each blocking call runs off the caller thread.
        val composed = withContext(Dispatchers.IO) { composer.compose(request, passages) }
        stats.record(
            EvalScorer.scoreOpening(case, composed.text),
            retried = false,
            fellBack = composed.composerId != "llm-v1",
        )
    }
    return stats
}

/**
 * Builds the case-specific passages used by both the template and LLM-composed routes, keyed by
 * case id so [evaluateLlmComposed] can retrieve passages without going through the HTTP server.
 */
internal fun caseSpecificRetrieval(cases: List<GoldenCase>): Pair<ServerDependencies, Map<String, List<Passage>>> {
    val dependencies = caseSpecificOpeningDependencies(cases)
    val byCase = cases.associate { case ->
        case.id to listOf(
            Passage(
                sourceId = "eval-${case.id}",
                title = "${case.eco} opening concepts",
                text = case.expectedConcepts.joinToString(", ").ifBlank { "development and center control" } + ".",
            )
        )
    }
    return dependencies to byCase
}

internal fun GoldenCase.toOpeningRequest() = OpeningExplainRequest(
    fen = fen,
    movesSan = movesSan,
    eco = eco,
    locale = "en-US",
)

internal fun tokenText(chunks: List<AiTokenOrFinal>): String = buildString {
    chunks.forEach { chunk ->
        when (chunk) {
            is AiTokenOrFinal.Token -> append(chunk.text)
            is AiTokenOrFinal.Final -> append(chunk.text)
        }
    }
}.trim()

enum class CollectionMode { AUTOMATED, OPTIONAL, MANUAL }

data class RouteStats(
    val route: String,
    val collection: CollectionMode,
    val available: Boolean = true,
    val note: String = "",
    var cases: Int = 0,
    var groundingViolations: Int = 0,
    var retries: Int = 0,
    var fallbacks: Int = 0,
    var lengthViolations: Int = 0,
) {
    fun record(score: OutputScore, retried: Boolean, fellBack: Boolean) {
        cases++
        if (!score.grounded) groundingViolations++
        if (retried) retries++
        if (fellBack) fallbacks++
        if (score.lengthViolation) lengthViolations++
    }
}

object ScorecardWriter {
    fun render(totalCases: Int, openingCases: Int, stats: List<RouteStats>): String = buildString {
        appendLine("# AI coach eval scorecard")
        appendLine()
        appendLine("> Candidate dataset: $totalCases total cases, $openingCases opening cases. Owner hand-review is still required before article publication.")
        appendLine()
        appendLine("| Route | Cases | Grounding violation | Retry | Fallback | Length violation | Collection |")
        appendLine("|---|---:|---:|---:|---:|---:|---|")
        stats.forEach { stat ->
            if (stat.available) {
                appendLine(
                    "| ${stat.route} | ${stat.cases} | ${percent(stat.groundingViolations, stat.cases)} | " +
                        "${percent(stat.retries, stat.cases)} | ${percent(stat.fallbacks, stat.cases)} | " +
                        "${percent(stat.lengthViolations, stat.cases)} | ${stat.collection.name.lowercase()} |",
                )
            } else {
                appendLine("| ${stat.route} | — | — | — | — | — | ${stat.collection.name.lowercase()} (${stat.note}) |")
            }
        }
        appendLine("| cactus-android | — | — | — | — | — | manual (hardware numbers not collected) |")
        appendLine("| foundation-models-ios | — | — | — | — | — | manual (hardware numbers not collected) |")
        appendLine()
        appendLine("The scorer is rule-based: move cases use `MoveCoachResponseValidator`; opening cases require all `expectedConcepts`. No judge model is used.")
    }

    private fun percent(value: Int, total: Int): String =
        if (total == 0) "—" else "${((value * 1000.0 / total).roundToInt() / 10.0)}%"
}
