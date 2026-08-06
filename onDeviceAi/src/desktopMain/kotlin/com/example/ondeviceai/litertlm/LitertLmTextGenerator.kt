package com.example.ondeviceai.litertlm

import com.example.ondeviceai.AiAvailability
import com.example.ondeviceai.AiGenerationRequest
import com.example.ondeviceai.AiInferenceMetrics
import com.example.ondeviceai.AiRoute
import com.example.ondeviceai.AiRoutePolicyDecider
import com.example.ondeviceai.AiTokenOrFinal
import com.example.ondeviceai.OnDeviceTextGenerator
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.SamplerConfig
import co.touchlab.kermit.Logger
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

/**
 * Desktop [OnDeviceTextGenerator] backed by LiteRT-LM (Google AI Edge)
 * via the `litertlm-jvm` Maven artifact.
 *
 * Mirrors [com.example.ondeviceai.cactus.CactusTextGenerator] on Android:
 * same [OnDeviceTextGenerator] contract, same single-thread serialization of
 * native calls, same no-op [close] (keeps the model warm across moves). The
 * only differences are the underlying runtime (LiteRT-LM vs Cactus)
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

    /** One shared initialization; see [CactusTextGenerator]'s `initJob` for why this can't be a
     *  plain re-entrant call (the model fetch releases [engineDispatcher] mid-flight). */
    private var initJob: Deferred<Unit>? = null
    private val initMutex = Mutex()
    private val initScope = CoroutineScope(SupervisorJob() + engineDispatcher)

    override suspend fun status(): AiAvailability {
        initializationFailed?.let { return AiAvailability.Error(it) }
        if (engine != null) return AiAvailability.Available
        return if (initJob?.isActive == true) AiAvailability.Downloading() else AiAvailability.Unavailable
    }

    /** Returns as soon as the download starts, so the UI stays live. [awaitWarmup] joins it. */
    override suspend fun warmup() {
        startInit()
    }

    suspend fun awaitWarmup() {
        startInit().await()
    }

    private suspend fun startInit(): Deferred<Unit> = initMutex.withLock {
        initJob ?: initScope.async { ensureInitialized() }.also { initJob = it }
    }

    override fun generate(request: AiGenerationRequest): Flow<AiTokenOrFinal> = flow {
        startInit().await()
        val activeEngine = engine ?: run {
            emit(
                failureMetric(
                    AiRoutePolicyDecider.FallbackReason.Other(
                        initializationFailed ?: "LiteRT-LM not initialized",
                    ),
                ),
            )
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
        val finished = kotlinx.coroutines.CompletableDeferred<Unit>()
        try {
            // litertlm-jvm 0.14.0 is compiled against an older kotlinx-coroutines, causing a
            // NoSuchMethodError on SendChannel.close$default when using its built-in callbackFlow
            // sendMessageAsync on Coroutines 1.9.0. We bridge via MessageCallback to compile
            // our own callbackFlow against 1.9.0.
            kotlinx.coroutines.flow.callbackFlow {
                val callback = object : com.google.ai.edge.litertlm.MessageCallback {
                    override fun onMessage(message: com.google.ai.edge.litertlm.Message) {
                        trySend(message)
                    }
                    override fun onDone() {
                        finished.complete(Unit)
                        close()
                    }
                    override fun onError(t: Throwable) {
                        finished.complete(Unit)
                        close(t)
                    }
                }
                try {
                    conversation.sendMessageAsync(request.userPrompt, callback)
                } catch (t: Throwable) {
                    finished.complete(Unit)
                    throw t
                }
                awaitClose {
                    conversation.cancelProcess()
                }
            }.collect { message ->
                val text = message.contents.contents
                    .filterIsInstance<Content.Text>()
                    .joinToString("") { it.text }
                if (text.isNotEmpty()) {
                    if (firstTokenMs == null) firstTokenMs = System.currentTimeMillis() - start
                    output.append(text)
                }
            }

            if (output.isNotEmpty()) {
                // Qwen3 (the default .litertlm model) emits <think>…</think>
                // chain-of-thought blocks before its answer. Strip them so the
                // Move Coach never shows internal deliberation to the user — and
                // so downstream validation (MoveCoachResponseValidator) judges
                // only the delivered answer, not the reasoning. Matches both a
                // closed <think>…</think> and an unterminated trailing <think>….
                val cleaned = stripThinkBlocks(output.toString())
                if (cleaned.isNotEmpty()) {
                    emit(AiTokenOrFinal.Token(cleaned))
                }
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
            withContext(kotlinx.coroutines.NonCancellable) {
                try {
                    // Wait for the native engine to actually finish using the conversation object
                    // before we delete it (to prevent C++ use-after-free or deadlocks).
                    kotlinx.coroutines.withTimeoutOrNull(5000) {
                        finished.await()
                    }
                } finally {
                    conversation.close()
                }
            }
        }
    }.flowOn(engineDispatcher)

    /** No-op — keeps the model warm across moves (mirrors CactusTextGenerator). */
    override suspend fun close() {}

    private fun ensureInitialized() {
        if (engine != null || initializationFailed != null) return
        try {
            if (!LitertLmModelStore.isDownloaded()) {
                LitertLmModelStore.download()
            }
            val configured = EngineConfig(
                modelPath = modelPath,
                backend = Backend.CPU(),
                maxNumTokens = contextSize,
            )
            val instance = Engine(configured)
            instance.initialize()
            engine = instance
        } catch (t: Throwable) {
            // Surface the full stack trace so init failures aren't invisible. Previously this
            // only stashed t.message into `initializationFailed` with no log, which made the
            // coroutines-bridge NoSuchMethodError (litertlm-jvm 0.14.0 vs coroutines <1.11.0)
            // present as a silent "stuck on LoadingModel" with no clue in logcat/stderr.
            Logger.w("LitertLmTextGenerator", t) { "LiteRT-LM init failed: ${t.message}" }
            initializationFailed = t.message ?: t::class.simpleName ?: "LiteRT-LM init failed"
        }
    }

    private fun failureMetric(reason: AiRoutePolicyDecider.FallbackReason) = AiTokenOrFinal.Final(
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

        // Matches <think>…</think> (case-insensitive, DOTALL so it spans newlines).
        // LiteRT-LM's Qwen3 model emits chain-of-thought before its answer; the
        // Move Coach contract wants only the delivered answer, so [generate]
        // strips these blocks before emitting. The unterminated variant catches
        // a <think> the model opened but never closed (generation cut off).
        private val THINK_BLOCK = Regex("(?is)<think>.*?</think>")
        private val THINK_UNTERMINATED = Regex("(?is)<think>.*")

        /**
         * Remove `<think>…</think>` chain-of-thought blocks from [text]. Handles
         * both closed blocks and an unterminated trailing `<think>`. Returns the
         * cleaned text, trimmed. If no `<think>` is present, returns the text
         * trimmed. Public on the companion so it can be unit-tested directly.
         */
        fun stripThinkBlocks(text: String): String {
            var cleaned = THINK_BLOCK.replace(text, "")
            cleaned = THINK_UNTERMINATED.replace(cleaned, "")
            return cleaned.trim()
        }
    }
}
