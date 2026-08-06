package com.example.coachserver

import com.example.coachapi.OpeningExplainRequest
import com.example.coachapi.Passage
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OpeningServiceTest {
    @Test
    fun `query uses only the first twelve SAN moves`() {
        val moves = (1..16).map { "M$it" }

        val query = OpeningQueryBuilder.build(
            OpeningExplainRequest(fen = "fen", movesSan = moves, eco = "C20"),
        )

        assertTrue(query.contains("M1"))
        assertTrue(query.endsWith("M12"))
        assertEquals(false, query.contains("M13"))
    }

    @Test
    fun `eco is kept out of the embedding text and passed as a structured filter instead`() {
        // One ECO token in a 384-dimension vector is outvoted, and measurably harmful: a live
        // request carrying eco = "C00" retrieved four E00/E06 Catalan passages. ECO now reaches
        // retrieval as an argument, so it must not also be blended into the query prose.
        val query = OpeningQueryBuilder.build(
            OpeningExplainRequest(fen = "fen", movesSan = listOf("e4", "e6"), eco = "C00"),
        )

        assertEquals(false, query.contains("C00"))
        assertEquals(false, query.contains("ECO"))
    }

    @Test
    fun `explain forwards the moves and the resolved eco to the composer`() {
        var seenMoves: List<String>? = null
        var seenEco: String? = null
        val service = OpeningService(
            ServerDependencies(
                embedder = { FloatArray(384) },
                passageRepository = object : PassageRepository {
                    override fun retrieve(
                        embedding: FloatArray,
                        limit: Int,
                        movesSan: List<String>,
                        eco: String?,
                    ): RetrievalResult {
                        seenMoves = movesSan
                        return RetrievalResult(
                            listOf(Passage("b20", "B20 — Sicilian Defense", "Black plays c5.")),
                            resolvedEco = "B20",
                        )
                    }

                    override fun upsert(
                        passage: Passage,
                        embedding: FloatArray,
                        eco: String?,
                        moves: String?,
                    ) = Unit
                },
                composer = { request, passages ->
                    seenEco = request.eco
                    TemplateComposer().compose(request, passages)
                },
            ),
        )

        runBlocking {
            service.explain(
                OpeningExplainRequest(
                    fen = "rnbqkbnr/pp1ppppp/8/2p5/4P3/8/PPPP1PPP/RNBQKBNR w KQkq c6 0 2",
                    movesSan = listOf("e4", "c5"),
                    eco = null,
                ),
            )
        }

        assertEquals(listOf("e4", "c5"), seenMoves)
        // Both clients send eco = null, so "B20" here can only have come from the server's own
        // move-prefix lookup. If this regresses, the LLM prompt's ECO line reads "unknown" forever.
        assertEquals("B20", seenEco)
    }

    @Test
    fun `template composer emits grounded deterministic prose`() {
        val passages = listOf(
            Passage("c20", "King's Pawn Game", "The central pawn move opens lines for the queen and bishop."),
            Passage("development", "Development", "Early development brings minor pieces toward useful central squares."),
        )
        val request = OpeningExplainRequest(fen = "fen", movesSan = listOf("e4", "e5"), eco = "C20")

        val first = TemplateComposer().compose(request, passages)
        val second = TemplateComposer().compose(request, passages)

        assertEquals(first, second)
        assertEquals("template-v1", first.composerId)
        assertTrue(first.text.contains("King's Pawn Game"))
        assertTrue(first.text.contains("Development"))
    }

    @Test
    fun `invalid llm prose falls back to the template`() {
        val passages = listOf(
            Passage("c20", "King's Pawn Game", "The center is contested by both king pawns."),
        )
        val request = OpeningExplainRequest(fen = "fen", movesSan = listOf("e4", "e5"), eco = "C20")
        val composer = LlmComposer(
            client = LlmClient { _, _, _ -> LlmCompletion("I think Stockfish probably depth 30 likes it.") },
            fallback = TemplateComposer(),
        )

        val result = composer.compose(request, passages)

        assertEquals("template-v1", result.composerId)
        assertTrue(result.text.contains("King's Pawn Game"))
    }

    @Test
    fun `unsupported llm certainty falls back even when it cites a source`() {
        val passages = listOf(
            Passage("c20", "King's Pawn Game", "Both king pawns contest the center and open development lines."),
        )
        val composer = LlmComposer(
            client = LlmClient { _, _, _ ->
                LlmCompletion(
                    "The king pawns contest the center [c20]. " +
                        "This opening forces checkmate through development [c20].",
                )
            },
            fallback = TemplateComposer(),
        )

        val result = composer.compose(
            OpeningExplainRequest(fen = "fen", movesSan = listOf("e4", "e5"), eco = "C20"),
            passages,
        )

        assertEquals("template-v1", result.composerId)
    }

    @Test
    fun `provider budget rejects a request above the configured cost ceiling`() {
        var calls = 0
        val composer = LlmComposer(
            client = LlmClient { _, _, _ -> calls++; LlmCompletion("unused") },
            fallback = TemplateComposer(),
            budget = ProviderCostBudget(
                maxUsdCents = 0.2,
                inputUsdPerMillionTokens = 1_000.0,
                outputUsdPerMillionTokens = 1_000.0,
            ),
        )

        val result = composer.compose(
            OpeningExplainRequest(fen = "fen", movesSan = listOf("e4"), eco = "C20"),
            listOf(Passage("c20", "Opening", "The center supports development.")),
        )

        assertEquals(0, calls)
        assertEquals("template-v1", result.composerId)
    }

    @Test
    fun openingDiagnosticsNameRetrievalAndDeterministicOutcome() = runBlocking {
        val passage = Passage("lichess-c20", "Title", "Text")
        val request = OpeningExplainRequest(fen = "fen", movesSan = listOf("e4", "e5"), eco = "C20")
        val service = OpeningService(
            ServerDependencies(
                embedder = { FloatArray(384) },
                passageRepository = object : PassageRepository {
                    override fun retrieve(
                        embedding: FloatArray, limit: Int, movesSan: List<String>, eco: String?
                    ) = RetrievalResult(listOf(passage), eco)
                    override fun upsert(
                        passage: Passage, embedding: FloatArray, eco: String?, moves: String?
                    ) = Unit
                },
                composer = TemplateComposer(),
            )
        )
        val body = service.explain(request)
        assertEquals(listOf("lichess-c20"), body.diagnostics!!.retrievedPassageIds)
        assertEquals("template-v1", body.diagnostics!!.composerId)
        assertEquals("completed", body.diagnostics!!.finishReason)
    }
}
