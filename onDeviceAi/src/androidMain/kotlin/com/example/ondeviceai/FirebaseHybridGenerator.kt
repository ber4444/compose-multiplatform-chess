package com.example.ondeviceai

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import com.google.firebase.Firebase
import com.google.firebase.vertexai.vertexAI
import com.google.firebase.vertexai.type.content

import com.google.firebase.vertexai.type.generationConfig

class FirebaseHybridGenerator(private val mode: String) : OnDeviceTextGenerator {
    private val model = Firebase.vertexAI.generativeModel(
        modelName = "gemini-1.5-flash",
        generationConfig = generationConfig {
            responseMimeType = "application/json"
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
            emit(AiTokenOrFinal.Final(fullText, AiInferenceMetrics(0L, 0L, fullText.length, AiRoute.Cloud)))
        } catch (e: Exception) {
            emit(AiTokenOrFinal.Final("{\"error\": \"${e.message}\"}", AiInferenceMetrics(0L, 0L, 0, AiRoute.Cloud)))
        }
    }

    override suspend fun close() {}
}
