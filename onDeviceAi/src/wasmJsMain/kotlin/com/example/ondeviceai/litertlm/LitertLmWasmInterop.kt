package com.example.ondeviceai.litertlm

// ── JS interop for LitertLmWasmTextGenerator ─────────────────────────────────
// All JS-facing calls live here so the generator stays readable. Each helper is
// either a `@JsFun` external (typed) or a `js("...")` expression (untyped).
// wasmJs is single-threaded, so there's no dispatcher customization here — the
// generator drains its Channel on whatever coroutine calls it.

/**
 * A module Web Worker. Distinct from the classic `external class Worker(scriptURL:
 * String)` used by `WorkerUciTransport` because `@litert-lm/core` is an ES module
 * and must be loaded in a module worker (`new Worker(url, { type: "module" })`,
 * which lets the worker use top-level `import`). Communication is
 * `postMessage(JsString)` with JSON payloads — same on-the-wire convention as the
 * Stockfish worker.
 */
external interface JsWorker : JsAny {
    fun postMessage(message: JsString)
    fun terminate()
    var onmessage: (JsWorkerMessageEvent) -> Unit
}

external interface JsWorkerMessageEvent : JsAny {
    val data: JsAny?
}

/**
 * Build a module worker from an embedded JS source string: wrap the source in a
 * Blob, turn it into an object URL, then spawn `new Worker(url, { type: 'module' })`.
 * This keeps the worker script inside the `:onDeviceAi` published artifact (no
 * static `.js` asset, no webpack wiring) — mirrors how `FilamentWasmChessRenderer`
 * injects its CDN `<script>` + glue at runtime.
 */
@JsFun(
    "(code) => {" +
        "  const url = URL.createObjectURL(new Blob([code], { type: 'text/javascript' }));" +
        "  return new Worker(url, { type: 'module' });" +
        "}",
)
external fun createModuleWorkerFromBlob(code: String): JsWorker

/** True iff `navigator.gpu` exists (the WebGPU entry point @litert-lm/core needs). */
private fun isWebGpuAvailable(): Boolean =
    js("(typeof navigator !== 'undefined' && !!navigator.gpu)")

internal fun isWebGpuAvailableWrapper(): Boolean = isWebGpuAvailable()

/** `Date.now()` — same wall-clock the wasm persistence `Clock` actual uses. */
private fun jsNowMs(): Double = js("Date.now()")
internal fun nowMs(): Long = jsNowMs().toLong()

// ── tiny JSON builders (no kotlinx-serialization on this path — payloads are tiny) ──

/**
 * Build a flat JSON object string from key/value pairs. Values are already
 * strings (callers `.toString()` numbers). Keys/values are JSON-escaped.
 */
internal fun buildJson(vararg pairs: Pair<String, String>): String {
    val sb = StringBuilder("{")
    pairs.forEachIndexed { i, (k, v) ->
        if (i > 0) sb.append(',')
        sb.append(jsonString(k)).append(':').append(jsonString(v))
    }
    sb.append('}')
    return sb.toString()
}

/** Extract a string field from a JSON object string (handles simple escaped values). */
internal fun jsonStringField(json: String, key: String): String? {
    val needle = jsonString(key)
    val start = json.indexOf(needle)
    if (start < 0) return null
    // The value starts after `needle:` — find the opening quote of the string value.
    var i = start + needle.length
    while (i < json.length && json[i] != ':') i++
    if (i >= json.length) return null
    i++ // skip ':'
    while (i < json.length && json[i].isWhitespace()) i++
    if (i >= json.length || json[i] != '"') return null
    i++ // skip opening quote
    val out = StringBuilder()
    while (i < json.length) {
        val c = json[i]
        when {
            c == '\\' && i + 1 < json.length -> {
                out.append(json[i + 1])
                i += 2
            }
            c == '"' -> return out.toString()
            else -> { out.append(c); i++ }
        }
    }
    return null
}

private fun jsonString(s: String): String {
    val sb = StringBuilder("\"")
    s.forEach { c ->
        when (c) {
            '\\' -> sb.append("\\\\")
            '"' -> sb.append("\\\"")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            else -> sb.append(c)
        }
    }
    sb.append('"')
    return sb.toString()
}

/**
 * The module-worker source. Imports `@litert-lm/core` from the CDN, lazily creates
 * an `Engine` on first `status`/`generate`, and replies with JSON-over-postMessage.
 *
 * Kept as plain JS (not a Kotlin/JS compile target) so it can be embedded as a
 * string and spawned from a Blob URL without any webpack/resource wiring. The
 * `${litertLmModuleUrl}` substitution is the only interpolation — everything else
 * is static.
 */
internal fun workerScript(litertLmModuleUrl: String): String = """
import { Engine } from '$litertLmModuleUrl';

let engine = null;
let conversation = null;
let initializing = null;

async function ensureEngine(modelUrl) {
  if (engine) return engine;
  if (initializing) return initializing;
  initializing = (async () => {
    if (!navigator.gpu) throw new Error('WebGPU unavailable in this browser');
    engine = await Engine.create({ model: modelUrl });
    return engine;
  })();
  try {
    return await initializing;
  } finally {
    initializing = null;
  }
}

self.onmessage = async (e) => {
  let msg;
  try { msg = JSON.parse(e.data); } catch (err) {
    self.postMessage(JSON.stringify({ type: 'error', message: 'bad message: ' + err.message }));
    return;
  }
  try {
    if (msg.type === 'status') {
      await ensureEngine(msg.modelUrl);
      self.postMessage(JSON.stringify({ type: 'status', status: 'available' }));
      return;
    }
    if (msg.type === 'generate') {
      await ensureEngine(msg.modelUrl);
      // LiteRT-LM JS conversation API: the system instruction is part of the
      // conversation config (`preface.messages`), not a separate setSystemPrompt
      // call. A fresh conversation per generate() keeps move-coach turns independent.
      // sessionConfig.samplerParams only exposes temperature/topK/topP/seed (checked
      // against @litert-lm/core@0.14.0's SamplerParameters — no repetition/frequency
      // penalty field exists there), so temperature is the only sampler knob this
      // request can actually carry through.
      const conversationConfig = {
        sessionConfig: {
          samplerParams: { temperature: Number(msg.temperature) },
          maxOutputTokens: Number(msg.maxTokens),
        },
      };
      if (msg.systemPrompt) {
        conversationConfig.preface = { messages: [{ role: 'system', content: msg.systemPrompt }] };
      }
      conversation = await engine.createConversation(conversationConfig);
      let first = true;
      for await (const chunk of conversation.sendMessageStreaming(msg.userPrompt)) {
        const text = (chunk && chunk.content)
          ? chunk.content.filter(c => c.type === 'text').map(c => c.text).join('')
          : '';
        if (text) {
          self.postMessage(JSON.stringify({ type: 'token', text: text, first: first }));
          first = false;
        }
      }
      self.postMessage(JSON.stringify({ type: 'final' }));
      return;
    }
    self.postMessage(JSON.stringify({ type: 'error', message: 'unknown message type: ' + msg.type }));
  } catch (err) {
    self.postMessage(JSON.stringify({ type: 'error', message: (err && err.message) || String(err) }));
  }
};
""".trimIndent()
