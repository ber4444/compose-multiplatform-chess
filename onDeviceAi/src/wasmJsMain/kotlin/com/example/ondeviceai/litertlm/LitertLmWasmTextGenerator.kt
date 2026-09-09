package com.example.ondeviceai.litertlm

import com.example.ondeviceai.AiAvailability
import com.example.ondeviceai.AiGenerationRequest
import com.example.ondeviceai.AiInferenceMetrics
import com.example.ondeviceai.AiRoute
import com.example.ondeviceai.AiRoutePolicyDecider
import com.example.ondeviceai.AiTokenOrFinal
import com.example.ondeviceai.OnDeviceTextGenerator
import com.example.ondeviceai.withAntiRepetitionGuard
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Wasm/JS [OnDeviceTextGenerator] backed by LiteRT-LM for Web (`@litert-lm/core`,
 * loaded from the jsdelivr CDN at runtime).
 *
 * Mirrors [com.example.ondeviceai.litertlm.LitertLmTextGenerator] on desktop and
 * the Cactus generator Android used to carry (since removed): same
 * [OnDeviceTextGenerator] contract, same single-call-per-generate semantics,
 * same no-op [close] (keeps the worker/engine warm across moves).
 *
 * ## Architecture
 *
 * Inference runs entirely off the main thread in a **Web Worker** (so the UI never
 * stalls during a multi-second generation — exactly why the Stockfish engine runs
 * in a worker too; see `WorkerUciTransport`). The worker is a **module worker**
 * (`new Worker(url, { type: "module" })`) so it can `import` `@litert-lm/core`
 * from the CDN. The worker script is built from a Blob URL at runtime (via
 * [createModuleWorkerFromBlob]) rather than shipped as a static asset — this keeps
 * the generator self-contained inside the `:onDeviceAi` published artifact and
 * avoids any webpack/resource-packaging changes, mirroring how
 * `FilamentWasmChessRenderer` injects its CDN `<script>` + glue at runtime.
 *
 * On the wire we use JSON-over-`postMessage(JsString)` (same convention as the
 * Stockfish worker's string protocol — Kotlin/Wasm `postMessage` only takes a
 * `JsString` cheaply, and structured-clone of `JsAny` payloads is fiddly).
 *
 * ## Graceful degradation
 *
 * `@litert-lm/core` requires **WebGPU** (`navigator.gpu`). On browsers without it
 * (Firefox/Safari without the flag, or hardware that lacks WebGPU) [status]
 * returns [AiAvailability.Unavailable] *without* touching the network, and the
 * orchestrator falls back to [com.example.ondeviceai.MoveCoachFallback] — the
 * same deterministic path Desktop/Android used before this backend landed.
 *
 * The model is fetched from Hugging Face directly by the LiteRT-LM Engine inside
 * the worker (the `Engine.create({ model: <url> })` call accepts a URL string and
 * streams the `.litertlm` file). No model is bundled into the webpack dist.
 */
class LitertLmWasmTextGenerator(
    private val modelUrl: String = DEFAULT_MODEL_URL,
    private val litertLmModuleUrl: String = DEFAULT_LITERTLM_MODULE_URL,
) : OnDeviceTextGenerator {

    /**
     * One pending request at a time. Each `generate()` call drains this channel
     * for worker messages (tokens / final / error) until it sees `final`.
     */
    /**
     * Generation traffic only. Status replies go to [statusInbox].
     *
     * These used to be one channel with two readers: `generate()`'s loop and `requestStatus()`.
     * `generate()` already discarded any `Status` that landed in it — the tell that messages could
     * reach the wrong reader — but the converse was unguarded, so a `status()` call overlapping a
     * generation could consume that generation's `Token` or `Final` and hang it until its timeout.
     * The class comment claimed the orchestrator serialises calls; it serialises *generations*, and
     * `status()` is called from entry-point code, not the orchestrator. Single-threaded wasmJs does
     * not help — these are coroutines interleaving at suspension points, not threads.
     */
    private val inbox = Channel<WorkerMsg>(Channel.UNLIMITED)
    private val statusInbox = Channel<WorkerMsg.Status>(Channel.UNLIMITED)

    // wasmJs is single-threaded — no @Volatile / dispatcher needed. At most one
    // generate() call is active at a time (the orchestrator serializes them).
    private var worker: JsWorker? = null
    private var statusCache: AiAvailability? = null

    override suspend fun status(): AiAvailability {
        statusCache?.let { return it }
        if (!isWebGpuAvailableWrapper()) {
            statusCache = AiAvailability.Unavailable
            return AiAvailability.Unavailable
        }
        return when (val r = requestStatus()) {
            StatusResult.Available -> AiAvailability.Available
            is StatusResult.Error -> AiAvailability.Error(r.message)
            null -> AiAvailability.Error("LiteRT-LM worker did not respond")
        }
    }

    override suspend fun warmup() {
        status()
    }

    override fun generate(request: AiGenerationRequest): Flow<AiTokenOrFinal> = flow {
        ensureWorker()
        val started = nowMs()
        val req = buildJson(
            "type" to "generate",
            "systemPrompt" to request.systemPrompt,
            "userPrompt" to request.userPrompt,
            "maxTokens" to request.maxOutputTokens.toString(),
            "temperature" to request.temperature.toString(),
        )
        worker?.postMessage(req.toJsString())

        val output = StringBuilder()
        var firstTokenMs: Long? = null
        while (true) {
            val msg = inbox.receive()
            when (msg) {
                is WorkerMsg.Token -> {
                    if (firstTokenMs == null) firstTokenMs = nowMs() - started
                    output.append(msg.text)
                }
                is WorkerMsg.Final -> {
                    if (output.isNotEmpty()) {
                        emit(AiTokenOrFinal.Token(output.toString()))
                    }
                    emit(
                        AiTokenOrFinal.Final(
                            text = "",
                            metrics = AiInferenceMetrics(
                                firstTokenMs = firstTokenMs,
                                completeMs = nowMs() - started,
                                tokenCount = maxOf(1, output.length / 4),
                                route = AiRoute.OnDevice,
                            ),
                        ),
                    )
                    return@flow
                }
                is WorkerMsg.Error -> {
                    emit(
                        AiTokenOrFinal.Final(
                            text = "",
                            metrics = AiInferenceMetrics(
                                firstTokenMs = null,
                                completeMs = nowMs() - started,
                                tokenCount = 0,
                                route = AiRoute.OnDevice,
                                fallbackReason = AiRoutePolicyDecider.FallbackReason.Other(msg.message),
                            ),
                        ),
                    )
                    return@flow
                }
                // Unreachable since onWorkerMessage routes Status to statusInbox; kept so the `when`
                // stays exhaustive without an else arm.
                is WorkerMsg.Status -> Unit
            }
        }
    }.withAntiRepetitionGuard(
        ngramSize = request.noRepeatNgramSize,
        stopSequences = request.stopSequences,
    )

    /** No-op — keeps the worker + model warm across moves (mirrors desktop/Android). */
    override suspend fun release() {}

    // ── internals ──────────────────────────────────────────────────────────────

    private suspend fun requestStatus(): StatusResult? {
        ensureWorker()
        worker?.postMessage(buildJson("type" to "status", "modelUrl" to modelUrl).toJsString())
        // Wait for the worker's status reply (or error). A null return means the
        // worker never replied — treated as an error by status().
        return when (val status = statusInbox.receive().status) {
            "available" -> StatusResult.Available
            "unavailable" -> StatusResult.Error("WebGPU unavailable")
            else -> StatusResult.Error(status)
        }
    }

    private fun ensureWorker() {
        if (worker != null) return
        val script = workerScript(litertLmModuleUrl)
        val w = createModuleWorkerFromBlob(script)
        w.onmessage = ::onWorkerMessage
        worker = w
    }

    /** Worker message pump. Non-suspending (Channel.trySend) — safe from a JS callback. */
    private fun onWorkerMessage(event: JsWorkerMessageEvent) {
        val raw = event.data?.toString() ?: return
        val parsed = parseWorkerMessage(raw) ?: return
        // Route by kind so a status reply can never be consumed by an in-flight generation, or
        // vice versa. Errors go to both: either reader may be the one waiting.
        when (parsed) {
            is WorkerMsg.Status -> statusInbox.trySend(parsed)
            is WorkerMsg.Error -> {
                statusInbox.trySend(WorkerMsg.Status(parsed.message))
                inbox.trySend(parsed)
            }
            else -> inbox.trySend(parsed)
        }
    }

    companion object {
        // The model `@litert-lm/core` officially documents for web (one of only two
        // in its supported set — see the package README): gemma-4-E2B-it-web.litertlm
        // (~2 GB, publicly downloadable from litert-community). Larger than the desktop
        // backend's Qwen3-0.6B (~347 MB), but correctness wins over size for an opt-in
        // feature, and `@litert-lm/core` only verifies the Gemma 4 `-web.litertlm`
        // files — non-documented models are not guaranteed to load on WebGPU.
        const val DEFAULT_MODEL_URL =
            "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it-web.litertlm"

        // @litert-lm/core (LiteRT-LM for Web) on the jsdelivr ESM CDN.
        const val DEFAULT_LITERTLM_MODULE_URL =
            "https://cdn.jsdelivr.net/npm/@litert-lm/core@0.14.0/+esm"
    }
}

// ── sealed results / messages ────────────────────────────────────────────────

private sealed interface StatusResult {
    data object Available : StatusResult
    data class Error(val message: String) : StatusResult
}

private sealed interface WorkerMsg {
    data class Status(val status: String) : WorkerMsg
    data class Token(val text: String) : WorkerMsg
    data class Final(val metrics: String?) : WorkerMsg
    data class Error(val message: String) : WorkerMsg
}

/**
 * Parse the worker's JSON message envelope. The worker posts strings of the form
 * `{"type":"token","text":"..."}` / `{"type":"final"}` / `{"type":"status",...}`
 * / `{"type":"error","message":"..."}`. Falls back to raw-text-as-token for
 * robustness if the worker ever posts a non-JSON string (it shouldn't).
 */
private fun parseWorkerMessage(raw: String): WorkerMsg? {
    val type = jsonStringField(raw, "type") ?: return null
    return when (type) {
        "status" -> WorkerMsg.Status(jsonStringField(raw, "status") ?: "unknown")
        "token" -> WorkerMsg.Token(jsonStringField(raw, "text") ?: "")
        "final" -> WorkerMsg.Final(jsonStringField(raw, "metrics"))
        "error" -> WorkerMsg.Error(jsonStringField(raw, "message") ?: "LiteRT-LM worker error")
        else -> null
    }
}
