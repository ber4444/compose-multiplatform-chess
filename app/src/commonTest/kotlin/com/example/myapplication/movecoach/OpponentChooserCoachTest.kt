package com.example.myapplication.movecoach

import com.example.myapplication.BestMoveResult
import com.example.myapplication.ChessEngine
import com.example.myapplication.GameViewModel
import com.example.myapplication.OpponentMoveChooser
import com.example.myapplication.board3d.HighlightTone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The panel under the board, end to end, when an [OpponentMoveChooser] plays the reply — the seam
 * Maia uses, and Maia is the default opponent.
 *
 * [MoveCoachToneTest] fires `onMoveCoached` by hand, so it could not see this break: once Maia
 * replied, the view model never fired the callback at all (no reply search meant no `cpAfter`, and
 * idle analysis skipped any move without one). The panel stayed [MoveCoachUiState.Hidden] and the
 * board lost its move colours, with every coach test green. This drives a real move instead.
 */
class OpponentChooserCoachTest {

    /** Best move for White scores +30; every evaluated position is -400, so e4 loses 430 cp. */
    private class Engine : ChessEngine {
        override suspend fun getBestMove(fen: String, thinkTimeMs: Long?): BestMoveResult =
            if (" b " in fen) BestMoveResult("e7e5", evaluationCp = 0) else BestMoveResult("g1f3", evaluationCp = 30)
        override suspend fun evaluate(fen: String, thinkTimeMs: Long?): Int = -400
        override fun close() {}
    }

    private class RepliesE5 : OpponentMoveChooser {
        override val name = "Test opponent"
        override suspend fun chooseMove(fenHistory: List<String>): String = "e7e5"
    }

    @Test
    fun `a move the chooser answers still reaches the panel with its colour`() = runTest {
        val vm = GameViewModel()
        vm.aiCoachEnabled = true
        // No orchestrator, as Android and iOS ship: the deterministic line is the panel's content.
        val manager = MoveCoachManager(vm)
        try {
            // The VM and the manager run on Dispatchers.Default, which runTest's virtual clock does
            // not drive, so every wait here is in real time.
            withContext(Dispatchers.Default) {
                vm.setOpponentMoveChooser(RepliesE5())
                vm.attachEngine(Engine())

                vm.playerMove(vm.gameState.value.positionsWhite.indexOf(Pair(6, 4)), Pair(4, 4)) // e2-e4
                vm.animationEnd() // the player's animation ends: the chooser replies
                withTimeout(TIMEOUT_MS) {
                    while (vm.gameState.value.moveHistory.size < 2 || vm.animState.value.pieceToAnimate == null) delay(10)
                }
                vm.animationEnd() // the reply's animation ends: idle analysis coaches e4

                val state = withTimeout(TIMEOUT_MS) {
                    manager.coachUiState.first { it !is MoveCoachUiState.Hidden }
                }
                val ready = assertIs<MoveCoachUiState.Ready>(state, "the panel under the board never appeared")
                assertTrue(ready.explanation.explanation.isNotBlank(), "the panel has no coach line")
                assertEquals(HighlightTone.BAD, ready.tone, "a 430 cp loss must paint the board red")
                assertEquals(listOf("e2", "e4"), ready.squares)
            }
        } finally {
            manager.close()
            vm.close()
        }
    }

    private companion object {
        const val TIMEOUT_MS = 5_000L
    }
}
