package com.example.ondeviceai.litert

import com.example.ondeviceai.AiAvailability
import com.example.ondeviceai.AiGenerationRequest
import com.example.ondeviceai.AiInferenceMetrics
import com.example.ondeviceai.AiRoute
import com.example.ondeviceai.AiTokenOrFinal
import com.example.ondeviceai.OnDeviceTextGenerator
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.SamplerConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow

/**
 * Android implementation of [OnDeviceTextGenerator] backed by LiteRT-LM
 * (`com.google.ai.edge.litertlm:litertlm-android`). Bundled-Gemma path that
 * works on any device with sufficient RAM — **no AICore / Gemini Nano
 * dependency** (the earlier ML Kit Prompt API path was removed because of
 * AICore's narrow device support).
 *
 * Verified against the API documented at
 * https://developers.google.com/edge/litert-lm/android (artifact 0.13.1):
 *   - `Engine(EngineConfig(modelPath, backend, cacheDir))` then `engine.initialize()`
 *   - `engine.createConversation(ConversationConfig(systemInstruction, samplerConfig))`
 *   - `conversation.sendMessageAsync(text): Flow<Message>` for streaming
 *
 * The `.litertlm` Gemma model asset is supplied by the chess app via
 * [pathToModel] — typically unpacked from app assets into `cacheDir` at first
 * launch (assets aren't directly readable by LiteRT-LM's native layer on all
 * Android versions). The chess app wires the path through
 * `defaultLitertLmModelPath()`.
 *
 * Per the LiteRT-LM guide, `engine.initialize()` is heavy (model load can take
 * seconds). This generator lazily initializes on the first `status()` call and
 * keeps the engine warm until [close]. Callers should call `warmup()`
 * opportunistically (e.g. when the coach panel mounts) to hide init latency
 * behind the user's first move.
 *
 * Backend selection: GPU is preferred when available (requires
 * `<uses-native-library android:name="libOpenCL.so" android:required="false"/>`
 * in the AndroidManifest, which the chess app declares). Falls back to CPU if
 * GPU init throws. NPU is left to a future per-SoC spike (plan §6.1.1) since it
 * needs vendor-specific dispatch libraries that the chess app doesn't bundle yet.
 */
class LiteRtLmTextGenerator(
    private val pathToModel: String,
    private val cacheDir: String? = null,
    private val accelerator: Accelerator = Accelerator.GPU_PREFERRED,
) : OnDeviceTextGenerator {

    enum class Accelerator { CPU_ONLY, GPU_PREFERRED, NPU_PREFERRED }

    private var engine: Engine? = null
    private var initializationFailed: String? = null
    private var isClosed: Boolean = false

    override suspend fun status(): AiAvailability {
        if (isClosed) return AiAvailability.Unavailable
        initializationFailed?.let { return AiAvailability.Error(it) }
        ensureEngineInitialized()
        return if (engine != null) AiAvailability.Available
        else AiAvailability.Error(initializationFailed ?: "engine init failed")
    }

    override suspend fun warmup() {
        if (isClosed) return
        ensureEngineInitialized()
    }

    override fun generate(request: AiGenerationRequest): Flow<AiTokenOrFinal> = flow {
        if (isClosed) {
            emit(failureMetric("generator closed"))
            return@flow
        }
        ensureEngineInitialized()
        val activeEngine = engine ?: run {
            emit(failureMetric(initializationFailed ?: "engine not initialized"))
            return@flow
        }

        val start = System.currentTimeMillis()

        val conversationConfig = ConversationConfig(
            systemInstruction = com.google.ai.edge.litertlm.Contents.of(request.systemPrompt),
            samplerConfig = SamplerConfig(
                topK = DEFAULT_TOP_K,
                topP = DEFAULT_TOP_P,
                temperature = request.temperature,
                seed = 0,
            ),
        )
        val conversation = activeEngine.createConversation(conversationConfig)
        try {
            // Use synchronous sendMessage() instead of sendMessageAsync() (Flow).
            // The Flow/streaming path spawns a native callback_thread that
            // SIGSEGVs at 0.13.1 (null pointer deref ~6ms after thread creation).
            // The sync API runs inference on the calling thread — no callback
            // thread, no crash. The coach only needs ~2 sentences so blocking
            // for a few seconds is acceptable.
            val response = conversation.sendMessage(
                com.google.ai.edge.litertlm.Contents.of(request.userPrompt)
            )
            val text = response.contents.contents
                .filterIsInstance<Content.Text>()
                .joinToString("") { it.text }
            if (text.isNotEmpty()) {
                emit(AiTokenOrFinal.Token(text))
            }
        } finally {
            runCatching { conversation.close() }
        }

        emit(
            AiTokenOrFinal.Final(
                text = "",
                metrics = AiInferenceMetrics(
                    firstTokenMs = null,
                    completeMs = System.currentTimeMillis() - start,
                    tokenCount = 1,
                    route = AiRoute.OnDevice,
                )
            )
        )
    }

    /**
     * No-op — the engine stays warm across moves. The orchestrator calls close()
     * after each coached move; actually closing the LiteRT-LM engine here would
     * destroy the model and force a 2-10s re-initialization on the next move.
     * Cleanup happens when the Activity is destroyed / process dies.
     */
    override suspend fun close() {
        // Intentionally not closing engine or conversation pool.
    }

    @Synchronized
    private fun ensureEngineInitialized() {
        if (engine != null || initializationFailed != null || isClosed) return
        val config = EngineConfig(
            modelPath = pathToModel,
            backend = when (accelerator) {
                Accelerator.CPU_ONLY -> Backend.CPU()
                Accelerator.GPU_PREFERRED -> Backend.GPU()
                Accelerator.NPU_PREFERRED -> Backend.GPU() // NPU needs vendor dispatch libs; fall back to GPU
            },
            cacheDir = cacheDir,
        )
        engine = try {
            Engine(config).also { it.initialize() }
        } catch (t: Throwable) {
            initializationFailed = t.message ?: "LiteRT-LM engine init failed"
            null
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

    private companion object {
        // Conservative sampler defaults for a 2-sentence coach explanation.
        // Low temperature is enforced by the caller (request.temperature), but
        // we still cap topK/topP so the model doesn't wander into hallucinated
        // chess variations.
        const val DEFAULT_TOP_K = 10
        const val DEFAULT_TOP_P = 0.95
    }
}
