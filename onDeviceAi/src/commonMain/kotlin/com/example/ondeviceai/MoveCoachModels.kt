package com.example.ondeviceai

import kotlinx.serialization.Serializable
enum class ExplanationConfidence {
    HIGH,
    LOW,
    FALLBACK,
}

data class MoveCoachRequest(
    val moveUci: String,
    val moveDisplay: String,
    val deterministicHeadline: String,
    val deterministicExplanation: String,
    val engineDifficultyName: String,
    val policy: AiRoutePolicy = AiRoutePolicies.moveCoachOffline,
    val bannedOpeningFrames: List<String> = emptyList(),
)


@Serializable
data class MoveCoachResponse(
    val headline: String,
    val explanation: String
)

data class MoveCoachExplanation(
    val headline: String,
    val explanation: String,
    val confidence: ExplanationConfidence,
    val route: AiRoute,
    val metrics: AiInferenceMetrics,
)

sealed interface MoveCoachResult {
    data class Success(val explanation: MoveCoachExplanation) : MoveCoachResult
    data class FellBack(val text: String, val reason: AiRoutePolicyDecider.FallbackReason) : MoveCoachResult
    data class Failed(val message: String) : MoveCoachResult
}
