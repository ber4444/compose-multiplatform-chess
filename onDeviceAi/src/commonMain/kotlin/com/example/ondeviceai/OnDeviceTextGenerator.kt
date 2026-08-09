package com.example.ondeviceai

sealed interface AiAvailability {
    data object Available : AiAvailability
    data class Downloadable(val requiresUserConfirmation: Boolean = true) : AiAvailability
    data object Downloading : AiAvailability
    data object Unavailable : AiAvailability
    data object Busy : AiAvailability
    data class Error(val message: String) : AiAvailability
}

data class AiToolParameter(
    val type: String, // e.g. "string", "integer"
    val description: String,
)

data class AiToolSpec(
    val name: String,
    val description: String,
    val parameters: Map<String, AiToolParameter>,
)

data class AiGenerationRequest(
    val systemPrompt: String,
    val userPrompt: String,
    val maxOutputTokens: Int,
    val temperature: Double = 0.2,
    /** Sampler-level repetition penalty, for the runtimes whose API exposes one (wasm today). */
    val repetitionPenalty: Double? = 1.15,
    /** B15: cut the completion before an n-gram of this size reoccurs; `null` disables the rule. */
    val noRepeatNgramSize: Int? = 4,
    val stopSequences: List<String> = emptyList(),
    val tools: List<AiToolSpec> = emptyList(),
)

sealed interface AiTokenOrFinal {
    data class Token(val text: String) : AiTokenOrFinal
    data class ToolCall(val name: String, val arguments: Map<String, String>) : AiTokenOrFinal
    data class Final(
        val text: String,
        val metrics: AiInferenceMetrics,
    ) : AiTokenOrFinal
}

data class AiInferenceMetrics(
    val firstTokenMs: Long?,
    val completeMs: Long?,
    val tokenCount: Int,
    val route: AiRoute,
    val fallbackReason: AiRoutePolicyDecider.FallbackReason? = null,
)

interface OnDeviceTextGenerator {
    suspend fun status(): AiAvailability
    suspend fun warmup()
    fun generate(request: AiGenerationRequest): kotlinx.coroutines.flow.Flow<AiTokenOrFinal>
    suspend fun close()
    val supportsTools: Boolean get() = false
}


object UnsupportedTextGenerator : OnDeviceTextGenerator {
    override suspend fun status(): AiAvailability = AiAvailability.Unavailable
    override suspend fun warmup() = Unit
    override fun generate(
        request: AiGenerationRequest,
    ): kotlinx.coroutines.flow.Flow<AiTokenOrFinal> = kotlinx.coroutines.flow.flowOf()
    override suspend fun close() = Unit
}


/** Multiplatform wall-clock ms (no kotlinx-datetime dep — avoids export headaches on iOS). */
internal expect fun defaultNowMs(): Long
