package com.example.ondeviceai

import kotlinx.serialization.Serializable
enum class ExplanationConfidence {
    HIGH,
    LOW,
    FALLBACK,
}

data class MoveCoachRequest(
    val fenBefore: String,
    val bestMoveUci: String,
    val bestMoveDisplay: String,
    val sideToMove: String,
    val evaluationBeforeCp: Int?,
    val evaluationAfterCp: Int?,
    val deterministicTags: List<String>,
    val engineDifficultyName: String,
    val policy: AiRoutePolicy = AiRoutePolicies.moveCoachOffline,
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
    data class FellBack(val text: String, val reason: String) : MoveCoachResult
    data class Failed(val message: String) : MoveCoachResult
}
