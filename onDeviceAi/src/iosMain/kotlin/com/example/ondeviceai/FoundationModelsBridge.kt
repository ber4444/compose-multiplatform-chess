package com.example.ondeviceai

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

interface FoundationModelsBridge {
    suspend fun status(): AiAvailability
    suspend fun warmup()
    suspend fun generate(systemPrompt: String, userPrompt: String, maxTokens: Int): String
    fun close()
}

class FoundationModelsTextGenerator(
    private val bridge: FoundationModelsBridge,
) : OnDeviceTextGenerator {

    override suspend fun status(): AiAvailability = bridge.status()

    override suspend fun warmup() = bridge.warmup()

    override fun generate(request: AiGenerationRequest): Flow<AiTokenOrFinal> = flow {
        val start = defaultNowMs()
        val text = bridge.generate(
            systemPrompt = request.systemPrompt,
            userPrompt = request.userPrompt,
            maxTokens = request.maxOutputTokens,
        )
        if (text.isNotEmpty()) emit(AiTokenOrFinal.Token(text))
        emit(
            AiTokenOrFinal.Final(
                text = "",
                metrics = AiInferenceMetrics(
                    firstTokenMs = null,
                    completeMs = defaultNowMs() - start,
                    tokenCount = text.split(Regex("\\s+")).count { it.isNotBlank() },
                    route = AiRoute.OnDevice,
                )
            )
        )
    }.withAntiRepetitionGuard(
        ngramSize = request.noRepeatNgramSize,
        stopSequences = request.stopSequences,
    )

    override suspend fun release() = bridge.close()
}

fun createFoundationModelsTextGenerator(bridge: FoundationModelsBridge): OnDeviceTextGenerator =
    FoundationModelsTextGenerator(bridge)
