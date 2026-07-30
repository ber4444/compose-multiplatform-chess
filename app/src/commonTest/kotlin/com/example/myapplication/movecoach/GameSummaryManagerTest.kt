package com.example.myapplication.movecoach

import com.example.ondeviceai.GameSummaryEvent
import com.example.ondeviceai.GameSummaryOrchestrator
import com.example.ondeviceai.GameSummaryRequest
import com.example.ondeviceai.GameSummaryResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Regression coverage for the "Get Coach Summary" button rendering (and doing nothing) on builds
 * where no orchestrator was ever wired: release Android, desktop without `CHESS_ENABLE_COACH=1`,
 * web without `?coach=1`. Before this fix, [GameSummaryManager.uiState] defaulted to
 * [GameSummaryUiState.Hidden] — the "ready" state that draws the trigger button — regardless of
 * whether an orchestrator existed, so pressing it silently no-opped ([GameSummaryManager.triggerSummary]
 * returns immediately without an orchestrator).
 */
class GameSummaryManagerTest {

    @Test
    fun `a fresh manager starts Unavailable not Hidden`() {
        val manager = GameSummaryManager()
        assertEquals(GameSummaryUiState.Unavailable, manager.uiState.value)
        manager.close()
    }

    @Test
    fun `attaching null explicitly keeps the manager Unavailable`() {
        val manager = GameSummaryManager()
        manager.attachOrchestrator(null)
        assertEquals(GameSummaryUiState.Unavailable, manager.uiState.value)
        manager.close()
    }

    @Test
    fun `attaching a real orchestrator moves the manager to the ready Hidden state`() {
        val manager = GameSummaryManager()
        manager.attachOrchestrator(fakeOrchestrator())
        assertEquals(GameSummaryUiState.Hidden, manager.uiState.value)
        manager.close()
    }

    @Test
    fun `triggering a summary without an orchestrator is a no-op and stays Unavailable`() {
        val manager = GameSummaryManager()
        manager.triggerSummary("1. e4 e5")
        assertEquals(GameSummaryUiState.Unavailable, manager.uiState.value)
        manager.close()
    }

    // triggerSummary sets Loading synchronously, then launches on the manager's own
    // (real-dispatcher) scope to collect the orchestrator's flow — so the terminal state lands
    // asynchronously.
    @Test
    fun `triggering a summary with an orchestrator surfaces its result`() = runTest {
        val manager = GameSummaryManager()
        manager.attachOrchestrator(fakeOrchestrator())

        manager.triggerSummary("1. e4 e5")

        // The predicate targets Ready specifically since Loading also matches "not Hidden."
        val result = manager.uiState.first { it is GameSummaryUiState.Ready }
        assertIs<GameSummaryUiState.Ready>(result)
        manager.close()
    }

    private fun fakeOrchestrator(): GameSummaryOrchestrator = object : GameSummaryOrchestrator {
        override suspend fun summarizeGame(request: GameSummaryRequest): GameSummaryResult =
            GameSummaryResult.Failed("unused")

        override fun summarizeGameStreaming(request: GameSummaryRequest): Flow<GameSummaryEvent> =
            flowOf(
                GameSummaryEvent.Complete(
                    GameSummaryResult.Success(
                        com.example.ondeviceai.GameSummaryExplanation(
                            explanation = "A quiet, well-developed game.",
                            route = com.example.ondeviceai.AiRoute.OnDevice,
                            metrics = com.example.ondeviceai.AiInferenceMetrics(
                                firstTokenMs = 10,
                                completeMs = 20,
                                tokenCount = 5,
                                route = com.example.ondeviceai.AiRoute.OnDevice,
                            ),
                        ),
                    ),
                ),
            )
    }
}
