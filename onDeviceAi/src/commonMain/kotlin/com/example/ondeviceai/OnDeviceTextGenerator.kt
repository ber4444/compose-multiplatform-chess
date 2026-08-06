package com.example.ondeviceai

sealed interface AiAvailability {
    data object Available : AiAvailability
    data class Downloadable(val requiresUserConfirmation: Boolean = true) : AiAvailability
    data class Downloading(val progress: Float? = null) : AiAvailability
    data object Unavailable : AiAvailability
    data object Busy : AiAvailability
    data class Error(val message: String) : AiAvailability
}

data class AiGenerationRequest(
    val systemPrompt: String,
    val userPrompt: String,
    val maxOutputTokens: Int,
    val temperature: Double = 0.2,
)

sealed interface AiTokenOrFinal {
    data class Token(val text: String) : AiTokenOrFinal
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
