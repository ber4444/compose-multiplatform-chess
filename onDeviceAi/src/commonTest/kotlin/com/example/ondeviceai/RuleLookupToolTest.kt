package com.example.ondeviceai

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RuleLookupToolTest {

    @Test
    fun `fixed rules queries retrieve the expected bundled passage`() = runTest {
        val lookup = BundledRuleLookupTool()
        val cases = listOf(
            "Can a king castle through check?" to "castling-check",
            "What happens after three repetitions?" to "draw-repetition",
            "How does the fifty move draw work?" to "draw-fifty-move",
            "When can a pawn capture en passant?" to "en-passant",
            "What pieces can a pawn promote to?" to "promotion",
            "What is stalemate?" to "stalemate",
            "Can I move into check?" to "king-in-check",
            "When is checkmate?" to "checkmate",
            "How does a rook move?" to "rook-movement",
            "How does a knight move?" to "knight-movement",
            "What is insufficient material?" to "draw-dead-position",
            "Can I touch a piece and then choose another?" to "touch-move",
            "game ends in draw if only two kings remain" to "draw-dead-position",
            "Game is a draw when 2 kings remain?" to "draw-dead-position",
            "Game is a draw when only kings remain?" to "draw-dead-position",
        )

        cases.forEach { (question, expectedId) ->
            val results = lookup.lookup(question)
            assertTrue(results.isNotEmpty(), question)
            assertEquals(expectedId, results.first().id, question)
        }
    }

    @Test
    fun `lookup returns a bounded ranked result set`() = runTest {
        val results = BundledRuleLookupTool(maxResults = 3).lookup("draw king pawn repetition")

        assertTrue(results.isNotEmpty())
        assertTrue(results.size <= 3)
        assertEquals(results.map { it.id }.distinct().size, results.size)
    }
}
