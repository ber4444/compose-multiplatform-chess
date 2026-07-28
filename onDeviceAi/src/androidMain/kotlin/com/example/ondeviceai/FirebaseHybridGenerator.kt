package com.example.ondeviceai

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

class FirebaseHybridGenerator(private val mode: String) : OnDeviceTextGenerator {

    override suspend fun status(): AiAvailability {
        return AiAvailability.Available
    }

    override suspend fun warmup() {}

    override fun generate(request: AiGenerationRequest): Flow<AiTokenOrFinal> = flow {
        // Stub implementation
        val fakeJson = """{"headline": "Good move", "explanation": "The move is good."}"""
        emit(AiTokenOrFinal.Final(fakeJson, AiInferenceMetrics(0L, 0L, 10, AiRoute.Cloud)))
    }

    override suspend fun close() {}
}
