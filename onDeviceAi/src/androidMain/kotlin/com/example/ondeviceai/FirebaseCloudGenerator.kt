package com.example.ondeviceai

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import com.google.firebase.Firebase
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.content
import com.google.firebase.ai.type.generationConfig

class FirebaseCloudGenerator(private val modelName: String = "gemini-2.5-flash-lite") : OnDeviceTextGenerator {
    private val model = Firebase.ai.generativeModel(
        modelName = modelName,
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
