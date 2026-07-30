package com.example.ondeviceai

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import com.google.firebase.Firebase
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.InferenceMode
import com.google.firebase.ai.type.content
import com.google.firebase.ai.type.generationConfig

class FirebaseHybridGenerator(private val mode: AiInferenceMode) : OnDeviceTextGenerator {
    private val model = Firebase.ai.generativeModel(
        modelName = "gemini-2.5-flash-lite",
        inferenceMode = when (mode) {
            AiInferenceMode.PREFER_ON_DEVICE -> InferenceMode.PREFER_ON_DEVICE
            AiInferenceMode.PREFER_IN_CLOUD -> InferenceMode.PREFER_IN_CLOUD
            AiInferenceMode.ONLY_ON_DEVICE -> InferenceMode.ONLY_ON_DEVICE
            AiInferenceMode.ONLY_IN_CLOUD -> InferenceMode.ONLY_IN_CLOUD
        },
        generationConfig = generationConfig {
            responseMimeType = "application/json"
            responseSchema = MoveCoachResponse_Schema
        }
    )

    override suspend fun status(): AiAvailability {
        return AiAvailability.Available
    }

    override suspend fun warmup() {}

    override fun generate(request: AiGenerationRequest): Flow<AiTokenOrFinal> = flow {
        val prompt = content {
            text(request.systemPrompt)
            text(request.userPrompt)
        }
        
        var fullText = ""
        try {
            model.generateContentStream(prompt).collect { response ->
                response.text?.let { chunk ->
                    fullText += chunk
                    emit(AiTokenOrFinal.Token(chunk))
                }
            }
            emit(AiTokenOrFinal.Final(fullText, AiInferenceMetrics(0L, 0L, fullText.length, AiRoute.OnDevice)))
        } catch (e: Exception) {
            throw e
        }
    }

    override suspend fun close() {}
}
