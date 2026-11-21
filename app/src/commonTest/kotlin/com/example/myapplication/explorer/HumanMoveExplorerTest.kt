package com.example.myapplication.explorer

import com.example.myapplication.multiplayer.LichessException
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

    private class FakeLichess(var answer: () -> HumanMoveStats) : LichessOpeningExplorer {
        val calls = mutableListOf<Triple<String, RatingBand, String>>()
        override suspend fun lookup(fen: String, band: RatingBand, token: String): HumanMoveStats {
            calls += Triple(fen, band, token)
            return answer()
        }
    }

    private class FakeMaia : HumanMovePredictor {
        var calls = 0
        override suspend fun predict(position: ExplorerPosition, band: RatingBand): HumanMoveStats {
            calls++
            return HumanMoveStats(HumanMoveSource.MAIA, band, listOf(HumanMove("c7c5", "c5", 0.4)))
        }
        override fun close() = Unit
    }

    private fun lichessStats(total: Long) = HumanMoveStats(
        HumanMoveSource.LICHESS, band,
        if (total == 0L) emptyList() else listOf(HumanMove("e7e5", "e5", 0.5, total / 2)),
        totalGames = total,
    )

    private fun explorer(lichess: LichessOpeningExplorer?, maia: HumanMovePredictor?, token: String? = "tok") =
        HumanMoveExplorer(lichess, maia, { token })

    @Test
    fun signedInWithEnoughGamesAnswersFromLichess() = runTest {
        val lichess = FakeLichess { lichessStats(12_000) }
        val maia = FakeMaia()
        val answer = assertIs<ExplorerAnswer.Moves>(explorer(lichess, maia).explore(position, band))
        assertEquals(HumanMoveSource.LICHESS, answer.stats.source)
        assertEquals(null, answer.lichessMiss)
        assertEquals(listOf(Triple(position.fen, band, "tok")), lichess.calls)
        assertEquals(0, maia.calls, "Maia must not run when Lichess answered")
    }

    @Test
    fun notSignedInFallsBackToMaiaWithoutCallingLichess() = runTest {
        val lichess = FakeLichess { error("must not be called") }
        val answer = assertIs<ExplorerAnswer.Moves>(explorer(lichess, FakeMaia(), token = null).explore(position, band))
        assertEquals(HumanMoveSource.MAIA, answer.stats.source)
        assertEquals(LichessMiss.NOT_SIGNED_IN, answer.lichessMiss)
        assertTrue(lichess.calls.isEmpty())
    }

    @Test
    fun offlineFallsBackToMaia() = runTest {
        val lichess = FakeLichess { throw RuntimeException("Unable to resolve host explorer.lichess.org") }
        val answer = assertIs<ExplorerAnswer.Moves>(explorer(lichess, FakeMaia()).explore(position, band))
        assertEquals(HumanMoveSource.MAIA, answer.stats.source)
        assertEquals(LichessMiss.OFFLINE, answer.lichessMiss)
    }

    @Test
    fun lichessHttpErrorsFallBackToMaiaWithTheirReason() = runTest {
        for ((status, miss) in listOf(500 to LichessMiss.ERROR, 401 to LichessMiss.UNAUTHORIZED, 429 to LichessMiss.RATE_LIMITED)) {
            val lichess = FakeLichess { throw LichessException("boom", status) }
            val answer = assertIs<ExplorerAnswer.Moves>(explorer(lichess, FakeMaia()).explore(position, band))
            assertEquals(HumanMoveSource.MAIA, answer.stats.source, "status $status")
            assertEquals(miss, answer.lichessMiss, "status $status")
        }
    }

    @Test
    fun deepMiddlegamePositionWithNoExplorerDataFallsBackToMaia() = runTest {
        // Past the opening the explorer almost always returns zero games for a rating band.
        val answer = assertIs<ExplorerAnswer.Moves>(explorer(FakeLichess { lichessStats(0) }, FakeMaia()).explore(position, band))
        assertEquals(HumanMoveSource.MAIA, answer.stats.source)
        assertEquals(LichessMiss.NO_DATA, answer.lichessMiss)
    }

    @Test
    fun aHandfulOfGamesIsTreatedAsNoData() = runTest {
        val answer = assertIs<ExplorerAnswer.Moves>(
            explorer(FakeLichess { lichessStats(MIN_LICHESS_GAMES - 1) }, FakeMaia()).explore(position, band),
        )
        assertEquals(HumanMoveSource.MAIA, answer.stats.source)
        assertEquals(LichessMiss.NO_DATA, answer.lichessMiss)
    }

    @Test
    fun withoutMaiaThinLichessDataBeatsNothingButEmptyDataIsNoAnswer() = runTest {
        val thin = assertIs<ExplorerAnswer.Moves>(explorer(FakeLichess { lichessStats(8) }, maia = null).explore(position, band))
        assertEquals(HumanMoveSource.LICHESS, thin.stats.source)
        assertEquals(8L, thin.stats.totalGames)

        val none = explorer(FakeLichess { lichessStats(0) }, maia = null).explore(position, band)
        assertEquals(ExplorerAnswer.NoAnswer(LichessMiss.NO_DATA), none)

        val offline = explorer(FakeLichess { throw RuntimeException("offline") }, maia = null).explore(position, band)
        assertEquals(ExplorerAnswer.NoAnswer(LichessMiss.OFFLINE), offline)
    }

    @Test
    fun buildWithoutLichessUsesMaiaAndNamesNoLichessReason() = runTest {
        val answer = assertIs<ExplorerAnswer.Moves>(explorer(lichess = null, maia = FakeMaia()).explore(position, band))
        assertEquals(HumanMoveSource.MAIA, answer.stats.source)
        assertEquals(null, answer.lichessMiss)
    }

    @Test
    fun canAnswerOnlyWhenSomeSourceCan() {
        assertFalse(explorer(lichess = null, maia = null).canAnswer())
        assertFalse(explorer(FakeLichess { lichessStats(1) }, maia = null, token = null).canAnswer())
        assertTrue(explorer(FakeLichess { lichessStats(1) }, maia = null, token = "tok").canAnswer())
        assertTrue(explorer(lichess = null, maia = FakeMaia()).canAnswer())
    }

    @Test
    fun reviewNeverSeesTheExplorerDuringAnOnlineGameOrWithoutASource() {
        val live = explorer(lichess = null, maia = FakeMaia())
        assertEquals(live, live.forReview(assistanceBlocked = false))
        assertEquals(null, live.forReview(assistanceBlocked = true))
        assertEquals(null, explorer(lichess = null, maia = null).forReview(assistanceBlocked = false))
    }

    @Test
    fun transientFailuresAreRetriedButDefinitiveAnswersAreCached() = runTest {
        var offline = true
        val lichess = FakeLichess { if (offline) throw RuntimeException("offline") else lichessStats(9_000) }
        val subject = explorer(lichess, FakeMaia())
        assertEquals(LichessMiss.OFFLINE, assertIs<ExplorerAnswer.Moves>(subject.explore(position, band)).lichessMiss)
        offline = false
        assertEquals(HumanMoveSource.LICHESS, assertIs<ExplorerAnswer.Moves>(subject.explore(position, band)).stats.source)
        subject.explore(position, band)
        assertEquals(2, lichess.calls.size, "the Lichess answer is cached, the offline miss was not")
    }

    @Test
    fun signingInReasksLichessForACachedPosition() = runTest {
        var token: String? = null
        val lichess = FakeLichess { lichessStats(9_000) }
        val subject = HumanMoveExplorer(lichess, FakeMaia(), { token })
        assertEquals(LichessMiss.NOT_SIGNED_IN, assertIs<ExplorerAnswer.Moves>(subject.explore(position, band)).lichessMiss)
        token = "tok"
        assertEquals(HumanMoveSource.LICHESS, assertIs<ExplorerAnswer.Moves>(subject.explore(position, band)).stats.source)
    }

    @Test
    fun cancellationIsNotMistakenForBeingOffline() = runTest {
        val lichess = FakeLichess { throw CancellationException("screen closed") }
        assertFailsWith<CancellationException> { explorer(lichess, FakeMaia()).explore(position, band) }
    }
}
