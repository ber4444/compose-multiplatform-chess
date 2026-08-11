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

    /** As [detect], with the positional inputs the four tactical detections don't need. */
    private fun detectMove(
        fenBefore: String,
        fenAfter: String,
        side: Set,
        from: Pair<Int, Int>,
        to: Pair<Int, Int>,
        promoted: Boolean = false,
        previousTo: Pair<Int, Int>? = null,
    ) = MotifDetector.detectMotifs(
        stateBefore = FenConverter.fenToGameState(fenBefore),
        stateAfter = FenConverter.fenToGameState(fenAfter),
        movingSide = side,
        toSquare = to,
        fromSquare = from,
        promoted = promoted,
        previousToSquare = previousTo,
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
    fun `a quiet move detects no tactic`() {
        // The negative case matters as much as the positive. A detector that fires on every move
        // makes every headline say "forks", and the signal is gone.
        //
        // Was `a quiet move detects nothing`. It no longer detects *nothing*, and that is the point
        // of the positional vocabulary: Nd1-c3 covers d5 and e4, so `center-control` is a true
        // statement about it and the coach can now say something instead of falling through to
        // "The position stays roughly balanced." What must stay absent is a claimed **tactic**.
        val motifs = detect(
            "k7/8/8/8/8/8/8/3N3K w - - 0 1",
            "k7/8/8/8/8/2N5/8/7K b - - 0 1",
            Set.WHITE,
            Pair(5, 2),
        )
        val tactics = listOf(
            MotifDetector.FORK, MotifDetector.PIN,
            MotifDetector.SKEWER, MotifDetector.DISCOVERED_ATTACK,
        )
        assertTrue(motifs.none { it in tactics }, "claimed a tactic on a quiet move: $motifs")
        assertTrue(MotifDetector.CENTER_CONTROL in motifs, "Nc3 covers d5 and e4: $motifs")
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

    // --- the guard that was missing -------------------------------------------------------------

    /**
     * The reverse of `motif vocabulary is understood by DeterministicCoach`, and the direction that
     * was never pinned.
     *
     * That test asks "is every motif the detector emits understood?" — detector to coach. Nothing
     * asked "is every motif the coach handles actually emitted?", so eleven explanation branches and
     * seven headline phrases sat in `DeterministicCoach` unreachable while `ALL_MOTIFS` held four
     * entries. The coach looked rich and behaved thin: on any move that was not a fork, pin, skewer
     * or discovered attack, every branch missed and the line fell through to "The position stays
     * roughly balanced." — which is exactly what shipped.
     */
    @Test
    fun `every motif DeterministicCoach handles can actually be emitted`() {
        val handled = DeterministicCoach.handledMotifs()
        val orphans = handled - MotifDetector.ALL_MOTIFS.toSet()
        assertTrue(
            orphans.isEmpty(),
            "DeterministicCoach has sentences for motifs nothing emits, so they are dead code: $orphans",
        )
    }

    // --- the positional vocabulary --------------------------------------------------------------

    @Test
    fun `detects a developing move`() {
        // Ng1-f3: a minor piece leaving the back rank, which is most of what an opening is.
        val motifs = detectMove(
            "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1",
            "rnbqkbnr/pppppppp/8/8/8/5N2/PPPPPPPP/RNBQKB1R b KQkq - 1 1",
            Set.WHITE,
            from = Pair(7, 6),
            to = Pair(5, 5),
        )
        assertTrue(MotifDetector.DEVELOPS in motifs, "expected develops, got $motifs")
    }

    @Test
    fun `detects castling and prefers it over the generic king-safety line`() {
        val motifs = detectMove(
            "rnbqk2r/pppp1ppp/5n2/2b1p3/2B1P3/5N2/PPPP1PPP/RNBQK2R w KQkq - 4 4",
            "rnbqk2r/pppp1ppp/5n2/2b1p3/2B1P3/5N2/PPPP1PPP/RNBQ1RK1 b kq - 5 4",
            Set.WHITE,
            from = Pair(7, 4),
            to = Pair(7, 6),
        )
        assertTrue(MotifDetector.CASTLE_KINGSIDE in motifs, "expected castle-kingside, got $motifs")
        assertTrue(
            MotifDetector.KING_SAFETY !in motifs,
            "castling has its own sentence and DeterministicCoach reaches king-safety first: $motifs",
        )
    }

    @Test
    fun `a recapture is not reported as winning material`() {
        // DeterministicCoach reaches material-swing before recapture, so emitting both would say
        // "It wins material." about restoring balance.
        val motifs = detectMove(
            "k7/8/8/8/8/8/8/K2Rr3 w - - 0 1",
            // Rd1 takes on e1, so the rook is on e1 afterwards — K3R3, not K2R4.
            "k7/8/8/8/8/8/8/K3R3 b - - 0 1",
            Set.WHITE,
            from = Pair(7, 3),
            to = Pair(7, 4),
            previousTo = Pair(7, 4),
        )
        assertTrue(MotifDetector.RECAPTURE in motifs, "expected recapture, got $motifs")
        assertTrue(MotifDetector.MATERIAL_SWING !in motifs, "recapture must not claim material: $motifs")
    }

    @Test
    fun `a general threat is suppressed when a specific tactic applies`() {
        // "It creates a concrete threat." would otherwise win over "It attacks two pieces at once."
        val motifs = detect(
            "k7/8/8/8/8/8/8/3N3K w - - 0 1",
            "8/8/8/8/2k3r1/4N3/8/7K b - - 0 1",
            Set.WHITE,
            Pair(5, 4),
        )
        assertTrue(MotifDetector.FORK in motifs)
        assertTrue(MotifDetector.THREATENS !in motifs, "fork must outrank threatens: $motifs")
    }

    @Test
    fun `checkmate outranks everything in the returned order`() {
        // The headline takes the first motif it has a phrase for, so order is the priority.
        val motifs = detectMove(
            "k7/7R/8/8/8/8/8/K6R w - - 0 1",
            "k6R/7R/8/8/8/8/8/K7 b - - 1 1",
            Set.WHITE,
            from = Pair(7, 7),
            to = Pair(0, 7),
        )
        assertEquals(MotifDetector.CHECKMATE, motifs.firstOrNull(), "got $motifs")
    }
}
