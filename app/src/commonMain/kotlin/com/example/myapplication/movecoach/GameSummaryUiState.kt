package com.example.myapplication.movecoach

import androidx.compose.runtime.Immutable
import com.example.ondeviceai.GameSummaryExplanation

@Immutable
sealed interface GameSummaryUiState {
    /** No orchestrator is attached (release Android, desktop without `CHESS_ENABLE_COACH=1`, web
     *  without `?coach=1`, or Foundation Models unavailable on iOS). The trigger button is hidden —
     *  pressing it would otherwise silently do nothing, since [GameSummaryManager.triggerSummary]
     *  no-ops without an orchestrator. This is the manager's initial state. */
    data object Unavailable : GameSummaryUiState

    /** An orchestrator is attached and idle; shows the "Get Coach Summary" trigger button. */
    data object Hidden : GameSummaryUiState
    data object Loading : GameSummaryUiState
    data class Streaming(val text: String) : GameSummaryUiState
    data class Ready(val explanation: GameSummaryExplanation) : GameSummaryUiState
    data class Fallback(val text: String, val reason: String) : GameSummaryUiState
    data class Error(val message: String) : GameSummaryUiState
}
