package com.example.ondeviceai.litertlm

import com.example.ondeviceai.AiAvailability
import com.example.ondeviceai.AiGenerationRequest
import com.example.ondeviceai.AiInferenceMetrics
import com.example.ondeviceai.AiRoute
import com.example.ondeviceai.AiTokenOrFinal
import com.example.ondeviceai.OnDeviceTextGenerator
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.SamplerConfig
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

/**
 * Desktop [OnDeviceTextGenerator] backed by LiteRT-LM (Google AI Edge)
 * via the `litertlm-jvm` Maven artifact.
 *
 * Mirrors [com.example.ondeviceai.cactus.CactusTextGenerator] on Android:
 * same [OnDeviceTextGenerator] contract, same single-thread serialization of
 * native calls, same no-op [close] (keeps the model warm across moves). The
 * only differences are the underlying runtime (LiteRT-LM vs Cactus/llama.cpp)
 * and that the model is a `.litertlm` file on local disk (downloaded by
 * [LitertLmModelStore]) rather than a Cactus-packaged slug.
 *
 * All native calls are serialized through [engineDispatcher] (single-threaded)
 * to avoid races when a coach job is cancelled mid-inference and the next move
 * starts a new one — the same reason `CactusTextGenerator` does it.
 *
 * Native libs for `litertlm-jvm` are bundled inside the jar for
 * linux-x86_64 / linux-aarch64 / darwin-aarch64 / win-x86_64. On Intel Mac
 * (darwin-x86_64, unsupported) [ensureInitialized] catches the
 * `UnsatisfiedLinkError` and reports [AiAvailability.Error], so the
 * orchestrator falls back to [com.example.ondeviceai.MoveCoachFallback].
 */
class LitertLmTextGenerator(
    private val modelPath: String,
    private val contextSize: Int = DEFAULT_CONTEXT_SIZE,
) : OnDeviceTextGenerator {

    private val engineDispatcher = Executors.newSingleThreadExecutor { r ->
        Thread(r, "litertlm-engine").apply { isDaemon = true }
    }.asCoroutineDispatcher()

    @Volatile private var engine: Engine? = null
    @Volatile private var initializationFailed: String? = null

    override suspend fun status(): AiAvailability = withContext(engineDispatcher) {
        initializationFailed?.let { return@withContext AiAvailability.Error(it) }
        ensureInitialized()
        when {
            engine != null -> AiAvailability.Available
            else -> AiAvailability.Error(initializationFailed ?: "LiteRT-LM init failed")
        }
    }

    override suspend fun warmup() {
        withContext(engineDispatcher) { ensureInitialized() }
    }

    override fun generate(request: AiGenerationRequest): Flow<AiTokenOrFinal> = flow {
        ensureInitialized()
        val activeEngine = engine ?: run {
            emit(failureMetric(initializationFailed ?: "LiteRT-LM not initialized"))
            return@flow
        }

        // One conversation per generate() call — cheap (no model reload), and keeps
        // move-coach turns independent (no stray context bleeding between moves).
        val conversation = activeEngine.createConversation(
            ConversationConfig(
                systemInstruction = Contents.of(request.systemPrompt),
                samplerConfig = SamplerConfig(
                    topK = DEFAULT_TOP_K,
                    topP = DEFAULT_TOP_P,
                    temperature = request.temperature,
                    seed = 0,
                ),
            ),
        )

        val start = System.currentTimeMillis()
        val output = StringBuilder()
        var firstTokenMs: Long? = null
        try {
            // sendMessageAsync(String) is the cleanest overload — the conversation
            // already carries the system instruction via ConversationConfig.
            conversation.sendMessageAsync(request.userPrompt).collect { message ->
                val text = message.contents.contents
                    .filterIsInstance<Content.Text>()
                    .joinToString("") { it.text }
                if (text.isNotEmpty()) {
                    if (firstTokenMs == null) firstTokenMs = System.currentTimeMillis() - start
                    output.append(text)
                }
            }

            if (output.isNotEmpty()) {
                emit(AiTokenOrFinal.Token(output.toString()))
            }
            // getTokenCount() is @ExperimentalApi in litertlm-jvm and BenchmarkInfo is
            // gated too; we avoid both and derive a rough token count from output length
            // (≈4 chars/token for English). Our own firstTokenMs timing already gives us
            // the latency metric the orchestrator cares about.
            val tokenCount = maxOf(1, output.length / 4)
            emit(
                AiTokenOrFinal.Final(
                    text = "",
                    metrics = AiInferenceMetrics(
                        firstTokenMs = firstTokenMs,
                        completeMs = System.currentTimeMillis() - start,
                        tokenCount = tokenCount,
                        route = AiRoute.OnDevice,
                    ),
                ),
            )
        } finally {
            conversation.close()
        }
    }.flowOn(engineDispatcher)

    /** No-op — keeps the model warm across moves (mirrors CactusTextGenerator). */
    override suspend fun close() {}

    private fun ensureInitialized() {
        if (engine != null || initializationFailed != null) return
        try {
            val configured = EngineConfig(
                modelPath = modelPath,
                backend = Backend.CPU(),
                maxNumTokens = contextSize,
            )
            val instance = Engine(configured)
            instance.initialize()
            engine = instance
        } catch (t: Throwable) {
            initializationFailed = t.message ?: t::class.simpleName ?: "LiteRT-LM init failed"
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
        ),
    )

    companion object {
        /**
         * Must be <= the model's KV-cache capacity. The Qwen3-0.6B-int4 model
         * (`qwen3_0.6b_q4_block32_ekv1280.litertlm`) exposes `max_num_tokens: 1280`
         * in its metadata — the `ekv1280` in the filename is the extended KV-cache
         * size. Requesting more (the old default of 2048) compiles fine but the
         * native runtime traps with SIGTRAP (JVM exit 133) once a generation's
         * prompt + `<think>` chain-of-thought + output crosses 1280 tokens —
         * typically on checkmate/mate-in-N cases that trigger long reasoning.
         * The trap is a C++ CHECK inside litertlm-jvm, so it can't be caught by
         * Kotlin's `try/catch (Throwable)` and kills the whole driver. 1024
         * leaves headroom under the 1280 cap while still fitting the Move Coach
         * prompt + a full paraphrase.
         */
        const val DEFAULT_CONTEXT_SIZE = 1024
        const val DEFAULT_TOP_K = 40
        const val DEFAULT_TOP_P = 1.0
    }
}
