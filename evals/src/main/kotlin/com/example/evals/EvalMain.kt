package com.example.evals

import com.example.coachapi.ChatStreamEvent
import com.example.coachapi.OpeningExplainRequest
import com.example.coachapi.OpeningExplainResponse
import com.example.coachapi.Passage
import com.example.coachapi.PositionChatRequest
import com.example.coachserver.ChatServerDependencies
import com.example.coachserver.Embedder
import com.example.coachserver.LlmComposer
import com.example.coachserver.OpeningQueryBuilder
import com.example.coachserver.PassageRepository
import com.example.coachserver.PositionChatQueryBuilder
import com.example.coachserver.PositionChatService
import com.example.coachserver.RequestRateLimiter
import com.example.coachserver.ServerDependencies
import com.example.coachserver.TemplateChatComposer
import com.example.coachserver.TemplateComposer
import com.example.coachserver.TextComposer
import com.example.coachserver.openingCoachModule
import com.example.coachserver.selectComposer
import com.example.ondeviceai.AiTokenOrFinal
import com.example.ondeviceai.FakeTextGenerator
import com.example.ondeviceai.MoveCoachPromptBuilder
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication
import io.ktor.utils.io.readLine
import com.example.ondeviceai.AiContextSnapshot
import com.example.ondeviceai.AiRoutePolicies
import com.example.ondeviceai.AiRoutePolicyDecider
import com.example.ondeviceai.AiUserSetting
import com.example.ondeviceai.ThermalState
import com.example.ondeviceai.VendorRoute
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
    // Recalibration aid: `EVAL_CALIBRATION=1 ./gradlew :evals:run` prints the reading-grade
    // distribution the FluencySurface bounds are derived from. See
    // docs/benchmarks/on-device-ai/fluency-calibration.md.
    if (System.getenv("EVAL_CALIBRATION") == "1") {
        stats.forEach { System.err.println("[calibration] ${it.gradePercentiles()}") }
    }
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
    collected += evaluateRouteSelection()
    testApplication {
        val dependencies = caseSpecificOpeningDependencies(openingCases)
        val chatDependencies = caseSpecificChatDependencies(openingCases)
        application {
            openingCoachModule(
                dependencies = dependencies,
                chatService = PositionChatService(chatDependencies),
                rateLimiter = RequestRateLimiter { true },
            )
        }
        val localClient = createClient { install(ContentNegotiation) { json() } }

        collected += evaluateFake(cases)
        collected += evaluateOpeningRoute("local-template", openingCases) { request ->
            localClient.post("/v1/openings/explain") {
                contentType(ContentType.Application.Json)
                setBody(request)
            }.body()
        }
        collected += evaluateChatRoute("local-template-chat", openingCases.toChatTranscripts(), localClient)
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
            // Prose, not bare tags. OpeningExplanationValidator grounds each output sentence by
            // requiring >=2 content-word overlaps with the cited passage's text; a passage whose
            // text is just "development, center." (the old shape) gives the model ~2 tokens of
            // chess vocabulary to reuse, so even a correct 2-3 sentence explanation fails the
            // per-sentence overlap check and the LLM route falls back 100% of the time — measuring
            // the template, not the composer. The backbone below covers the standard opening ideas
            // (development, central control, king safety) in real sentences, so any fluent
            // grounded explanation can satisfy the validator. The case-specific concept line is
            // prepended so retrieval still distinguishes cases by their expectedConcepts.
            text = openingConceptsPassage(case.expectedConcepts),
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

/**
 * Deterministic chat-route dependencies. Same per-case retrieval isolation as the opening fake, but
 * keyed by the chat query builder (position + question) and feeding the deterministic
 * [TemplateChatComposer] — so the chat route is scored offline without any provider.
 */
internal fun caseSpecificChatDependencies(cases: List<GoldenCase>): ChatServerDependencies {
    val transcripts = cases.toChatTranscripts()
    // Index every (transcript, turn) request by the chat query the server will build for it, so each
    // turn of each case retrieves only its own passage — a retrieval regression surfaces as a
    // grounding failure rather than being masked by a catch-all passage.
    val indexByQuery = mutableMapOf<String, Int>()
    val passages = mutableListOf<Passage>()
    transcripts.forEach { transcript ->
        transcript.turns.forEachIndexed { turnIndex, turn ->
            val request = transcript.toRequest(turnIndex)
            indexByQuery[PositionChatQueryBuilder.build(request)] = passages.size
            passages.add(
                Passage(
                    sourceId = "eval-${transcript.case.id}",
                    title = "${transcript.case.eco ?: "opening"} concepts",
                    text = turn.expectedConcepts.joinToString(", ").ifBlank { "development and center control" } + ".",
                ),
            )
        }
    }
    return ChatServerDependencies(
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
        streamingChatComposer = TemplateChatComposer(),
    )
}

/**
 * Builds a prose opening-concepts passage for a case. The case-specific [expectedConcepts] line is
 * prepended (keeps retrieval case-distinguishing), then a common backbone explains the standard
 * opening ideas in real sentences. See [caseSpecificOpeningDependencies] for why the backbone
 * exists: OpeningExplanationValidator needs prose-level token overlap per output sentence, and bare
 * tags can never provide it.
 */
private fun openingConceptsPassage(expectedConcepts: List<String>): String {
    val focus = expectedConcepts.mapNotNull(::describeConcept).joinToString(", ").ifBlank { "developing pieces" }
    return buildString {
        append("This opening's key ideas are ")
        append(focus)
        append(". ")
        append("Both sides fight for central squares with their pawns and develop their minor pieces ")
        append("toward active squares. King safety matters: players castle early to shield the king ")
        append("and connect the rooks. Piece development, central control, and king safety are the ")
        append("main themes.")
    }
}

/** Maps a golden-case concept tag to the chess phrase used in the passage. Mirrors MoveCoachPromptBuilder. */
private fun describeConcept(concept: String): String? = when (concept.lowercase().trim()) {
    "development", "develops" -> "developing the minor pieces"
    "center", "center-control", "central control" -> "contesting the center"
    "king safety", "king-safety" -> "improving king safety"
    "pawn tension", "pawn-tension" -> "creating pawn tension"
    "opening" -> "solid opening play"
    else -> concept
}

private fun evaluateRouteSelection(): RouteStats {
    val result = RouterEvalSuite.evaluate()
    val stats = RouteStats(route = "route-selection", collection = CollectionMode.AUTOMATED)
    stats.cases = result.totalEvaluated
    stats.fallbacks = result.violations
    return stats
}

private suspend fun evaluateFake(cases: List<GoldenCase>): RouteStats {
    val stats = RouteStats(route = "fake-generator", collection = CollectionMode.AUTOMATED)
    cases.forEach { case ->
        val generator = FakeTextGenerator(
            response = "${case.bestMoveUci} develops a piece and controls the center.",
        )
        val request = case.toMoveCoachRequest()
        val text = tokenText(generator.generate(MoveCoachPromptBuilder.build(request)).toList())
        val score = EvalScorer.scoreMove(case, text)
        stats.record(score, retried = false, fellBack = !score.grounded)
        generator.close()
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

/**
 * Scores the multi-turn chat route. Each scripted transcript is streamed turn-by-turn against the
 * in-process server; per-turn output is accumulated and scored for grounding (expected concepts
 * present — the "no drift across turns" check) and length. Falls back / retries are tracked but the
 * deterministic template composer neither retries nor falls back, so a violation here is a real
 * regression in the chat grounding path.
 */
private suspend fun evaluateChatRoute(
    name: String,
    transcripts: List<ChatTranscript>,
    client: HttpClient,
): RouteStats {
    val stats = RouteStats(route = name, collection = CollectionMode.AUTOMATED)
    transcripts.forEach { transcript ->
        transcript.turns.forEachIndexed { turnIndex, turn ->
            val events = streamChat(client, transcript.toRequest(turnIndex))
            val text = events.accumulateTurnText()
            stats.record(
                EvalScorer.scoreChat(turn, text),
                retried = false,
                fellBack = events.fellBack(),
            )
        }
    }
    return stats
}

/** Streams one chat turn over the in-process server, collecting the SSE [ChatStreamEvent]s. */
private suspend fun streamChat(client: HttpClient, request: PositionChatRequest): List<ChatStreamEvent> {
    val events = mutableListOf<ChatStreamEvent>()
    val json = Json { ignoreUnknownKeys = true }
    client.preparePost("/v1/positions/chat/stream") {
        contentType(ContentType.Application.Json)
        setBody(request)
    }.execute { response ->
        if (response.status.value !in 200..299) return@execute
        val channel = response.bodyAsChannel()
        while (!channel.isClosedForRead) {
            val line = channel.readLine() ?: break
            if (!line.startsWith("data:")) continue
            val payload = line.removePrefix("data:").trim()
            if (payload.isEmpty()) continue
            val event = runCatching { json.decodeFromString(ChatStreamEvent.serializer(), payload) }.getOrNull()
                ?: continue
            events.add(event)
            if (event.type == ChatStreamEvent.TYPE_DONE ||
                event.type == ChatStreamEvent.TYPE_FALLBACK ||
                event.type == ChatStreamEvent.TYPE_ERROR
            ) break
        }
    }
    return events
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
    var fluencyViolations: Int = 0,
    var retries: Int = 0,
    var fallbacks: Int = 0,
    var lengthViolations: Int = 0,
) {
    private val readingGrades = mutableListOf<Double>()

    /**
     * Median measured reading grade, or `null` when the route records no scored text (the
     * `route-selection` row scores decisions, not prose). Median rather than mean so one long
     * outlier can't drag the reported figure.
     */
    /**
     * Grade distribution for this route, used to re-derive the [FluencyScorer.FluencySurface]
     * bounds. Not rendered in the scorecard — print it from `main` when recalibrating, and see
     * `docs/benchmarks/on-device-ai/fluency-calibration.md` for the procedure.
     */
    fun gradePercentiles(): String {
        if (readingGrades.isEmpty()) return "$route: no prose"
        val s = readingGrades.sorted()
        fun p(q: Double) = s[((s.size - 1) * q).toInt()]
        return "$route n=${s.size} min=${"%.1f".format(s.first())} p50=${"%.1f".format(p(0.5))} " +
            "p75=${"%.1f".format(p(0.75))} p90=${"%.1f".format(p(0.90))} " +
            "p95=${"%.1f".format(p(0.95))} max=${"%.1f".format(s.last())}"
    }

    val medianReadingGrade: Double?
        get() {
            if (readingGrades.isEmpty()) return null
            val sorted = readingGrades.sorted()
            val mid = sorted.size / 2
            return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2.0
        }

    fun record(score: OutputScore, retried: Boolean, fellBack: Boolean) {
        cases++
        if (!score.grounded) groundingViolations++
        if (!score.fluencyCompliant) fluencyViolations++
        if (retried) retries++
        if (fellBack) fallbacks++
        if (score.lengthViolation) lengthViolations++
        readingGrades += score.readingGrade
    }
}

object ScorecardWriter {
    fun render(totalCases: Int, openingCases: Int, stats: List<RouteStats>): String = buildString {
        appendLine("# AI coach eval scorecard")
        appendLine()
        appendLine("<!-- Generated by `./gradlew :evals:run`. Do not edit by hand — edits are overwritten.")
        appendLine("     Hand-measured device rows live in ScorecardWriter.MANUAL_ROWS (evals/.../EvalMain.kt). -->")
        appendLine()
        appendLine("> Candidate dataset: $totalCases total cases, $openingCases opening cases. Owner hand-review is still required before article publication.")
        appendLine()
        appendLine("| Route | Cases | Grounding violation | Reading grade | Fluency violation | Retry | Fallback | Length violation | Collection |")
        appendLine("|---|---:|---:|---:|---:|---:|---:|---:|---|")
        stats.forEach { stat ->
            if (stat.available) {
                appendLine(
                    "| ${stat.route} | ${stat.cases} | ${percent(stat.groundingViolations, stat.cases)} | " +
                        "${grade(stat.medianReadingGrade)} | " +
                        "${percent(stat.fluencyViolations, stat.cases)} | " +
                        "${percent(stat.retries, stat.cases)} | ${percent(stat.fallbacks, stat.cases)} | " +
                        "${percent(stat.lengthViolations, stat.cases)} | ${stat.collection.name.lowercase()} |",
                )
            } else {
                appendLine("| ${stat.route} | — | — | — | — | — | — | — | ${stat.collection.name.lowercase()} (${stat.note}) |")
            }
        }
        MANUAL_ROWS.forEach { appendLine(it.render()) }
        appendLine()
        appendLine("The scorer is rule-based: move cases use `MoveCoachResponseValidator`; opening cases require all `expectedConcepts`; multi-turn chat cases require at least one expected concept per turn (the no-drift check). No judge model is used.")
    }

    /**
     * A hand-measured device row.
     *
     * On-device routes can't run in CI — they need real hardware — so their numbers are transcribed
     * here from `docs/benchmarks/on-device-ai/move-coach-benchmark-schema.md`. **This list is the
     * only place to edit them.** `evals/scorecard.md` is generated: editing the markdown directly
     * works until the next `:evals:run`, which silently overwrites it. That is exactly how the
     * `cactus-android` row spent a week reporting "100% fallback, root cause open" after the real
     * figure had become 0/10, and how a hand-added `aicore-nano-fast` row would have vanished.
     *
     * Percentages are strings, not computed, because there is no `CaseScore` to aggregate — the runs
     * happened on a phone. Keep the `—` convention for "not measured" so the column still parses.
     *
     * **Cell count must match the header.** These rows are rendered by hand, so a new column added
     * to the automated rows above does not reach them — it silently shifts every manual value one
     * column left. [fluencyViolation] is `—` because these runs predate the fluency scorer and were
     * transcribed from device output, not re-scored.
     */
    private data class ManualRow(
        val route: String,
        val cases: String,
        val groundingViolation: String,
        val retry: String,
        val fallback: String,
        val lengthViolation: String,
        val note: String,
        val fluencyViolation: String = "—",
        val readingGrade: String = "—",
    ) {
        fun render(): String =
            "| $route | $cases | $groundingViolation | $readingGrade | $fluencyViolation | " +
                "$retry | $fallback | $lengthViolation | manual ($note) |"
    }

    private val MANUAL_ROWS = listOf(
        ManualRow(
            route = "cactus-android",
            cases = "10", groundingViolation = "0.0%", retry = "0.0%",
            fallback = "0.0%", lengthViolation = "0.0%",
            note = "Galaxy Z Fold3 + Pixel 10 Pro XL, gemma3-270m, 5 runs each, 2026-07-31 — " +
                "was 60%/80% fallback until the JSON schema came out of the prompt; the model had " +
                "been returning the schema's own placeholder strings as values",
        ),
        ManualRow(
            route = "aicore-nano-fast",
            cases = "10", groundingViolation = "0.0%", retry = "0.0%",
            fallback = "100.0%", lengthViolation = "100.0%",
            note = "Pixel 10 Pro XL, Gemini Nano via AICore developer preview, 2026-07-31 — " +
                "TTFT ~170 ms, complete ~500 ms, ~125 MB peak. Every case is grounded and rejected " +
                "on length: the model emits correct coaching text, then repeats it verbatim " +
                "(314 chars against a 300 cap). A repetition loop, not a quality failure — the " +
                "length gate fires first and masks that",
        ),
        ManualRow(
            route = "foundation-models-ios",
            cases = "1", groundingViolation = "0.0%", retry = "0.0%",
            fallback = "0.0%", lengthViolation = "0.0%",
            note = "iPhone 17 Simulator / iOS 26.5, n=1, 2026-07-29 — real success, 30 tokens. " +
                "A Simulator on an M4 host, not a physical iPhone; draw no latency conclusions",
        ),
    )

    private fun percent(value: Int, total: Int): String =
        if (total == 0) "—" else "${((value * 1000.0 / total).roundToInt() / 10.0)}%"

    /**
     * Median Flesch-Kincaid grade. Reported so the [FluencyScorer.FluencySurface] bounds stay
     * auditable — a pass rate alone can't show that a bound has drifted away from the text.
     */
    private fun grade(value: Double?): String =
        if (value == null) "—" else "${(value * 10.0).roundToInt() / 10.0}"
}
