package com.example.myapplication

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * An [OpponentMoveChooser] plays the engine side; the engine only moves when the chooser declines,
 * fails or is wrong. Opening as Black makes White the engine side, so the first move is the
 * chooser's.
 */
class OpponentMoveChooserTest {
    private class RecordingEngine : ChessEngine {
        val asked = CompletableDeferred<String>()
        override suspend fun getBestMove(fen: String, thinkTimeMs: Long?): BestMoveResult? {
            asked.complete(fen)
            return BestMoveResult("d2d4", evaluationCp = null)
        }
        override fun close() {}
    }

    private class Chooser(private val answer: suspend (List<String>) -> String?) : OpponentMoveChooser {
        override val name = "Test opponent"
        val histories = mutableListOf<List<String>>()
        override suspend fun chooseMove(fenHistory: List<String>): String? {
            histories += fenHistory
            return answer(fenHistory)
        }
    }

    private val viewModel = GameViewModel()

    @AfterTest
    fun tearDown() = viewModel.close()

    private fun onRealTime(block: suspend () -> Unit) =
        kotlinx.coroutines.test.runTest { withContext(Dispatchers.Default) { block() } }

    private suspend fun firstMove(): String {
        withTimeout(TIMEOUT_MS) { while (viewModel.gameState.value.moveHistory.isEmpty()) delay(10) }
        return viewModel.gameState.value.moveHistory.first().uci
    }

    private suspend fun open(chooser: OpponentMoveChooser, engine: RecordingEngine): String {
        viewModel.setOpponentMoveChooser(chooser)
        viewModel.attachEngine(engine)
        viewModel.playerSide = Set.BLACK
        return firstMove()
    }

    @Test
    fun chooserPlaysTheEngineSideAndTheEngineIsNotAsked() = onRealTime {
        val chooser = Chooser { "e2e4" }
        val engine = RecordingEngine()
        assertEquals("e2e4", open(chooser, engine))
        assertFalse(engine.asked.isCompleted, "the engine moved although the chooser answered")
        assertEquals(listOf(FenConverter.STARTING_FEN), chooser.histories.single())
        assertEquals("Test opponent", viewModel.opponentName)
    }

    @Test
    fun declinedIllegalOrFailedChoicesFallBackToTheEngine() {
        for (answer in listOf<suspend (List<String>) -> String?>({ null }, { "e2e5" }, { "zz" }, { error("model crashed") })) {
            val vm = GameViewModel()
            try {
                kotlinx.coroutines.test.runTest {
                    withContext(Dispatchers.Default) {
                        val engine = RecordingEngine()
                        vm.setOpponentMoveChooser(Chooser(answer))
                        vm.attachEngine(engine)
                        vm.playerSide = Set.BLACK
                        withTimeout(TIMEOUT_MS) { engine.asked.await() }
                        withTimeout(TIMEOUT_MS) { while (vm.gameState.value.moveHistory.isEmpty()) delay(10) }
                        assertEquals("d2d4", vm.gameState.value.moveHistory.first().uci)
                    }
                }
            } finally {
                vm.close()
            }
        }
    }

    @Test
    fun clearingTheChooserRestoresTheEngineAndItsName() = onRealTime {
        viewModel.setOpponentMoveChooser(Chooser { "e2e4" })
        viewModel.setOpponentMoveChooser(null)
        val engine = RecordingEngine()
        viewModel.attachEngine(engine)
        assertEquals("Stockfish", viewModel.opponentName)
        viewModel.playerSide = Set.BLACK
        withTimeout(TIMEOUT_MS) { engine.asked.await() }
        assertTrue(firstMove() == "d2d4")
    }

    private companion object {
        const val TIMEOUT_MS = 5_000L
    }
}
