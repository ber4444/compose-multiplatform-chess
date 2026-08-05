package com.example.ondeviceai

import com.example.myapplication.MoveRecord
import com.example.myapplication.Set

data class GameSummaryRequest(
    val pgn: String,
    val moveHistory: List<MoveRecord> = emptyList(),
    val playerSide: Set = Set.WHITE,
    val engineDifficultyName: String = "MEDIUM",
    val policy: AiRoutePolicy = AiRoutePolicies.moveCoachOffline,
)

data class GameSummaryExplanation(
    val explanation: String,
    val route: AiRoute,
    val metrics: AiInferenceMetrics,
)

sealed interface GameSummaryResult {
    data class Success(val explanation: GameSummaryExplanation) : GameSummaryResult
    data class FellBack(val text: String, val reason: AiRoutePolicyDecider.FallbackReason) : GameSummaryResult
    data class Failed(val message: String) : GameSummaryResult
}

sealed interface GameSummaryEvent {
    data class Streaming(val partialText: String) : GameSummaryEvent
    data class Complete(val result: GameSummaryResult) : GameSummaryEvent
}
