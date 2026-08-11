package com.example.myapplication.movecoach

import com.example.myapplication.FenConverter
import com.example.myapplication.GameUiState
import com.example.myapplication.MoveAssessment
import com.example.myapplication.MoveClass
import com.example.myapplication.MoveRecord
import com.example.myapplication.SanConverter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The line the panel was missing.
 *
 * Everything else `DeterministicCoach` says describes the move the user just made — which they can
 * see, and whose verdict the board now colours — so on a quiet position it degraded to "The position
 * stays roughly balanced.": true, and worth nothing. What the engine would have played instead is
 * the one thing the user cannot work out by looking, and it was already being computed and thrown
 * away: `runIdleAnalysis` calls `getBestMove` to obtain `cpBest` and nothing read the move.
 */
class CounterfactualTest {

    private fun record(
        san: String = "e3",
        uci: String = "e2e3",
        moveClass: MoveClass = MoveClass.INACCURACY,
        bestMoveSan: String? = "Nf3",
    ) = MoveRecord(
        uci = uci,
        san = san,
        fenAfter = "",
        assessment = MoveAssessment(
            cpBefore = 0,
            cpPlayed = -80,
            cpBest = 0,
            cpLoss = 80,
            moveClass = moveClass,
            motifs = emptyList(),
            bestMoveUci = "g1f3",
            bestMoveSan = bestMoveSan,
        ),
    )

    @Test
    fun `names the move that would have been better`() {
        val explanation = DeterministicCoach.buildExplanation(record())
        assertTrue("Nf3 was stronger." in explanation, explanation)
    }

    @Test
    fun `the wording escalates with the class`() {
        fun tail(moveClass: MoveClass) = DeterministicCoach.buildExplanation(record(moveClass = moveClass))
        assertTrue("a shade sharper" in tail(MoveClass.GOOD), tail(MoveClass.GOOD))
        assertTrue("was stronger" in tail(MoveClass.INACCURACY), tail(MoveClass.INACCURACY))
        assertTrue("much stronger" in tail(MoveClass.MISTAKE), tail(MoveClass.MISTAKE))
        assertTrue("far better" in tail(MoveClass.BLUNDER), tail(MoveClass.BLUNDER))
    }

    @Test
    fun `a best move is not told it could have been better`() {
        // Within 10cp the gap is inside the engine's own noise at these movetimes. Naming an
        // improvement the user cannot feel is how a coach loses their trust.
        for (moveClass in listOf(MoveClass.BEST, MoveClass.BOOK)) {
            val explanation = DeterministicCoach.buildExplanation(record(moveClass = moveClass))
            // The claim is "no alternative is named", not "the word better never appears" — the
            // evaluation sentence legitimately says "Black is measurably better after this move".
            assertTrue("Nf3" !in explanation, "$moveClass named an alternative: $explanation")
        }
    }

    @Test
    fun `no alternative recorded means no claim about one`() {
        val explanation = DeterministicCoach.buildExplanation(record(bestMoveSan = null))
        assertTrue("stronger" !in explanation, explanation)
        assertTrue(explanation.isNotBlank(), "the reason must survive on its own")
    }

    @Test
    fun `the counterfactual still fits the panel budget`() {
        val explanation = DeterministicCoach.buildExplanation(
            record(moveClass = MoveClass.BLUNDER, bestMoveSan = "Q".repeat(400)),
        )
        assertTrue(explanation.length <= 300, "was ${explanation.length}")
    }

    // --- SanConverter.sanForUci: the alternative has to be named correctly ----------------------

    private val start = GameUiState()

    @Test
    fun `resolves a quiet move to SAN`() {
        assertEquals("Nf3", SanConverter.sanForUci(start, "g1f3"))
        assertEquals("e4", SanConverter.sanForUci(start, "e2e4"))
    }

    @Test
    fun `marks a check`() {
        // Scholar's-mate shape: Qxf7 is mate, and a coaching line that says "Qf7" instead of "Qxf7#"
        // is materially less useful.
        val state = FenConverter.fenToGameState(
            "r1bqkbnr/pppp1ppp/2n5/4p3/2B1P3/5Q2/PPPP1PPP/RNB1K1NR w KQkq - 4 4",
        )
        val san = SanConverter.sanForUci(state, "f3f7")
        assertEquals("Qxf7#", san)
    }

    @Test
    fun `rejects a move the side to move cannot play`() {
        assertNull(SanConverter.sanForUci(start, "e7e5"), "black's move, white to move")
        assertNull(SanConverter.sanForUci(start, "a3a4"), "no piece on a3")
        assertNull(SanConverter.sanForUci(start, "zz"), "not a UCI move")
    }
}
