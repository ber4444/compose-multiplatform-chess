package com.example.coachserver

import com.example.coachapi.Passage
import com.example.coachapi.PositionChatRequest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **Does the chat route stream?**
 *
 * Measured against the live deployment on 2026-08-05: it does not. Four calls each returned the
 * whole answer as a *single* `token` event — time-to-first-token 10.9 s, `done` 20 ms later,
 * `composerId = llm-chat-v1` (so the provider path, not the template). The published claim that
 * tokens reach the UI incrementally does not hold for that deployment.
 *
 * Every existing test asserts on *accumulated* text, so all of them pass on a one-chunk stream —
 * which is why nothing caught it. These assert on chunk **count**, and they localize the cause,
 * because "it doesn't stream" has three possible owners and only one of them is ours:
 *
 * - **Our SSE writer** — ruled out by the live measurement itself: the response contains exactly one
 *   `data:` line, so there are no events being coalesced. A flushing bug would have produced several
 *   lines arriving together.
 * - **[LlmChatComposer]'s think-stripping state machine** — ruled out here: given deltas, it emits
 *   chunks, including across a `<think>` block and a code fence.
 * - **The provider**, which leaves it upstream of this codebase. `OpenAiCompatibleStreamingLlmClient`
 *   already knows this happens and handles it (`deltas == 0` → emit the whole completion as one
 *   chunk, with a `chat-provider-oneshot` log line naming it).
 */
class ChatStreamingChunkCountTest {

    private val passage = Passage(
        sourceId = "lichess-c20",
        title = "C20 — King's Pawn Game",
        text = "Both king pawns contest the center and open lines for piece development and king safety.",
    )

    private val request = PositionChatRequest(
        fen = "rnbqkbnr/pppp1ppp/8/4p3/4P3/8/PPPP1PPP/RNBQKBNR w KQkq - 0 2",
        movesSan = listOf("e4", "e5"),
        eco = "C20",
        userMessage = "What is this opening?",
    )

    private fun composerOver(deltas: List<String>) = LlmChatComposer(
        client = { _, _, _, _ -> flow { deltas.forEach { emit(it) } } },
        fallback = TemplateChatComposer(),
        budget = ProviderCostBudget(maxUsdCents = 5.0, inputUsdPerMillionTokens = 0.0, outputUsdPerMillionTokens = 0.0),
    )

    private fun tokenChunks(deltas: List<String>): List<String> = runBlocking {
        composerOver(deltas).streamCompose(request, listOf(passage)).toList()
            .filterIsInstance<ChatChunk.Token>()
            .map(ChatChunk.Token::text)
    }

    @Test
    fun `a multi-delta provider response reaches the client as multiple chunks`() {
        val deltas = listOf(
            "Both king pawns ", "contest the center ", "and open lines ", "for development ",
            "[lichess-c20]. ", "King safety ", "follows from castling ", "early [lichess-c20].",
        )

        val chunks = tokenChunks(deltas)

        assertTrue(chunks.size > 1, "composer collapsed ${deltas.size} deltas into ${chunks.size} chunk(s)")
        assertEquals(deltas.joinToString("").trim(), chunks.joinToString("").trim())
    }

    @Test
    fun `deliberation is stripped without holding the answer back to the end`() {
        // The specific suspicion in the plan: the <think> state machine buffers until it can be
        // sure, and in doing so releases everything at once. It does not — text after the closing
        // tag flows out delta by delta.
        val deltas = listOf(
            "<think>", "The user asks ", "about the opening.", "</think>",
            "Both king pawns ", "contest the center ", "with development ", "[lichess-c20]. ",
            "Castling early ", "keeps the king safe ", "[lichess-c20].",
        )

        val chunks = tokenChunks(deltas)

        assertTrue(chunks.size > 1, "think-stripper collapsed the stream into ${chunks.size} chunk(s)")
        assertTrue(chunks.none { it.contains("user asks") }, "deliberation leaked into the answer")
    }

    @Test
    fun `a fenced response still streams in pieces`() {
        val deltas = listOf(
            "```", "\n", "Both king pawns ", "contest the center ", "for development ",
            "[lichess-c20]. ", "Castling follows ", "for king safety ", "[lichess-c20].", "```",
        )

        val chunks = tokenChunks(deltas)

        assertTrue(chunks.size > 1, "code-fence stripper collapsed the stream into ${chunks.size} chunk(s)")
        assertTrue(chunks.none { it.contains("```") }, "the fence reached the client")
    }

    @Test
    fun `a provider that answers with one whole completion produces exactly one chunk`() {
        // Not a bug in this class — it is the shape the live deployment is in, pinned so the
        // distinction stays legible: one chunk here means the provider batched, never that the
        // composer did.
        val chunks = tokenChunks(
            listOf("Both king pawns contest the center for development [lichess-c20]. Castling keeps the king safe [lichess-c20]."),
        )

        assertEquals(1, chunks.size)
    }
}
