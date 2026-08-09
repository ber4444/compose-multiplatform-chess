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
    /**
     * The code-detected facts about this ply, passed so the model can *reason about them* rather
     * than reword one sentence. Both are additive with defaults, so the published `:onDeviceAi`
     * API stays source-compatible for the React Native consumer.
     *
     * "Code detects, the model narrates" is preserved: these come from [MoveAssessment], the model
     * invents nothing. What changed is that the model can now see them at all — before this, the
     * prompt carried only [deterministicExplanation] and the model had no way to say anything about
     * the position it had not already been handed as a finished sentence.
     */
    val moveClassName: String? = null,
    val motifs: List<String> = emptyList(),
    val centipawnLoss: Int? = null,
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
