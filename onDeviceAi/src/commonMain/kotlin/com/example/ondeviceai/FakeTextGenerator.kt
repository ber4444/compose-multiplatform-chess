package com.example.ondeviceai

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

class FakeTextGenerator(
    var status: AiAvailability = AiAvailability.Available,
    var response: String = "Nf3 develops a piece and supports the centre.",
    var tokenDelayMs: Long = 0,
    var throwOnGenerate: Throwable? = null,
    var chunks: List<String>? = null,
    var generateInterceptor: (suspend (AiGenerationRequest, Int) -> String?)? = null,
) : OnDeviceTextGenerator {
    var warmupCount: Int = 0
        private set
    var releaseCount: Int = 0
        private set
    var generateCount: Int = 0
        private set
    var lastRequest: AiGenerationRequest? = null
        private set

    override suspend fun status(): AiAvailability = status

    override suspend fun warmup() {
        warmupCount++
    }

    override fun generate(request: AiGenerationRequest): Flow<AiTokenOrFinal> = flow {
        generateCount++
        lastRequest = request
        tokenDelayMs.takeIf { it > 0 }?.let { delay(it) }
        throwOnGenerate?.let { throw it }
        val pieces = generateInterceptor?.let {
            listOf(it(request, generateCount) ?: "")
        } ?: chunks ?: listOf(response)
        for (piece in pieces) {
            delay(tokenDelayMs)
            emit(AiTokenOrFinal.Token(piece))
        }
        emit(
            AiTokenOrFinal.Final(
                text = "",
                metrics = AiInferenceMetrics(
                    firstTokenMs = tokenDelayMs,
                    completeMs = tokenDelayMs * pieces.size,
                    tokenCount = pieces.size,
                    route = AiRoute.OnDevice,
                )
            )
        )
    }

    override suspend fun release() {
        releaseCount++
    }
}

