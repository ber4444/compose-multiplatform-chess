package com.example.myapplication

import com.example.myapplication.movecoach.DeterministicCoach
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Board coordinates are `Pair(row, col)`, 0-indexed from the top-left exactly as FEN is written:
 * rank 8 is row 0, rank 1 is row 7, file a is col 0. So e3 is `Pair(5, 4)`.
 */
class MotifDetectorTest {

    private fun detect(fenBefore: String, fenAfter: String, side: Set, to: Pair<Int, Int>) =
        MotifDetector.detectMotifs(
            FenConverter.fenToGameState(fenBefore),
            FenConverter.fenToGameState(fenAfter),
            side,
            to,
        )

    // --- the contract that was broken ---------------------------------------------------------

    /**
     * The regression this file exists for. `MotifDetector` emitted "Fork"/"Pin"/"Skewer"/"Discovered
     * Attack" while `DeterministicCoach` matched lowercase-hyphenated names, so the intersection was
     * empty: motifs were detected, persisted, and then silently ignored by the only code that reads
     * them. Every motif branch in the coach was unreachable, and nothing caught it — because the
     * coach's own tests supply hand-written motif strings in the coach's vocabulary, confirming it
     * agrees with itself rather than with its actual producer.
     */
    @Test
    fun `motif vocabulary is understood by DeterministicCoach`() {
        MotifDetector.ALL_MOTIFS.forEach { motif ->
            val record = MoveRecord(
                uci = "g1f3", san = "Nf3", fenAfter = "",
                assessment = MoveAssessment(0, 0, 0, 0, MoveClass.GOOD, listOf(motif)),
            )
            assertFalse(
                DeterministicCoach.buildHeadline(record).endsWith("— Nf3"),
                "'$motif' fell through to the generic headline; DeterministicCoach doesn't handle it",
            )
            val explanation = DeterministicCoach.buildExplanation(record)
            assertFalse(
                "roughly balanced" in explanation || "measurably better" in explanation,
                "'$motif' fell through to the cp-based explanation; DeterministicCoach doesn't handle it",
            )
        }
    }

    @Test
    fun `motif names are lowercase and hyphenated`() {
        MotifDetector.ALL_MOTIFS.forEach {
            assertEquals(it.lowercase(), it, "motif names must be lowercase")
            assertFalse(" " in it, "motif names must use hyphens, not spaces: '$it'")
        }
    }

    // --- detection ----------------------------------------------------------------------------

    @Test
    fun `detects a knight fork`() {
        // Knight to e3 attacks the king on c4 and the rook on g4.
        val motifs = detect(
            "k7/8/8/8/8/8/8/3N3K w - - 0 1",
            "8/8/8/8/2k3r1/4N3/8/7K b - - 0 1",
            Set.WHITE,
            Pair(5, 4),
        )
        assertTrue(MotifDetector.FORK in motifs, "expected fork, got $motifs")
    }

    @Test
    fun `a quiet move detects nothing`() {
        // The negative case matters as much as the positive. A detector that fires on every move
        // makes every headline say "forks", and the signal is gone.
        val motifs = detect(
            "k7/8/8/8/8/8/8/3N3K w - - 0 1",
            "k7/8/8/8/8/2N5/8/7K b - - 0 1",
            Set.WHITE,
            Pair(5, 2),
        )
        assertTrue(motifs.isEmpty(), "expected no motifs on a quiet move, got $motifs")
    }

    @Test
    fun `returns empty when the moved piece is not on the target square`() {
        // Guards the `indexOf(toSquare) == -1` early return. Callers derive `toSquare` separately
        // from the board, so a mismatch is a caller bug — it must degrade to "no motifs" rather than
        // throw or read whichever piece happens to be there.
        val motifs = detect(
            "k7/8/8/8/8/8/8/3N3K w - - 0 1",
            "k7/8/8/8/8/2N5/8/7K b - - 0 1",
            Set.WHITE,
            Pair(0, 0),
        )
        assertTrue(motifs.isEmpty())
    }

    @Test
    fun `detects motifs for black as well as white`() {
        // The side branching picks the ally/enemy lists; a copy-paste error there leaves the
        // detector silently blind for one colour — which now matters, since the player can be either.
        val motifs = detect(
            "3n3k/8/8/8/8/8/8/K7 b - - 0 1",
            "7k/8/8/8/2K3R1/4n3/8/8 w - - 0 1",
            Set.BLACK,
            Pair(5, 4),
        )
        assertTrue(MotifDetector.FORK in motifs, "expected fork for Black, got $motifs")
    }

    @Test
    fun `detection is deterministic`() {
        // "Code detects, the model narrates" is only worth claiming if the code is stable.
        val runs = List(5) {
            detect(
                "k7/8/8/8/8/8/8/3N3K w - - 0 1",
                "8/8/8/8/2k3r1/4N3/8/7K b - - 0 1",
                Set.WHITE,
                Pair(5, 4),
            )
        }
        assertTrue(runs.all { it == runs.first() }, "detector produced varying output: $runs")
    }
}
