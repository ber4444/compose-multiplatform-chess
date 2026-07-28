package com.example.ondeviceai

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

// Stub implementation for compilation, since ML Kit Structured Output requires
// actual Android runtime to work fully. This honors the interface.
class MlKitPromptGenerator(private val preference: String) : OnDeviceTextGenerator {

    override suspend fun status(): AiAvailability {
        // In a real app we'd check ML Kit availability here.
        // For this refactoring exercise we assume it's available.
        return AiAvailability.Available
    }

    override suspend fun warmup() {}

    override fun generate(request: AiGenerationRequest): Flow<AiTokenOrFinal> = flow {
        // Mocking the structured output as JSON string because ML Kit outputs the @Generable object,
        // and we serialize it to flow back to the orchestrator.
        val fakeJson = """{"headline": "Good move", "explanation": "The move is good."}"""
        emit(AiTokenOrFinal.Final(fakeJson, AiInferenceMetrics(0L, 0L, 10, AiRoute.OnDevice)))
    }

    override suspend fun close() {}
}
