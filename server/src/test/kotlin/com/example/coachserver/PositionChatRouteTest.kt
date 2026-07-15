package com.example.coachserver

import com.example.coachapi.ChatTurn
import com.example.coachapi.Passage
import com.example.coachapi.PositionChatRequest
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for `POST /v1/positions/chat/stream`: the SSE token events, the terminal `done`/`fallback`
 * events, and the grounding-fail → fallback downgrade. Uses an in-process fake composer (no DB, no
 * provider) so the route is deterministic and network-free — mirroring [ApplicationTest]'s
 * `testDependencies`.
 */
class PositionChatRouteTest {
    private val passage = Passage(
        sourceId = "lichess-c20",
        title = "King's Pawn Game",
        text = "Both sides use a king-pawn advance to contest the center and open development lines.",
    )

    @Test
    fun `chat stream emits token events then a done event`() = testApplication {
        application {
            openingCoachModule(
                dependencies = openingDependencies(listOf(passage)),
                chatService = PositionChatService(
                    ChatServerDependencies(
                        embedder = Embedder { FloatArray(384) { 0.25f } },
                        passageRepository = inMemoryRepo(listOf(passage)),
                        streamingChatComposer = chunkedComposer(listOf("The center ", "is contested ", "[lichess-c20].")),
                    ),
                ),
            )
        }
        val jsonClient = createClient { install(ContentNegotiation) { json() } }
        val request = PositionChatRequest(
            fen = "rnbqkbnr/pppp1ppp/8/4p3/4P3/8/PPPP1PPP/RNBQKBNR w KQkq - 0 2",
            movesSan = listOf("e4", "e5"),
            eco = "C20",
            userMessage = "What is this opening?",
        )

        val response = jsonClient.post("/v1/positions/chat/stream") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.headers["Content-Type"]!!.startsWith("text/event-stream"))
        val events = parseSseEvents(response.body<String>())
        // Three token deltas…
        assertEquals(3, events.count { it.type == "token" })
        assertEquals("The center ", events.first { it.type == "token" }.text)
        // …then a terminal done event.
        assertEquals("done", events.last().type)
    }

    @Test
    fun `chat stream template composer emits tokens and a done event`() = testApplication {
        application {
            openingCoachModule(
                dependencies = openingDependencies(listOf(passage)),
                chatService = PositionChatService(
                    ChatServerDependencies(
                        embedder = Embedder { FloatArray(384) { 0.25f } },
                        passageRepository = inMemoryRepo(listOf(passage)),
                        streamingChatComposer = TemplateChatComposer(),
                    ),
                ),
            )
        }
        val jsonClient = createClient { install(ContentNegotiation) { json() } }
        val request = PositionChatRequest(
            fen = "rnbqkbnr/pppp1ppp/8/4p3/4P3/8/PPPP1PPP/RNBQKBNR w KQkq - 0 2",
            movesSan = listOf("e4", "e5"),
            eco = "C20",
            userMessage = "Summarize the opening.",
        )

        val response = jsonClient.post("/v1/positions/chat/stream") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val events = parseSseEvents(response.body<String>())
        assertTrue(events.any { it.type == "token" })
        assertEquals("done", events.last().type)
        // Template composer id is exposed via the done event.
        assertEquals("template-chat-v1", events.last().composerId)
    }

    @Test
    fun `chat stream downgrades unvalidated provider prose to a fallback event`() = testApplication {
        // The real LlmChatComposer streams provider tokens, then validates the ACCUMULATED text at
        // stream end. A forbidden phrase fails validation → the composer emits a `fallback` (never
        // `done`). The route forwards whatever the composer emits, so this exercises the whole path.
        val forbiddenStreamingClient = StreamingLlmClient { _, _, _, _ ->
            flow { listOf("I think Stockfish ", "probably depth 30 ", "likes this [lichess-c20].").forEach { emit(it) } }
        }
        application {
            openingCoachModule(
                dependencies = openingDependencies(listOf(passage)),
                chatService = PositionChatService(
                    ChatServerDependencies(
                        embedder = Embedder { FloatArray(384) { 0.25f } },
                        passageRepository = inMemoryRepo(listOf(passage)),
                        streamingChatComposer = LlmChatComposer(
                            client = forbiddenStreamingClient,
                            fallback = TemplateChatComposer(),
                            budget = ProviderCostBudget(0.2, 1.0, 1.0),
                        ),
                    ),
                ),
            )
        }
        val jsonClient = createClient { install(ContentNegotiation) { json() } }
        val request = PositionChatRequest(
            fen = "rnbqkbnr/pppp1ppp/8/4p3/4P3/8/PPPP1PPP/RNBQKBNR w KQkq - 0 2",
            movesSan = listOf("e4", "e5"),
            eco = "C20",
            userMessage = "Is this good for White?",
        )

        val response = jsonClient.post("/v1/positions/chat/stream") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val events = parseSseEvents(response.body<String>())
        // No `done` — the turn failed validation and downgraded to fallback.
        assertEquals("fallback", events.last().type)
        assertTrue(events.none { it.type == "done" })
        // The fallback carries deterministic text + the template composer id.
        assertTrue(events.last().text!!.isNotBlank())
        assertEquals("template-chat-v1", events.last().composerId)
    }

    @Test
    fun `chat stream rejects an over-long user message`() = testApplication {
        application {
            openingCoachModule(
                dependencies = openingDependencies(listOf(passage)),
                chatService = PositionChatService(
                    ChatServerDependencies(
                        embedder = Embedder { FloatArray(384) { 0.25f } },
                        passageRepository = inMemoryRepo(listOf(passage)),
                        streamingChatComposer = TemplateChatComposer(),
                    ),
                ),
            )
        }
        val jsonClient = createClient { install(ContentNegotiation) { json() } }
        val request = PositionChatRequest(
            fen = "rnbqkbnr/pppp1ppp/8/4p3/4P3/8/PPPP1PPP/RNBQKBNR w KQkq - 0 2",
            movesSan = listOf("e4", "e5"),
            userMessage = "x".repeat(501),
        )

        val response = jsonClient.post("/v1/positions/chat/stream") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals("invalid_request", response.body<com.example.coachapi.ApiError>().code)
    }

    @Test
    fun `chat stream carries multi-turn history to the composer`() = testApplication {
        var observedHistory: List<ChatTurn>? = null
        application {
            openingCoachModule(
                dependencies = openingDependencies(listOf(passage)),
                chatService = PositionChatService(
                    ChatServerDependencies(
                        embedder = Embedder { FloatArray(384) { 0.25f } },
                        passageRepository = inMemoryRepo(listOf(passage)),
                        streamingChatComposer = StreamingChatComposer { req, _ ->
                            observedHistory = req.history
                            // Valid grounded reply so the turn completes (not fallback).
                            chunkedComposer(listOf("The center is contested [lichess-c20]."))
                                .streamCompose(req, listOf(passage))
                        },
                    ),
                ),
            )
        }
        val jsonClient = createClient { install(ContentNegotiation) { json() } }
        val request = PositionChatRequest(
            fen = "rnbqkbnr/pppp1ppp/8/4p3/4P3/8/PPPP1PPP/RNBQKBNR w KQkq - 0 2",
            movesSan = listOf("e4", "e5"),
            eco = "C20",
            history = listOf(
                ChatTurn("user", "What is this opening?"),
                ChatTurn("assistant", "A king's pawn game [lichess-c20]."),
            ),
            userMessage = "And what should White play next?",
        )

        val response = jsonClient.post("/v1/positions/chat/stream") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val history = requireNotNull(observedHistory)
        assertEquals(2, history.size)
        assertEquals("user", history[0].role)
    }

    @Test
    fun `validator accepts grounded cited prose`() {
        val text = "The king pawns contest the center [lichess-c20]. Development follows from the central pawn move [lichess-c20]."
        val valid = PositionChatValidator.validate(text, listOf(passage))
        assertEquals(text, valid)
    }

    @Test
    fun `validator rejects forbidden engine phrases`() {
        val text = "I think Stockfish probably depth 30 likes the center [lichess-c20]."
        assertEquals(null, PositionChatValidator.validate(text, listOf(passage)))
    }

    @Test
    fun `validator rejects over-length output`() {
        val text = "Center [lichess-c20]. " + "x".repeat(PositionChatValidator.MAX_OUTPUT_CHARS)
        assertEquals(null, PositionChatValidator.validate(text, listOf(passage)))
    }

    @Test
    fun `validator rejects uncited sentences`() {
        val text = "The king pawns contest the center. Development follows naturally from the central pawn structure."
        assertEquals(null, PositionChatValidator.validate(text, listOf(passage)))
    }

    /** Minimal opening-explainer deps so the module mounts; the chat route is what's under test. */
    private fun openingDependencies(passages: List<Passage>) = ServerDependencies(
        embedder = Embedder { FloatArray(384) { 0.25f } },
        passageRepository = inMemoryRepo(passages),
        composer = TemplateComposer(),
    )

    private fun chunkedComposer(deltas: List<String>): StreamingChatComposer = StreamingChatComposer { _, _ ->
        flow {
            deltas.forEach { emit(ChatChunk.Token(it)) }
            emit(ChatChunk.Done("llm-chat-v1"))
        }
    }

    private fun inMemoryRepo(passages: List<Passage>) = object : PassageRepository {
        override fun retrieve(embedding: FloatArray, limit: Int): List<Passage> = passages.take(limit)
        override fun upsert(passage: Passage, embedding: FloatArray) = Unit
    }

    private val sseJson = Json { ignoreUnknownKeys = true }

    private data class ParsedEvent(val type: String, val text: String?, val composerId: String?)

    private fun parseSseEvents(body: String): List<ParsedEvent> = body
        .split("\n\n")
        .filter { it.startsWith("data: ") }
        .map { payload ->
            val obj = sseJson.parseToJsonElement(payload.removePrefix("data: ")).jsonObject
            ParsedEvent(
                type = obj.getValue("type").jsonPrimitive.content,
                text = obj["text"]?.jsonPrimitive?.content,
                composerId = obj["composerId"]?.jsonPrimitive?.content,
            )
        }
}
