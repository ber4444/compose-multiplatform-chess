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
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

class LiteRtLmTextGenerator(
    private val pathToModel: String,
    private val cacheDir: String? = null,
    private val accelerator: Accelerator = Accelerator.GPU_PREFERRED,
) : OnDeviceTextGenerator {

    enum class Accelerator { CPU_ONLY, GPU_PREFERRED, NPU_PREFERRED }

    /**
     * Single-threaded dispatcher for ALL native LiteRT-LM calls. LiteRT-LM's
     * Engine/Conversation are not thread-safe; concurrent calls (e.g. a
     * cancelled coachJob's native sendMessage still running when the next
     * move's sendMessage starts) deadlock. This serializes every native call
     * onto one thread so:
     *  - Coroutine cancellation takes effect at the dispatcher queue — a
     *    cancelled coroutine's native call never starts.
     *  - If a native call is in progress, the next one queues behind it.
     */
    private val engineDispatcher = Executors.newSingleThreadExecutor { r ->
        Thread(r, "litertlm-engine").apply { isDaemon = true }
    }.asCoroutineDispatcher()

    private var engine: Engine? = null
    private var initializationFailed: String? = null

    override suspend fun status(): AiAvailability = withContext(engineDispatcher) {
        initializationFailed?.let { return@withContext AiAvailability.Error(it) }
        ensureEngineInitialized()
        if (engine != null) AiAvailability.Available
        else AiAvailability.Error(initializationFailed ?: "engine init failed")
    }

    override suspend fun warmup() {
        withContext(engineDispatcher) { ensureEngineInitialized() }
    }

    override fun generate(request: AiGenerationRequest): Flow<AiTokenOrFinal> = flow {
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
    }.flowOn(engineDispatcher)

    /** No-op — keeps the engine warm across moves. */
    override suspend fun close() {}

    private fun ensureEngineInitialized() {
        if (engine != null || initializationFailed != null) return
        val config = EngineConfig(
            modelPath = pathToModel,
            backend = when (accelerator) {
                Accelerator.CPU_ONLY -> Backend.CPU()
                Accelerator.GPU_PREFERRED -> Backend.GPU()
                Accelerator.NPU_PREFERRED -> Backend.GPU()
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
        const val DEFAULT_TOP_K = 10
        const val DEFAULT_TOP_P = 0.95
    }
}
