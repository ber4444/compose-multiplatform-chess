package com.example.myapplication.explorer

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class HumanMoveExplorerTest {
    private val position = ExplorerPosition("rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq - 0 1")
    private val band = RatingBand.R1400

    private class FakeMaia(var fail: Boolean = false) : HumanMovePredictor {
        var calls = 0
        override suspend fun predict(position: ExplorerPosition, band: RatingBand): HumanMoveStats {
            calls++
            if (fail) throw IllegalStateException("model failed")
            return HumanMoveStats(HumanMoveSource.MAIA, band, listOf(HumanMove("c7c5", "c5", 0.4)))
        }
        override fun close() = Unit
    }

    @Test
    fun answersWithMaiasPredictionAtTheBand() = runTest {
        val answer = assertIs<ExplorerAnswer.Moves>(HumanMoveExplorer(FakeMaia()).explore(position, band))
        assertEquals(HumanMoveSource.MAIA, answer.stats.source)
        assertEquals(band, answer.stats.band)
        assertEquals("c5", answer.stats.moves.single().san)
    }

    @Test
    fun withoutMaiaThereIsNothingToShow() {
        val none = HumanMoveExplorer(maia = null)
        assertFalse(none.canAnswer())
        assertEquals(null, none.forReview())
        val live = HumanMoveExplorer(FakeMaia())
        assertTrue(live.canAnswer())
        assertEquals(live, live.forReview())
    }

    @Test
    fun answersAreCachedButFailuresAreRetried() = runTest {
        val maia = FakeMaia(fail = true)
        val subject = HumanMoveExplorer(maia)
        assertEquals(ExplorerAnswer.NoAnswer, subject.explore(position, band))
        maia.fail = false
        assertIs<ExplorerAnswer.Moves>(subject.explore(position, band))
        subject.explore(position, band)
        assertEquals(2, maia.calls, "the answer is cached, the failure was not")
        subject.explore(position, RatingBand.R1800)
        assertEquals(3, maia.calls, "a different band is a different question")
    }

    @Test
    fun cancellationIsNotSwallowed() = runTest {
        val cancelling = object : HumanMovePredictor {
            override suspend fun predict(position: ExplorerPosition, band: RatingBand): HumanMoveStats =
                throw CancellationException("screen closed")
            override fun close() = Unit
        }
        assertFailsWith<CancellationException> { HumanMoveExplorer(cancelling).explore(position, band) }
    }
}
