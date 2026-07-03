package com.example.ondeviceai

enum class ExplanationConfidence {
    HIGH,
    MEDIUM,
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
