package com.example.ondeviceai.cactus

import com.cactus.CactusCompletionParams
import com.cactus.CactusInitParams
import com.cactus.CactusLM
import com.cactus.ChatMessage
import com.cactus.services.CactusConfig
import com.example.ondeviceai.AiAvailability
import com.example.ondeviceai.AiGenerationRequest
import com.example.ondeviceai.AiInferenceMetrics
import com.example.ondeviceai.AiRoute
import com.example.ondeviceai.AiTokenOrFinal
import com.example.ondeviceai.OnDeviceTextGenerator
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

/**
 * Android [OnDeviceTextGenerator] backed by Cactus (llama.cpp KMP wrapper).
 *
 * Replaces the earlier LiteRT-LM path which was too slow (557 MB model,
 * 7-9s cold start, GPU kernel compilation, streaming SIGSEGV at 0.13.1).
 *
 * Cactus uses llama.cpp's CPU kernels (ARM NEON-optimized, purpose-built for
 * LLM inference) and offers pre-packaged small models with built-in HF
 * download. The default model [modelSlug] is `gemma3-270m` (~200 MB) which
 * loads in ~1-2s and generates ~15-30 tok/s on a Snapdragon.
 *
 * All native calls are serialized through [engineDispatcher] (single-threaded)
 * to avoid race conditions when a coach job is cancelled mid-inference and
 * the next move starts a new one. See LiteRT-LM deadlock fix history.
 */
class CactusTextGenerator(
    private val modelSlug: String = DEFAULT_MODEL,
    private val contextSize: Int = DEFAULT_CONTEXT_SIZE,
) : OnDeviceTextGenerator {

    private val engineDispatcher = Executors.newSingleThreadExecutor { r ->
        Thread(r, "cactus-engine").apply { isDaemon = true }
    }.asCoroutineDispatcher()

    private var lm: CactusLM? = null
    private var initializationFailed: String? = null

    override suspend fun status(): AiAvailability = withContext(engineDispatcher) {
        initializationFailed?.let { return@withContext AiAvailability.Error(it) }
        ensureInitialized()
        if (lm != null) AiAvailability.Available
        else AiAvailability.Error(initializationFailed ?: "Cactus init failed")
    }

    override suspend fun warmup() {
        withContext(engineDispatcher) { ensureInitialized() }
    }

    override fun generate(request: AiGenerationRequest): Flow<AiTokenOrFinal> = flow {
        ensureInitialized()
        val activeLm = lm ?: run {
            emit(failureMetric(initializationFailed ?: "Cactus not initialized"))
            return@flow
        }

        val start = System.currentTimeMillis()
        val result = activeLm.generateCompletion(
            messages = listOf(
                ChatMessage(content = request.systemPrompt, role = "system"),
                ChatMessage(content = request.userPrompt, role = "user"),
            ),
            params = CactusCompletionParams(
                temperature = request.temperature,
                maxTokens = request.maxOutputTokens,
            ),
        )

        val text = result?.response.orEmpty()
        if (text.isNotEmpty()) {
            emit(AiTokenOrFinal.Token(text))
        }
        emit(
            AiTokenOrFinal.Final(
                text = "",
                metrics = AiInferenceMetrics(
                    firstTokenMs = result?.timeToFirstTokenMs?.toLong(),
                    completeMs = System.currentTimeMillis() - start,
                    tokenCount = result?.totalTokens ?: 1,
                    route = AiRoute.OnDevice,
                )
            )
        )
    }.flowOn(engineDispatcher)

    /** No-op — keeps the model warm across moves. */
    override suspend fun close() {}

    private suspend fun ensureInitialized() {
        if (lm != null || initializationFailed != null) return
        try {
            CactusConfig.isTelemetryEnabled = false
            val instance = CactusLM()
            instance.downloadModel(modelSlug)
            instance.initializeModel(
                CactusInitParams(model = modelSlug, contextSize = contextSize)
            )
            lm = instance
        } catch (t: Throwable) {
            initializationFailed = t.message ?: "Cactus init failed"
        }
    }

    private fun failureMetric(reason: String) = AiTokenOrFinal.Final(
        text = "",
        metrics = AiInferenceMetrics(
            firstTokenMs = null,
            completeMs = 0L,
            tokenCount = 0,
            route = AiRoute.OnDevice,
            fallbackReason = reason,
        )
    )

    companion object {
        const val DEFAULT_MODEL = "gemma3-270m"
        const val DEFAULT_CONTEXT_SIZE = 2048
    }
}
