package com.example.myapplication

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Picking Black has to start White, and nothing in the turn loop does it: engine turns run off
 * `animationEnd()`, and neither `resetGame()` nor a Settings change animates anything. A fresh game
 * as Black therefore sat on White's move forever, with every board correctly refusing the player's
 * taps because it was not their turn.
 *
 * The engine here only records that it was asked; returning `null` sends the move through the CPU
 * fallback, which is fine — the question these tests answer is *whether the opponent was started*,
 * and by whom.
 */
class PlayingAsBlackTest {

    private class RecordingEngine : ChessEngine {
        val asked = CompletableDeferred<String>()
        override suspend fun getBestMove(fen: String, thinkTimeMs: Long?): BestMoveResult? {
            asked.complete(fen)
            return null
        }
        override fun close() {}
    }

    private val viewModel = GameViewModel()

    @AfterTest
    fun tearDown() = viewModel.close()

    /**
     * The VM launches its engine turns on `Dispatchers.Default`, which `runTest`'s virtual clock
     * does not drive — a bare `runTest` expires every timeout instantly while the real work has not
     * started. Everything here therefore waits in real time.
     */
    private fun onRealTime(block: suspend () -> Unit) =
        kotlinx.coroutines.test.runTest { withContext(Dispatchers.Default) { block() } }

    @Test
    fun resettingWhilePlayingBlackStartsTheOpponent() = onRealTime {
        val engine = RecordingEngine()
        viewModel.attachEngine(engine)
        viewModel.playerSide = Set.BLACK

        viewModel.resetGame()

        withTimeout(TIMEOUT_MS) { engine.asked.await() }
    }

    @Test
    fun choosingBlackOnAFreshGameStartsTheOpponent() = onRealTime {
        val engine = RecordingEngine()
        viewModel.attachEngine(engine)

        // The Settings bridge assigns this; it is White's move, which is now the engine's.
        viewModel.playerSide = Set.BLACK

        withTimeout(TIMEOUT_MS) { engine.asked.await() }
    }

    @Test
    fun choosingWhiteLeavesTheOpponentAlone() = onRealTime {
        val engine = RecordingEngine()
        viewModel.attachEngine(engine)

        viewModel.playerSide = Set.WHITE

        delay(SETTLE_MS)
        assertTrue(engine.asked.isActive, "engine was asked to move on the player's own turn")
        assertEquals(0, viewModel.gameState.value.moveHistory.size)
    }

    @Test
    fun sideChosenBeforeTheEngineArrivesWaitsForIt() = onRealTime {
        // Entry points attach Stockfish asynchronously, and the Settings bridge runs at first
        // composition — so this ordering is the normal one, not an edge case. Starting the opponent
        // here would hand White's opening move to `pickMoveCPU`'s capture-preferring random pick a
        // moment before the real engine turned up.
        viewModel.playerSide = Set.BLACK

        delay(SETTLE_MS)
        assertEquals(
            0,
            viewModel.gameState.value.moveHistory.size,
            "the CPU fallback opened the game before the engine had a chance to attach",
        )

        val engine = RecordingEngine()
        viewModel.attachEngine(engine)

        withTimeout(TIMEOUT_MS) { engine.asked.await() }
    }

    @Test
    fun `a black player's legal move is accepted`() {
        // `playerMove`'s legality gate used to build White's move list whatever side the player was
        // on, so no Black move was ever in it and every one was refused as "Cannot move into
        // Check!" — the board looked frozen in 2D and 3D alike.
        val vm = GameViewModel(gameState = afterWhitesFirstMove())
        try {
            vm.playerSide = Set.BLACK

            val c7 = Pair(1, 2)
            val index = vm.gameState.value.positionsBlack.indexOf(c7)
            assertTrue(index != -1, "expected a black pawn on c7")

            vm.playerMove(index, Pair(3, 2)) // c7-c5

            assertEquals(
                Pair(3, 2),
                vm.gameState.value.positionsBlack[index],
                "the pawn did not move",
            )
            assertEquals(Set.WHITE, vm.gameState.value.turn)
        } finally {
            vm.close()
        }
    }

    @Test
    fun `a black player's illegal move is still refused`() {
        val vm = GameViewModel(gameState = afterWhitesFirstMove())
        try {
            vm.playerSide = Set.BLACK

            val c7 = Pair(1, 2)
            val index = vm.gameState.value.positionsBlack.indexOf(c7)

            vm.playerMove(index, Pair(5, 2)) // c7-c3, three ranks: not a pawn move

            assertEquals(c7, vm.gameState.value.positionsBlack[index], "an illegal move was applied")
            assertEquals(Set.BLACK, vm.gameState.value.turn)
        } finally {
            vm.close()
        }
    }

    /** Position after 1. d4 — Black to move, which is the player's turn in these tests. */
    private fun afterWhitesFirstMove(): GameUiState =
        FenConverter.fenToGameState("rnbqkbnr/pppppppp/8/8/3P4/8/PPP1PPPP/RNBQKBNR b KQkq d3 0 1")

    private companion object {
        /** The VM launches on `Dispatchers.Default`, so these are real milliseconds, not virtual. */
        const val TIMEOUT_MS = 10_000L
        const val SETTLE_MS = 250L
    }
}
