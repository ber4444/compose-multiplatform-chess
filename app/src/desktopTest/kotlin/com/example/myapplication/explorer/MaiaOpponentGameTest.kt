package com.example.myapplication.explorer

import com.example.myapplication.GameViewModel
import com.example.myapplication.Set
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The real bundled model opens a game as White through the view model's opponent seam. */
class MaiaOpponentGameTest {
    @Test
    fun maiaOpensWithOneOfTheMovesPlayersAtTheBandPlay(): Unit = runBlocking {
        val predictor = MaiaHumanMovePredictor(assertNotNull(maiaRuntimeFactory()))
        val viewModel = GameViewModel()
        try {
            val opponent = MaiaOpponent(predictor, RatingBand.R1400)
            viewModel.setOpponentMoveChooser(opponent)
            viewModel.attachEngine(null) // no Stockfish: Maia must not need it to play
            viewModel.playerSide = Set.BLACK
            withTimeout(15_000) { while (viewModel.gameState.value.moveHistory.isEmpty()) delay(20) }

            val played = viewModel.gameState.value.moveHistory.first().uci
            val likely = predictor.predict(ExplorerPosition(START), RatingBand.R1400)!!.moves.map { it.uci }
            assertTrue(played in likely, "Maia opened $played, not one of $likely")
            assertEquals("Maia-3 (1400–1599)", viewModel.opponentName)
        } finally {
            viewModel.close()
            predictor.close()
        }
    }

    private companion object {
        const val START = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
    }
}
