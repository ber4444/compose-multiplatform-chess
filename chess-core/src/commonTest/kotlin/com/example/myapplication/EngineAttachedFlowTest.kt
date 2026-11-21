package com.example.myapplication

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Entry points attach Stockfish after the first frame, so the UI has to be able to observe the
 * attach rather than read [GameViewModel.engineAttached] once and keep the answer.
 */
class EngineAttachedFlowTest {

    private class NoOpEngine : ChessEngine {
        override suspend fun getBestMove(fen: String, thinkTimeMs: Long?): BestMoveResult? = null
        override fun close() {}
    }

    @Test
    fun tracksAttachDetachAndClose() {
        val viewModel = GameViewModel()
        assertFalse(viewModel.engineAttachedFlow.value, "no engine before attachEngine")

        viewModel.attachEngine(NoOpEngine())
        assertTrue(viewModel.engineAttachedFlow.value)

        // A failed start settles the seat with no engine: the CPU fallback is not "an engine".
        viewModel.attachEngine(null)
        assertFalse(viewModel.engineAttachedFlow.value)

        viewModel.attachEngine(NoOpEngine())
        viewModel.close()
        assertFalse(viewModel.engineAttachedFlow.value, "close() drops the engine")
    }
}
