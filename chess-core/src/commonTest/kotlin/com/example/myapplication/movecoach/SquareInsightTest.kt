package com.example.myapplication.movecoach

import com.example.myapplication.FenConverter
import com.example.myapplication.Set
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SquareInsightTest {

    private val start = FenConverter.fenToGameState(
        "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1",
    )

    private fun square(algebraic: String): Pair<Int, Int> =
        ('8' - algebraic[1]) to (algebraic[0] - 'a')

    @Test
    fun `names the pawn covering an empty square`() {
        // d5 is empty and the black e6 pawn covers it. Move generation does not report this — a
        // pawn's diagonals are only moves when something is standing on them — so a reading built
        // on legal moves calls d5 free. That is the failure this analyzer exists to avoid.
        val state = FenConverter.fenToGameState("4k3/8/4p3/8/8/8/8/4K3 w - - 0 1")
        val text = SquareInsight.buildExplanation(state, square("d5"), Set.WHITE)
        assertTrue("only a pawn belongs there" in text, text)
        assertTrue("Black covers it once" in text, text)
    }

    @Test
    fun `f3 from the start is quiet and reachable`() {
        val text = SquareInsight.buildExplanation(start, square("f3"), Set.WHITE)
        assertTrue("f3 is empty." in text, text)
        assertTrue("knight" in text, text)
        assertEquals("f3 — yours to take", SquareInsight.buildHeadline(start, square("f3"), Set.WHITE))
    }

    @Test
    fun `reports the occupant from the viewer's side`() {
        val e4 = square("e4")
        val afterE4 = FenConverter.fenToGameState(
            "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq e3 0 1",
        )
        assertTrue(
            SquareInsight.buildExplanation(afterE4, e4, Set.WHITE).startsWith("Your pawn stands on e4."),
            SquareInsight.buildExplanation(afterE4, e4, Set.WHITE),
        )
        assertTrue(
            SquareInsight.buildExplanation(afterE4, e4, Set.BLACK).startsWith("White's pawn stands on e4."),
            SquareInsight.buildExplanation(afterE4, e4, Set.BLACK),
        )
    }

    @Test
    fun `warns when the defender count loses`() {
        // Black's d7 knight and e7 queen both hit e5; White only has the d4 pawn covering it.
        val state = FenConverter.fenToGameState("4k3/3nq3/8/8/3P4/8/8/4K3 w - - 0 1")
        val text = SquareInsight.buildExplanation(state, square("e5"), Set.WHITE)
        assertTrue("would drop" in text, text)
        assertEquals("e5 — Black holds it", SquareInsight.buildHeadline(state, square("e5"), Set.WHITE))
    }

    @Test
    fun `stays within the coach's character budget`() {
        for (row in 0..7) {
            for (col in 0..7) {
                val text = SquareInsight.buildExplanation(start, row to col, Set.WHITE)
                assertTrue(text.length <= 300, "${text.length}: $text")
            }
        }
    }
}
