package com.example.myapplication

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `MoveAssessor` is pure arithmetic — six classification thresholds and a `max(0, …)` clamp — and
 * RAG-2's turning-point ranking is `cpLoss` sorted descending. If a threshold moves, the coach
 * silently starts calling blunders inaccuracies and the summary ranks the wrong plies. Cheap to
 * pin, so pin it.
 */
class MoveAssessorTest {

    private fun lossOf(cpLoss: Int) =
        MoveAssessor.assessMove(mover = Set.WHITE, cpBefore = 0, cpPlayed = -cpLoss, cpBest = 0)

    // --- cpLoss ------------------------------------------------------------------------------

    @Test
    fun `cpLoss is best minus played`() {
        val a = MoveAssessor.assessMove(mover = Set.WHITE, cpBefore = 50, cpPlayed = 20, cpBest = 120)
        assertEquals(100, a.cpLoss)
    }

    @Test
    fun `cpLoss clamps at zero when the played move beats the search's best`() {
        // Not hypothetical: cpPlayed and cpBest come from separate searches, so a deeper or luckier
        // line can score the played move above the "best" one. Without the clamp that is a negative
        // loss, which would sort to the top of RAG-2's turning-point ranking — the single worst
        // place for an artefact to land.
        val a = MoveAssessor.assessMove(mover = Set.WHITE, cpBefore = 0, cpPlayed = 80, cpBest = 30)
        assertEquals(0, a.cpLoss)
        assertEquals(MoveClass.BEST, a.moveClass)
    }

    @Test
    fun `cpLoss is unaffected by cpBefore`() {
        // cpBefore is carried for the narrative ("you were winning"), not the classification.
        val losing = MoveAssessor.assessMove(mover = Set.WHITE, cpBefore = -900, cpPlayed = 0, cpBest = 50)
        val winning = MoveAssessor.assessMove(mover = Set.WHITE, cpBefore = 900, cpPlayed = 0, cpBest = 50)
        assertEquals(losing.cpLoss, winning.cpLoss)
        assertEquals(losing.moveClass, winning.moveClass)
    }

    // --- the mover ---------------------------------------------------------------------------
    //
    // Every score is White's perspective. A Black move that costs Black *raises* it, so the loss
    // has to be read the other way round for Black, or every Black blunder clamps to a loss of 0.

    @Test
    fun `a black move that hands white the advantage is a loss for black`() {
        val a = MoveAssessor.assessMove(mover = Set.BLACK, cpBefore = 30, cpPlayed = 450, cpBest = 30)
        assertEquals(420, a.cpLoss)
        assertEquals(MoveClass.BLUNDER, a.moveClass)
    }

    @Test
    fun `a black move that matches black's best loses nothing`() {
        val a = MoveAssessor.assessMove(mover = Set.BLACK, cpBefore = -50, cpPlayed = -50, cpBest = -50)
        assertEquals(0, a.cpLoss)
        assertEquals(MoveClass.BEST, a.moveClass)
    }

    @Test
    fun `the two sides grade mirrored positions identically`() {
        for (loss in listOf(0, 11, 31, 61, 101, 301, 100_000)) {
            val white = MoveAssessor.assessMove(mover = Set.WHITE, cpBefore = 40, cpPlayed = 40 - loss, cpBest = 40)
            val black = MoveAssessor.assessMove(mover = Set.BLACK, cpBefore = -40, cpPlayed = -40 + loss, cpBest = -40)
            assertEquals(white.cpLoss, black.cpLoss, "loss $loss")
            assertEquals(white.moveClass, black.moveClass, "loss $loss")
        }
    }

    @Test
    fun `black's clamp holds when the played move out-scores the search's best`() {
        val a = MoveAssessor.assessMove(mover = Set.BLACK, cpBefore = 0, cpPlayed = -80, cpBest = -30)
        assertEquals(0, a.cpLoss)
    }

    @Suppress("DEPRECATION")
    @Test
    fun `the deprecated overload still grades as white`() {
        // Kept for the published API; it must not have quietly changed meaning.
        val old = MoveAssessor.assessMove(cpBefore = 50, cpPlayed = 20, cpBest = 120)
        assertEquals(MoveAssessor.assessMove(mover = Set.WHITE, cpBefore = 50, cpPlayed = 20, cpBest = 120), old)
    }

    // --- regrade: records saved before the mover was known ------------------------------------

    /** As the old assessMove stored a Black move: graded as White's, so the 420 cp loss reads 0. */
    private fun staleBlackBlunder() = MoveRecord(
        uci = "f7f6",
        san = "f6",
        fenAfter = "rnbqkbnr/ppppp1pp/5p2/8/4P3/8/PPPP1PPP/RNBQKBNR w KQkq - 0 2",
        cpAfter = 450,
        assessment = MoveAssessment(cpBefore = 30, cpPlayed = 450, cpBest = 30, cpLoss = 0, moveClass = MoveClass.BEST, motifs = listOf("x")),
    )

    @Test
    fun `regrade fixes a black move graded as white's`() {
        val fixed = MoveAssessor.regrade(staleBlackBlunder()).assessment!!
        assertEquals(420, fixed.cpLoss)
        assertEquals(MoveClass.BLUNDER, fixed.moveClass)
        assertEquals(listOf("x"), fixed.motifs, "only the grade changes")
    }

    @Test
    fun `regrade is a no-op on a correct record and on its own output`() {
        val white = MoveRecord(
            uci = "e2e3", san = "e3",
            fenAfter = "rnbqkbnr/pppppppp/8/8/8/4P3/PPPP1PPP/RNBQKBNR b KQkq - 0 1",
            assessment = MoveAssessor.assessMove(mover = Set.WHITE, cpBefore = 30, cpPlayed = -50, cpBest = 30),
        )
        assertTrue(MoveAssessor.regrade(white) === white, "a correct White record was rewritten")
        val once = MoveAssessor.regrade(staleBlackBlunder())
        assertTrue(MoveAssessor.regrade(once) === once, "regrading twice changed the record again")
    }

    @Test
    fun `regrade leaves what it cannot place alone`() {
        val noSide = staleBlackBlunder().copy(fenAfter = "")
        assertTrue(MoveAssessor.regrade(noSide) === noSide)
        val unassessed = staleBlackBlunder().copy(assessment = null)
        assertTrue(MoveAssessor.regrade(unassessed) === unassessed)
        val book = staleBlackBlunder().let { it.copy(assessment = it.assessment!!.copy(moveClass = MoveClass.BOOK)) }
        assertTrue(MoveAssessor.regrade(book) === book)
    }

    @Test
    fun `a resumed game re-grades its history on the way in`() {
        val snapshot = GameSnapshot(fen = staleBlackBlunder().fenAfter, moveHistory = listOf(staleBlackBlunder()))
        val restored = GameSnapshotMapper.toState(snapshot).moveHistory.single().assessment!!
        assertEquals(MoveClass.BLUNDER, restored.moveClass)
    }

    // --- thresholds --------------------------------------------------------------------------

    @Test
    fun `each class covers its documented band`() {
        assertEquals(MoveClass.BEST, lossOf(0).moveClass)
        assertEquals(MoveClass.EXCELLENT, lossOf(11).moveClass)
        assertEquals(MoveClass.GOOD, lossOf(31).moveClass)
        assertEquals(MoveClass.INACCURACY, lossOf(61).moveClass)
        assertEquals(MoveClass.MISTAKE, lossOf(101).moveClass)
        assertEquals(MoveClass.BLUNDER, lossOf(301).moveClass)
    }

    @Test
    fun `boundaries are inclusive on the better side`() {
        // Off-by-one here reclassifies every move sitting exactly on a threshold.
        assertEquals(MoveClass.BEST, lossOf(10).moveClass)
        assertEquals(MoveClass.EXCELLENT, lossOf(30).moveClass)
        assertEquals(MoveClass.GOOD, lossOf(60).moveClass)
        assertEquals(MoveClass.INACCURACY, lossOf(100).moveClass)
        assertEquals(MoveClass.MISTAKE, lossOf(300).moveClass)
    }

    @Test
    fun `classification is monotonic in cpLoss`() {
        // The property RAG-2 actually relies on: a worse move never classifies better. Sweeping
        // catches a reordered `when` that spot checks would miss.
        val ordered = (0..600 step 7).map { lossOf(it).moveClass.ordinal }
        assertEquals(ordered.sorted(), ordered, "moveClass must not improve as cpLoss grows")
    }

    @Test
    fun `an extreme loss is still a blunder rather than overflowing`() {
        assertEquals(MoveClass.BLUNDER, lossOf(100_000).moveClass) // mate scores land here
    }

    // --- passthrough -------------------------------------------------------------------------

    @Test
    fun `motifs and evaluations pass through untouched`() {
        val motifs = listOf(MotifDetector.FORK, MotifDetector.PIN)
        val a = MoveAssessor.assessMove(mover = Set.WHITE, cpBefore = 12, cpPlayed = 34, cpBest = 56, motifs = motifs)
        assertEquals(12, a.cpBefore)
        assertEquals(34, a.cpPlayed)
        assertEquals(56, a.cpBest)
        assertEquals(motifs, a.motifs)
    }

    @Test
    fun `motifs default to empty rather than null`() {
        assertTrue(MoveAssessor.assessMove(Set.WHITE, 0, 0, 0).motifs.isEmpty())
    }

    @Test
    fun `BOOK is never produced by assessMove`() {
        // BOOK exists on the enum for opening-book tagging, which nothing computes yet. If a future
        // caller starts emitting it, this test is the reminder that the ranking has a class with no
        // cpLoss band behind it.
        val produced = (0..1000 step 3).map { lossOf(it).moveClass }.toSet()
        assertTrue(MoveClass.BOOK !in produced)
    }
}
