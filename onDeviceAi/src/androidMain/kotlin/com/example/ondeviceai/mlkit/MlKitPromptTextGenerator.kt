package com.example.ondeviceai.mlkit

import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.common.GenAiException
import com.google.mlkit.genai.common.GenAiException.ErrorCode
import com.google.mlkit.genai.prompt.GenerateContentRequest
import com.google.mlkit.genai.prompt.GenerateContentResponse
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.GenerativeModel
import com.google.mlkit.genai.prompt.ModelPreference
import com.google.mlkit.genai.prompt.ModelReleaseStage
import com.google.mlkit.genai.prompt.TextPart
import com.google.mlkit.genai.prompt.generateContentRequest
import com.google.mlkit.genai.prompt.generationConfig
import com.google.mlkit.genai.prompt.modelConfig
import com.example.ondeviceai.AiAvailability
import com.example.ondeviceai.AiGenerationRequest
import com.example.ondeviceai.AiInferenceMetrics
import com.example.ondeviceai.AiRoute
import com.example.ondeviceai.AiTokenOrFinal
import com.example.ondeviceai.OnDeviceTextGenerator
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow

class MlKitPromptTextGenerator(
    private val model: GenerativeModel = defaultModel(),
) : OnDeviceTextGenerator {

    override suspend fun status(): AiAvailability {
        return try {
            when (model.checkStatus()) {
                FeatureStatus.AVAILABLE -> AiAvailability.Available
                FeatureStatus.DOWNLOADABLE -> AiAvailability.Downloadable(requiresUserConfirmation = true)
                FeatureStatus.DOWNLOADING -> AiAvailability.Downloading
                else -> AiAvailability.Unavailable
            }
        } catch (ce: kotlinx.coroutines.CancellationException) {
            throw ce
        } catch (t: Throwable) {
            AiAvailability.Error(t.message ?: "ML Kit status check failed")
        }
    }

    override suspend fun warmup() {
        runCatching { model.warmup() }
    }

    override fun generate(request: AiGenerationRequest): Flow<AiTokenOrFinal> = flow {
        val start = System.currentTimeMillis()
        var firstTokenMs: Long? = null
        var tokenCount = 0

        val mlKitRequest = buildRequest(request)
        model.generateContentStream(mlKitRequest)
            .catch { cause ->
                if (cause is kotlinx.coroutines.CancellationException) throw cause
                throw mapGenAiException(cause)
            }
            .collect { response ->
                if (firstTokenMs == null) firstTokenMs = System.currentTimeMillis() - start
                val text = response.textOrNull() ?: ""
                if (text.isNotEmpty()) {
                    tokenCount++
                    emit(AiTokenOrFinal.Token(text))
                }
            }
        emit(
            AiTokenOrFinal.Final(
                text = "",
                metrics = AiInferenceMetrics(
                    firstTokenMs = firstTokenMs,
                    completeMs = System.currentTimeMillis() - start,
                    tokenCount = tokenCount,
                    route = AiRoute.OnDevice,
                )
            )
        )
    }

    override suspend fun close() {
        runCatching { model.close() }
    }

    private fun buildRequest(request: AiGenerationRequest): GenerateContentRequest {
        val prompt = buildString {
            appendLine(request.systemPrompt)
            appendLine()
            appendLine(request.userPrompt)
        }
        return generateContentRequest(TextPart(prompt)) {
            maxOutputTokens = request.maxOutputTokens
            temperature = request.temperature.toFloat()
            candidateCount = 1
        }
    }

    private fun mapGenAiException(cause: Throwable): Throwable {
        val code = (cause as? GenAiException)?.errorCode ?: ErrorCode.UNKNOWN
        return when (code) {
            ErrorCode.BUSY,
            ErrorCode.PER_APP_BATTERY_USE_QUOTA_EXCEEDED,
            ErrorCode.BACKGROUND_USE_BLOCKED ->
                MlKitQuotaException("ML Kit quota/background blocked (code=$code)", cause)
            else -> cause
        }
    }

    class MlKitQuotaException(message: String, cause: Throwable?) : RuntimeException(message, cause)

    private companion object {
        fun defaultModel(): GenerativeModel = Generation.getClient(
            generationConfig {
                modelConfig {
                    preference = ModelPreference.FAST
                    releaseStage = ModelReleaseStage.STABLE
                }
            }
        )
    }
}

private fun GenerateContentResponse.textOrNull(): String? {
    val candidates = candidates
    return candidates.joinToString(separator = "") { it.text.orEmpty() }
        .takeIf { it.isNotEmpty() }
}
