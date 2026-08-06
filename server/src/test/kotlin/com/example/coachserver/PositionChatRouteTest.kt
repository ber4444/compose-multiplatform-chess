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
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
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
        // …then a terminal done event, followed by diagnostics.
        assertEquals("done", events[events.lastIndex - 1].type)
        assertEquals("diagnostics", events.last().type)
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
        assertEquals("done", events[events.lastIndex - 1].type)
        // Template composer id is exposed via the done event.
        assertEquals("template-chat-v1", events[events.lastIndex - 1].composerId)
        assertEquals("diagnostics", events.last().type)
    }

    @Test
    fun `template chat is honest when retrieved material has no plan answer`() = runBlocking {
        val request = PositionChatRequest(
            fen = "rnbqkbnr/pppp1ppp/8/4p3/4P3/8/PPPP1PPP/RNBQKBNR w KQkq - 0 2",
            movesSan = listOf("e4", "e5"),
            userMessage = "What is White's plan?",
        )

        val text = TemplateChatComposer().streamCompose(request, listOf(passage)).toList()
            .filterIsInstance<ChatChunk.Token>()
            .joinToString("") { it.text }

        assertTrue(text.startsWith("The retrieved material does not specify a plan."))
        assertTrue(text.contains("Both sides use a king-pawn advance"))
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
                            // Cap must admit MAX_OUTPUT_TOKENS at these (deliberately inflated)
                            // fixture prices, or the budget guard short-circuits to the template
                            // path and this test never reaches the validation branch it asserts on.
                            budget = ProviderCostBudget(5.0, 1.0, 1.0),
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
        // No `done` — the turn failed validation and downgraded to fallback, then diagnostics.
        assertEquals("fallback", events[events.lastIndex - 1].type)
        assertTrue(events.none { it.type == "done" })
        // The fallback carries deterministic text + the template composer id.
        assertTrue(events[events.lastIndex - 1].text!!.isNotBlank())
        assertEquals("template-chat-v1", events[events.lastIndex - 1].composerId)
        assertEquals("diagnostics", events.last().type)
    }

    /**
     * The chat output-token budget (`COACH_LLM_CHAT_MAX_OUTPUT_TOKENS`, default
     * [LlmChatComposer.DEFAULT_MAX_OUTPUT_TOKENS]) was previously hard-coded in [LlmChatComposer];
     * it is now a constructor parameter threaded through to both the cost-ceiling check and the
     * provider call. This verifies the parameter actually reaches [StreamingLlmClient.streamGenerate]
     * (a fake client, no network) rather than silently falling back to the compiled-in constant.
     */
    @Test
    fun `LlmChatComposer forwards a custom maxOutputTokens to the provider call`() = runBlocking {
        var observedMaxTokens: Int? = null
        val client = StreamingLlmClient { _, _, _, maxTokens ->
            observedMaxTokens = maxTokens
            flow { emit("The center is contested [lichess-c20].") }
        }
        val composer = LlmChatComposer(
            client = client,
            fallback = TemplateChatComposer(),
            budget = ProviderCostBudget(maxUsdCents = 5.0, inputUsdPerMillionTokens = 0.0, outputUsdPerMillionTokens = 0.0),
            maxOutputTokens = 77,
        )
        val request = PositionChatRequest(
            fen = "rnbqkbnr/pppp1ppp/8/4p3/4P3/8/PPPP1PPP/RNBQKBNR w KQkq - 0 2",
            movesSan = listOf("e4", "e5"),
            eco = "C20",
            userMessage = "What is this opening?",
        )

        composer.streamCompose(request, listOf(passage)).toList()

        assertEquals(77, observedMaxTokens)
    }

    /** [parseChatMaxOutputTokens] is the env-gating logic `selectChatComposer` uses to build the
     * value above — tested directly since `selectChatComposer` itself needs a live database. */
    @Test
    fun `parseChatMaxOutputTokens defaults when the env var is unset`() {
        assertEquals(LlmChatComposer.DEFAULT_MAX_OUTPUT_TOKENS, parseChatMaxOutputTokens(emptyMap()))
    }

    @Test
    fun `parseChatMaxOutputTokens honors a valid positive override`() {
        val env = mapOf("COACH_LLM_CHAT_MAX_OUTPUT_TOKENS" to "4096")
        assertEquals(4096, parseChatMaxOutputTokens(env))
    }

    @Test
    fun `parseChatMaxOutputTokens defaults on a non-positive or malformed value`() {
        assertEquals(LlmChatComposer.DEFAULT_MAX_OUTPUT_TOKENS, parseChatMaxOutputTokens(mapOf("COACH_LLM_CHAT_MAX_OUTPUT_TOKENS" to "0")))
        assertEquals(LlmChatComposer.DEFAULT_MAX_OUTPUT_TOKENS, parseChatMaxOutputTokens(mapOf("COACH_LLM_CHAT_MAX_OUTPUT_TOKENS" to "-5")))
        assertEquals(LlmChatComposer.DEFAULT_MAX_OUTPUT_TOKENS, parseChatMaxOutputTokens(mapOf("COACH_LLM_CHAT_MAX_OUTPUT_TOKENS" to "not-a-number")))
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

    /**
     * Regression: the sentence splitter used to fire on any `.` followed by whitespace, so the
     * periods in algebraic move numbers broke one cited sentence into several uncited fragments —
     * blowing the sentence cap and failing the per-sentence citation check. Answers that quote a
     * move sequence are the common case for this feature, so they must survive validation.
     */
    @Test
    fun `validator accepts prose quoting a pawn move sequence`() {
        val text = "The king pawn advance 1. e4 e5 3. d4 continues to contest the center [lichess-c20]."
        assertEquals(text, PositionChatValidator.validate(text, listOf(passage)))
    }

    @Test
    fun `validator accepts prose quoting a piece move sequence`() {
        val text = "The king pawn advance 1. e4 e5 2. Nf3 Nc6 continues to contest the center [lichess-c20]."
        assertEquals(text, PositionChatValidator.validate(text, listOf(passage)))
    }

    // --- B4b: strip before validating, and never stream the fence to the user -----------------

    @Test
    fun `a fenced stream both validates and reaches the user without the fence`() {
        // B4b end-to-end at the composer's two exit points. Note the validator is permissive
        // enough to accept fenced text as-is (the citation survives), so stripping before
        // validating is defensive; what it definitely fixes is the fence being *streamed*.
        val inner = "The king pawns contest the center [lichess-c20]."
        val stripper = LlmChatComposer.CodeFenceStripper()
        val emitted = stripper.push("```json\n$inner\n```") + stripper.flush()

        assertEquals(false, emitted.contains("```"))
        assertEquals(inner, PositionChatValidator.validate(emitted, listOf(passage)))
    }

    @Test
    fun `stripper removes an opening fence with a language tag`() {
        val stripper = LlmChatComposer.CodeFenceStripper()
        val out = stripper.push("```json\nThe center [lichess-c20].\n```") + stripper.flush()
        assertEquals("The center [lichess-c20].\n", out)
    }

    @Test
    fun `stripper passes unfenced prose through unchanged`() {
        val stripper = LlmChatComposer.CodeFenceStripper()
        val out = stripper.push("The center [lichess-c20].") + stripper.flush()
        assertEquals("The center [lichess-c20].", out)
    }

    @Test
    fun `stripper holds back a fence split across token boundaries`() {
        // The real failure mode: tokens arrive as "``", "`js", "\n", … so a whole-string regex
        // never sees a fence and the user watches one render character by character.
        val stripper = LlmChatComposer.CodeFenceStripper()
        val emitted = buildString {
            listOf("``", "`", "json", "\n", "The center ", "[lichess-c20].", "\n``", "`")
                .forEach { append(stripper.push(it)) }
            append(stripper.flush())
        }
        assertEquals(false, emitted.contains("`"))
        assertEquals("The center [lichess-c20].", emitted.trim())
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

    @Test
    fun chatDiagnosticsFollowsDoneAndIdentifiesComposer() = runBlocking {
        val request = PositionChatRequest(
            fen = "fen", movesSan = listOf("e4", "e5"), eco = "C20", userMessage = "Q?"
        )
        val service = PositionChatService(
            ChatServerDependencies(
                embedder = Embedder { FloatArray(384) { 0.25f } },
                passageRepository = inMemoryRepo(listOf(passage)),
                streamingChatComposer = TemplateChatComposer(),
                releaseVersion = "git-abc123"
            )
        )
        val chunks = service.chat(request).toList()
        
        assertEquals(ChatChunk.Done("template-chat-v1"), chunks[chunks.lastIndex - 1])
        val diag = chunks.last() as ChatChunk.Diagnostics
        assertEquals("template-chat-v1", diag.diagnostics.composerId)
        assertEquals(listOf("lichess-c20"), diag.diagnostics.retrievedPassageIds)
        assertEquals("git-abc123", diag.diagnostics.releaseVersion)
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
        override fun retrieve(
            embedding: FloatArray,
            limit: Int,
            movesSan: List<String>,
            eco: String?,
        ) = RetrievalResult(passages.take(limit), eco)

        override fun upsert(
            passage: Passage,
            embedding: FloatArray,
            eco: String?,
            moves: String?,
        ) = Unit
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
