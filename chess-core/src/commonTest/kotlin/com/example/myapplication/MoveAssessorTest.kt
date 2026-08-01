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
        MoveAssessor.assessMove(cpBefore = 0, cpPlayed = -cpLoss, cpBest = 0)

    // --- cpLoss ------------------------------------------------------------------------------

    @Test
    fun `cpLoss is best minus played`() {
        val a = MoveAssessor.assessMove(cpBefore = 50, cpPlayed = 20, cpBest = 120)
        assertEquals(100, a.cpLoss)
    }

    @Test
    fun `cpLoss clamps at zero when the played move beats the search's best`() {
        // Not hypothetical: cpPlayed and cpBest come from separate searches, so a deeper or luckier
        // line can score the played move above the "best" one. Without the clamp that is a negative
        // loss, which would sort to the top of RAG-2's turning-point ranking — the single worst
        // place for an artefact to land.
        val a = MoveAssessor.assessMove(cpBefore = 0, cpPlayed = 80, cpBest = 30)
        assertEquals(0, a.cpLoss)
        assertEquals(MoveClass.BEST, a.moveClass)
    }

    @Test
    fun `cpLoss is unaffected by cpBefore`() {
        // cpBefore is carried for the narrative ("you were winning"), not the classification.
        val losing = MoveAssessor.assessMove(cpBefore = -900, cpPlayed = 0, cpBest = 50)
        val winning = MoveAssessor.assessMove(cpBefore = 900, cpPlayed = 0, cpBest = 50)
        assertEquals(losing.cpLoss, winning.cpLoss)
        assertEquals(losing.moveClass, winning.moveClass)
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
        val a = MoveAssessor.assessMove(cpBefore = 12, cpPlayed = 34, cpBest = 56, motifs = motifs)
        assertEquals(12, a.cpBefore)
        assertEquals(34, a.cpPlayed)
        assertEquals(56, a.cpBest)
        assertEquals(motifs, a.motifs)
    }

    @Test
    fun `motifs default to empty rather than null`() {
        assertTrue(MoveAssessor.assessMove(0, 0, 0).motifs.isEmpty())
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
