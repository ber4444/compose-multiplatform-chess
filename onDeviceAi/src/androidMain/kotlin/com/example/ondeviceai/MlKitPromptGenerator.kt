package com.example.ondeviceai

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.ModelPreference
import com.google.mlkit.genai.prompt.ModelReleaseStage
import com.google.mlkit.genai.prompt.SystemInstruction
import com.google.mlkit.genai.prompt.TextPart
import com.google.mlkit.genai.prompt.generateContentRequest
import com.google.mlkit.genai.prompt.generationConfig
import com.google.mlkit.genai.prompt.modelConfig

class MlKitPromptGenerator(private val routePreference: com.example.ondeviceai.ModelPreference) : OnDeviceTextGenerator {
    
    private val modelConfig = modelConfig {
        releaseStage = ModelReleaseStage.PREVIEW
        preference = if (this@MlKitPromptGenerator.routePreference == com.example.ondeviceai.ModelPreference.FAST) {
            com.google.mlkit.genai.prompt.ModelPreference.FAST
        } else {
            com.google.mlkit.genai.prompt.ModelPreference.FULL
        }
    }
    
    private val genConfig = generationConfig {
        this.modelConfig = this@MlKitPromptGenerator.modelConfig
    }
    
    private val model = Generation.getClient(genConfig)

    override suspend fun status(): AiAvailability {
        // VendorRouteExecutor's own fallback is `if (mlkit.status() is Available) mlkit else
        // getCactus()` — any non-Available result here (including Error) already routes to Cactus.
        // This used to hardcode Available regardless of real device support, which meant Cactus was
        // silently unreachable on any device without working AICore: ML Kit would be picked, fail
        // generation with e.g. ErrorCode -101, and never fall through. checkStatus() itself might
        // throw rather than cleanly return UNAVAILABLE on such devices — either outcome must still
        // fall through, so both paths are covered.
        return try {
            when (model.checkStatus()) {
                FeatureStatus.AVAILABLE -> AiAvailability.Available
                FeatureStatus.DOWNLOADABLE -> AiAvailability.Downloadable()
                FeatureStatus.DOWNLOADING -> AiAvailability.Downloading()
                else -> AiAvailability.Unavailable
            }
        } catch (e: Exception) {
            AiAvailability.Error(e.message ?: "checkStatus failed")
        }
    }

    override suspend fun warmup() {
        // Optional warmup logic if ML Kit requires
    }

    override fun generate(request: AiGenerationRequest): Flow<AiTokenOrFinal> = flow {
        val sysInst = SystemInstruction(request.systemPrompt)
        val userPart = TextPart(request.userPrompt)
        
        val genRequest = generateContentRequest(sysInst, userPart) {}
        
        // Let a generation failure (e.g. AICore not installed — ErrorCode -101) propagate as a real
        // exception rather than swallowing it here. DefaultAiCoachOrchestrator.runOnDevice already
        // wraps generation in a catch (t: Throwable) that reports a clean "generation error: ..."
        // fallback; emitting the error as a fake successful JSON payload instead made the real cause
        // invisible — it surfaced downstream as an opaque "model output failed validation" once the
        // error string failed to parse against the {headline, explanation} schema.
        var fullText = ""
        model.generateContentStream(genRequest).collect { response ->
            response.candidates.firstOrNull()?.text?.let { chunk ->
                fullText += chunk
                emit(AiTokenOrFinal.Token(chunk))
            }
        }
        emit(AiTokenOrFinal.Final(fullText, AiInferenceMetrics(0L, 0L, fullText.length, AiRoute.OnDevice)))
    }

    override suspend fun release() {
        model.close()
    }
}
