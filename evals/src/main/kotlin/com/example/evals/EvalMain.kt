package com.example.evals

import com.example.coachapi.ChatStreamEvent
import com.example.coachapi.OpeningExplainRequest
import com.example.coachapi.OpeningExplainResponse
import com.example.coachapi.Passage
import com.example.coachapi.PositionChatRequest
import com.example.coachserver.ChatServerDependencies
import com.example.coachserver.Embedder
import com.example.coachserver.OpeningQueryBuilder
import com.example.coachserver.PassageRepository
import com.example.coachserver.PositionChatQueryBuilder
import com.example.coachserver.PositionChatService
import com.example.coachserver.RequestRateLimiter
import com.example.coachserver.ServerDependencies
import com.example.coachserver.TemplateChatComposer
import com.example.coachserver.TemplateComposer
import com.example.coachserver.openingCoachModule
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
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication
import io.ktor.utils.io.readLine
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.roundToInt

fun main() = testApplication {
    val cases = GoldenCaseLoader.load(Path.of("golden/candidates.json"))
    val openingCases = cases.filter { it.eco != null }
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

    val stats = mutableListOf<RouteStats>()
    stats += evaluateFake(cases)
    stats += evaluateFallback(cases)
    stats += evaluateOpeningRoute("local-template", openingCases) { request ->
        localClient.post("/v1/openings/explain") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }.body()
    }
    stats += evaluateChatRoute("local-template-chat", openingCases.toChatTranscripts(), localClient)
    stats += evaluateDeployed(openingCases)

    val scorecard = ScorecardWriter.render(cases.size, openingCases.size, stats)
    Files.writeString(Path.of("scorecard.md"), scorecard)

    val regressions = stats.filter { it.available && it.collection == CollectionMode.AUTOMATED }
        .filter { it.groundingViolations > 0 }
    check(regressions.isEmpty()) {
        "Grounding violations detected: ${regressions.joinToString { "${it.route}=${it.groundingViolations}" }}"
    }
}

/**
 * Deterministic retrieval fake keyed by the production opening query. Each case gets only its own
 * passage, so a query-construction regression or mismatched retrieval produces a grounding failure
 * instead of being hidden by one passage containing every concept in the dataset.
 */
internal fun caseSpecificOpeningDependencies(cases: List<GoldenCase>): ServerDependencies {
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
        composer = TemplateComposer(),
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
        appendLine("The scorer is rule-based: move cases use `MoveCoachResponseValidator`; opening cases require all `expectedConcepts`; multi-turn chat cases require at least one expected concept per turn (the no-drift check). No judge model is used.")
    }

    private fun percent(value: Int, total: Int): String =
        if (total == 0) "—" else "${((value * 1000.0 / total).roundToInt() / 10.0)}%"
}
