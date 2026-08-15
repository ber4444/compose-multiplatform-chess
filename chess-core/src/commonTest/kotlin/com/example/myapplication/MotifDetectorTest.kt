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
                uci = "g1f3", san = "Nf3", fenAfter = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR b KQkq - 0 1",
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

    /**
     * The develops detail is the coach's most common sentence, and it used to read "It brings the
     * knight out to f3." — the move the player had just watched, with no reason attached. Scored
     * against the golden set on 2026-08-15 it was 11 of the 11 deterministic lines that carried no
     * explanatory concept at all. Pinned as a property rather than a string so a rewording stays
     * free, as long as it still says something.
     */
    @Test
    fun `the develops detail explains rather than restating the move`() {
        val details = MotifDetector.detectDetailed(
            stateBefore = FenConverter.fenToGameState("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"),
            stateAfter = FenConverter.fenToGameState("rnbqkbnr/pppppppp/8/8/8/5N2/PPPPPPPP/RNBQKB1R b KQkq - 1 1"),
            movingSide = Set.WHITE,
            toSquare = Pair(5, 5),
            fromSquare = Pair(7, 6),
            promoted = false,
            previousToSquare = null,
        ).details[MotifDetector.DEVELOPS]

        assertTrue(details != null, "expected a develops detail")
        assertTrue(
            details.contains("develop", ignoreCase = true) || details.contains("into play", ignoreCase = true),
            "develops detail must name what development buys, was: $details",
        )
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

    // --- naming the pieces, and not naming ones that aren't there -------------------------------

    @Test
    fun `a real pin names both ends of the line`() {
        // Bb5 with d7 empty: the knight on c6 genuinely cannot move without exposing the king.
        val detected = MotifDetector.detectDetailed(
            FenConverter.fenToGameState("r1bqkbnr/ppp2ppp/2n5/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R w KQkq - 0 4"),
            FenConverter.fenToGameState("r1bqkbnr/ppp2ppp/2n5/1B2p3/4P3/5N2/PPPP1PPP/RNBQK2R b KQkq - 1 4"),
            Set.WHITE,
            toSquare = Pair(3, 1),
            fromSquare = Pair(7, 5),
        )
        assertTrue(MotifDetector.PIN in detected.motifs, "expected a pin, got ${detected.motifs}")
        val detail = detected.details[MotifDetector.PIN]
        assertTrue(detail != null && "c6" in detail && "e8" in detail, "detail was $detail")
    }

    @Test
    fun `a piece shielded only by a pawn is neither pinned nor skewered`() {
        // The Ruy Lopez proper: Bb5 lines up on the c6 knight with the *pawn* on d7 behind it. Value
        // order alone called that a skewer, and once details landed the coach said "Your bishop on b5
        // skewers the knight on c6 against the pawn on d7." — fluent, specific, and false. A wrong
        // sentence naming real squares is worse than a vague true one.
        val detected = MotifDetector.detectDetailed(
            FenConverter.fenToGameState("r1bqkbnr/pppp1ppp/2n5/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R w KQkq - 3 3"),
            FenConverter.fenToGameState("r1bqkbnr/pppp1ppp/2n5/1B2p3/4P3/5N2/PPPP1PPP/RNBQK2R b KQkq - 4 3"),
            Set.WHITE,
            toSquare = Pair(3, 1),
            fromSquare = Pair(7, 5),
        )
        assertTrue(MotifDetector.PIN !in detected.motifs, "claimed a pin: ${detected.motifs}")
        assertTrue(MotifDetector.SKEWER !in detected.motifs, "claimed a skewer: ${detected.motifs}")
    }

    @Test
    fun `a fork names the pieces it hits`() {
        val detected = MotifDetector.detectDetailed(
            FenConverter.fenToGameState("k7/8/8/8/8/8/8/3N3K w - - 0 1"),
            FenConverter.fenToGameState("8/8/8/8/2k3r1/4N3/8/7K b - - 0 1"),
            Set.WHITE,
            toSquare = Pair(5, 4),
            fromSquare = Pair(7, 3),
        )
        val detail = detected.details[MotifDetector.FORK]
        assertTrue(detail != null && "g4" in detail && "c4" in detail, "detail was $detail")
    }

    @Test
    fun `details never describe a motif that was not detected`() {
        // The renderer re-runs the ray scan, so it must apply the same predicates the detector did.
        val detected = MotifDetector.detectDetailed(
            FenConverter.fenToGameState("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"),
            FenConverter.fenToGameState("rnbqkbnr/pppppppp/8/8/8/5N2/PPPPPPPP/RNBQKB1R b KQkq - 1 1"),
            Set.WHITE,
            toSquare = Pair(5, 5),
            fromSquare = Pair(7, 6),
        )
        assertTrue(
            detected.details.keys.all { it in detected.motifs },
            "described motifs that were not detected: ${detected.details.keys - detected.motifs.toSet()}",
        )
    }

    // Rc1 down an open c-file at a bishop on c8. The two cases differ by **one piece** — the rook on
    // a8 that defends it — so the pair isolates the rule rather than the geometry.
    private val defendedBishop = "r1b1k1nr/p2ppp1p/8/8/8/8/P2PPP1P/R3K1NR w KQkq - 0 1"
    private val undefendedBishop = "2b1k1nr/p2ppp1p/8/8/8/8/P2PPP1P/R3K1NR w KQk - 0 1"

    private fun rookToC1(before: String) = MotifDetector.detectDetailed(
        FenConverter.fenToGameState(before),
        FenConverter.fenToGameState(before.replace("/R3K1NR w", "/2R1K1NR w")),
        Set.WHITE,
        toSquare = Pair(7, 2),
        fromSquare = Pair(7, 0),
    )

    @Test
    fun `attacking a defended piece of lower value is not a threat`() {
        // Reported on-device: "Your rook on c1 attacks the bishop on c8." True, and acting on it
        // loses the exchange — a rook does not win a defended bishop. A claim the player pays for.
        val detected = rookToC1(defendedBishop)
        assertTrue(MotifDetector.THREATENS !in detected.motifs, "claimed a threat: ${detected.motifs}")
    }

    @Test
    fun `attacking an undefended piece is a threat and names it`() {
        // Same position minus the a8 rook, so the bishop really is winnable.
        val detected = rookToC1(undefendedBishop)
        assertTrue(MotifDetector.THREATENS in detected.motifs, "expected a threat, got ${detected.motifs}")
        val detail = detected.details[MotifDetector.THREATENS]
        assertTrue(detail != null && "c8" in detail, "detail was $detail")
    }
}
