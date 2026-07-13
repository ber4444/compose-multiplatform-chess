package com.example.ondeviceai

data class GameSummaryRequest(
    val pgn: String,
    val policy: AiRoutePolicy = AiRoutePolicies.moveCoachOffline,
)

data class GameSummaryExplanation(
    val explanation: String,
    val route: AiRoute,
    val metrics: AiInferenceMetrics,
)

sealed interface GameSummaryResult {
    data class Success(val explanation: GameSummaryExplanation) : GameSummaryResult
    data class FellBack(val text: String, val reason: String) : GameSummaryResult
    data class Failed(val message: String) : GameSummaryResult
}

sealed interface GameSummaryEvent {
    data class Streaming(val partialText: String) : GameSummaryEvent
    data class Complete(val result: GameSummaryResult) : GameSummaryEvent
}
