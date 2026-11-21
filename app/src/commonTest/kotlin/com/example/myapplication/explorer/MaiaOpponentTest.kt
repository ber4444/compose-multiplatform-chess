package com.example.myapplication.explorer

import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MaiaOpponentTest {
    private class ScriptedPredictor(private val moves: List<HumanMove>) : HumanMovePredictor {
        val asked = mutableListOf<Pair<ExplorerPosition, RatingBand>>()
        override suspend fun predict(position: ExplorerPosition, band: RatingBand): HumanMoveStats? {
            asked += position to band
            return if (moves.isEmpty()) null else HumanMoveStats(HumanMoveSource.MAIA, band, moves)
        }
        override fun close() = Unit
    }

    private val distribution = listOf(HumanMove("e2e4", "e4", 0.6), HumanMove("d2d4", "d4", 0.3), HumanMove("c2c4", "c4", 0.1))

    @Test
    fun samplesInProportionToHowOftenPlayersAtTheBandPlayEachMove() = runTest {
        val opponent = MaiaOpponent(ScriptedPredictor(distribution), RatingBand.R1400, Random(42))
        val picks = mutableListOf<String?>()
        repeat(3000) { picks += opponent.chooseMove(listOf(START)) }
        val counts = picks.groupingBy { it }.eachCount()
        assertEquals(setOf("e2e4", "d2d4", "c2c4"), counts.keys)
        assertTrue(counts.getValue("e2e4") in 1650..1950, "e4 drawn ${counts["e2e4"]} times")
        assertTrue(counts.getValue("c2c4") in 200..400, "c4 drawn ${counts["c2c4"]} times")
    }

    @Test
    fun asksAtItsBandWithTheGivenHistoryAndNamesItself() = runTest {
        val predictor = ScriptedPredictor(distribution)
        val opponent = MaiaOpponent(predictor, RatingBand.R1800, Random(1))
        val history = List(10) { "fen$it" } + START
        opponent.chooseMove(history)
        val (position, band) = predictor.asked.single()
        assertEquals(RatingBand.R1800, band)
        assertEquals(START, position.fen)
        assertEquals(history.takeLast(MAIA_HISTORY), position.history)
        assertEquals("Maia-3 (1800–1999)", opponent.name)
    }

    @Test
    fun declinesWhenMaiaHasNoMove() = runTest {
        assertNull(MaiaOpponent(ScriptedPredictor(emptyList()), RatingBand.R1400).chooseMove(listOf(START)))
    }

    private companion object {
        const val START = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
    }
}
