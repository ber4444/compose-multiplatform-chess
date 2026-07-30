package com.example.ondeviceai

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.ModelPreference
import com.google.mlkit.genai.prompt.SystemInstruction
import com.google.mlkit.genai.prompt.TextPart
import com.google.mlkit.genai.prompt.generateContentRequest
import com.google.mlkit.genai.prompt.generationConfig
import com.google.mlkit.genai.prompt.modelConfig
import kotlinx.coroutines.tasks.await

class MlKitPromptGenerator(private val preference: String) : OnDeviceTextGenerator {
    
    private val modelConfig = modelConfig {
        preference = if (this@MlKitPromptGenerator.preference == "FAST") {
            ModelPreference.FAST
        } else {
            ModelPreference.FULL
        }
    }
    
    private val genConfig = generationConfig {
        this.modelConfig = this@MlKitPromptGenerator.modelConfig
    }
    
    private val model = Generation.getClient(genConfig)

    override suspend fun status(): AiAvailability {
        // Checking status properly requires context or context-aware calls in ML Kit, 
        // but for now we just return Available as network state logic happens in decider.
        return AiAvailability.Available
    }

    override suspend fun warmup() {
        // Optional warmup logic if ML Kit requires
    }

    override fun generate(request: AiGenerationRequest): Flow<AiTokenOrFinal> = flow {
        val sysInst = SystemInstruction(request.systemPrompt)
        val userPart = TextPart(request.userPrompt)
        
        val genRequest = generateContentRequest(sysInst, userPart) {}
        
        var fullText = ""
        try {
            model.generateContentStream(genRequest).collect { response ->
                response.candidates.firstOrNull()?.text?.let { chunk ->
                    fullText += chunk
                    emit(AiTokenOrFinal.Token(chunk))
                }
            }
            emit(AiTokenOrFinal.Final(fullText, AiInferenceMetrics(0L, 0L, fullText.length, AiRoute.OnDevice)))
        } catch (e: Exception) {
            emit(AiTokenOrFinal.Final("{\"error\": \"${e.message}\"}", AiInferenceMetrics(0L, 0L, 0, AiRoute.OnDevice)))
        }
    }

    override suspend fun close() {
        model.close()
    }
}
