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
import com.example.ondeviceai.AiRoutePolicyDecider
import com.example.ondeviceai.AiTokenOrFinal
import com.example.ondeviceai.OnDeviceTextGenerator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.Executors

/**
 * Android [OnDeviceTextGenerator] backed by Cactus.
 *
 * Replaces the earlier LiteRT-LM path which was too slow (557 MB model,
 * 7-9s cold start, GPU kernel compilation, streaming SIGSEGV at 0.13.1).
 *
 * Cactus uses its own hand-written ARM CPU kernels (ARM NEON-optimized, purpose-built for
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

    // @Volatile because status() is deliberately NOT confined to engineDispatcher any more (B18:
    // it must answer instantly while a download runs, instead of blocking behind it). Without the
    // barrier a caller on another thread can keep reading a stale null and report Unavailable
    // forever after init has already succeeded on the engine thread.
    @Volatile private var lm: CactusLM? = null
    @Volatile private var initializationFailed: String? = null

    /**
     * The single in-flight initialization, or null when none has started. Shared rather than
     * re-entered: `downloadModel` internally hops to `Dispatchers.IO`, which *releases*
     * [engineDispatcher] mid-flight, so a `generate()` arriving during warmup would otherwise pass
     * `ensureInitialized`'s null check and start a second ~200 MB download of the same model.
     */
    private var initJob: Deferred<Unit>? = null
    private val initMutex = Mutex()
    private val initScope = CoroutineScope(SupervisorJob() + engineDispatcher)

    override suspend fun status(): AiAvailability {
        initializationFailed?.let { return AiAvailability.Error(it) }
        if (lm != null) return AiAvailability.Available
        // Distinguishing "fetching" from "nothing here" is what lets the coach panel say
        // "downloading" instead of "unavailable" on first launch.
        return if (initJob?.isActive == true) AiAvailability.Downloading else AiAvailability.Unavailable
    }

    /** Starts initialization and returns immediately — the board and the deterministic coach stay
     *  usable while the model downloads. Await [awaitWarmup] if you need the outcome. */
    override suspend fun warmup() {
        startInit()
    }

    /** [warmup] plus joining the download, for callers that report the final state (entry points). */
    suspend fun awaitWarmup() {
        startInit().await()
    }

    // A completed job is reused rather than retried: ensureInitialized() records a hard failure in
    // initializationFailed, and re-attempting a failed 200 MB download on every move is worse than
    // reporting Error once.
    private suspend fun startInit(): Deferred<Unit> = initMutex.withLock {
        initJob ?: initScope.async { ensureInitialized() }.also { initJob = it }
    }

    override fun generate(request: AiGenerationRequest): Flow<AiTokenOrFinal> = flow {
        // Join the shared init rather than calling ensureInitialized() directly — see initJob.
        startInit().await()
        val activeLm = lm ?: run {
            emit(
                failureMetric(
                    AiRoutePolicyDecider.FallbackReason.Other(
                        initializationFailed ?: "Cactus not initialized",
                    ),
                ),
            )
            return@flow
        }

        val start = System.currentTimeMillis()
        val result = try {
            activeLm.generateCompletion(
                messages = listOf(
                    ChatMessage(content = request.systemPrompt, role = "system"),
                    ChatMessage(content = request.userPrompt, role = "user"),
                ),
                params = CactusCompletionParams(
                    temperature = request.temperature,
                    maxTokens = request.maxOutputTokens,
                ),
            )
        } finally {
            // This instance is reused across every move ("keeps the model warm" below), but
            // Cactus's own maintainers flag the native context's session state as fragile across
            // repeated completions (upstream cactus-compute/cactus#572 — session-scoped KV-cache
            // state lives on the same handle as the long-lived model). We hit a real SIGSEGV in
            // GemmaModel::build_attention/CactusGraph::set_input after several successive Move
            // Coach calls, consistent with that cross-call corruption. reset() clears the native
            // context cheaply (no weight/model reload) so each move starts from a clean session.
            activeLm.reset()
        }

        val text = stripModelArtifacts(result?.response.orEmpty())
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

    /**
     * Strip Gemma chat-template artifacts. Cactus surfaces the raw completion, so gemma3-270m's
     * turn markers (`<start_of_turn>` / `<end_of_turn>`) and BOS/EOS tokens leak into `response`
     * verbatim — the coach panel was showing e.g. `The Knight'<end_of_turn>`. Cut everything from
     * the first turn terminator (the answer ends there) and drop any stray special tokens. Plain
     * string ops, no regex, so behavior is identical on every JVM/Android runtime. Mirrors the
     * `<think>`-block strip on the LiteRT-LM path.
     */
    private fun stripModelArtifacts(raw: String): String {
        var s = raw
        for (terminator in TURN_TERMINATORS) {
            val idx = s.indexOf(terminator)
            if (idx >= 0) s = s.substring(0, idx)
        }
        for (token in SPECIAL_TOKENS) s = s.replace(token, "")
        return s.trim()
    }

    /** No-op — keeps the model warm across moves. */
    override suspend fun release() {}

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
        } catch (ce: kotlinx.coroutines.CancellationException) {
            // Must not be recorded as a hard failure: startInit() reuses a *completed* job forever,
            // so treating a cancellation as "init failed" would pin the generator to Error for the
            // rest of the process. downloadModel hops to Dispatchers.IO, which is a real suspension
            // point, so this is reachable rather than theoretical.
            throw ce
        } catch (t: Throwable) {
            initializationFailed = t.message ?: "Cactus init failed"
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
        )
    )

    companion object {
        const val DEFAULT_MODEL = "gemma3-270m"
        const val DEFAULT_CONTEXT_SIZE = 2048

        // Gemma turn terminators — text at/after these is the template boundary, not the answer.
        private val TURN_TERMINATORS = listOf("<end_of_turn>", "<eos>")
        // Special tokens to scrub if they appear inline (e.g. an echoed template prefix).
        private val SPECIAL_TOKENS = listOf(
            "<start_of_turn>model", "<start_of_turn>user", "<start_of_turn>",
            "<bos>", "<eos>", "<end_of_turn>",
        )
    }
}
