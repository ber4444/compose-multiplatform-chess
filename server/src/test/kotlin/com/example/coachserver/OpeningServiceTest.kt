package com.example.coachserver

import com.example.coachapi.OpeningExplainRequest
import com.example.coachapi.Passage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OpeningServiceTest {
    @Test
    fun `query uses eco and only the first twelve SAN moves`() {
        val moves = (1..16).map { "M$it" }

        val query = OpeningQueryBuilder.build(
            OpeningExplainRequest(fen = "fen", movesSan = moves, eco = "C20"),
        )

        assertTrue(query.startsWith("ECO C20"))
        assertTrue(query.contains("M1"))
        assertTrue(query.endsWith("M12"))
        assertEquals(false, query.contains("M13"))
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
            client = LlmClient { _, _, _ -> "I think Stockfish probably depth 30 likes it." },
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
                "The king pawns contest the center [c20]. This opening forces checkmate through development [c20]."
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
            client = LlmClient { _, _, _ -> calls++; "unused" },
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
}
