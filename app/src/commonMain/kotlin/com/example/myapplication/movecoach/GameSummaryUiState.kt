package com.example.myapplication.movecoach

import androidx.compose.runtime.Immutable
import com.example.ondeviceai.GameSummaryExplanation

@Immutable
sealed interface GameSummaryUiState {
    data object Hidden : GameSummaryUiState
    data object Loading : GameSummaryUiState
    data class Streaming(val text: String) : GameSummaryUiState
    data class Ready(val explanation: GameSummaryExplanation) : GameSummaryUiState
    data class Fallback(val text: String, val reason: String) : GameSummaryUiState
    data class Error(val message: String) : GameSummaryUiState
}
