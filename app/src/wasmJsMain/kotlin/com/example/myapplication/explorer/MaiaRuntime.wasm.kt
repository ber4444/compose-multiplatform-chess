package com.example.myapplication.explorer

import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * The web build runs the LiteRT export through LiteRT.js on its WebAssembly (XNNPACK) CPU backend.
 * No WebGPU is needed, so every current browser qualifies. Checked in Chrome against PyTorch: max
 * legal-move logit difference 9.5e-06, about 10 ms per inference.
 *
 * Unlike the other platforms, **web does not bundle the model**. It downloads the same 5.9 MB int8
 * file Android and iOS bundle from the `ber4444/maia3-5m-litert` Hugging Face repo, pinned to a
 * commit so the URL is immutable, and checks the bytes against [MaiaModelFiles.WEB_TFLITE_SHA256]
 * before LiteRT.js sees them. The float file it was quantized from stays in that repo, because older
 * web builds pin the revision that added it. Serving the model per visitor from the app's own Fly.io
 * host would be billed as egress; Hugging Face serves it at no cost and allows the cross-origin
 * request. LiteRT.js itself loads from jsDelivr, the way `filament.js` does from unpkg. Nothing is
 * fetched until Maia is first asked.
 *
 * Offline, or if the CDN is unreachable, opening fails. The History explorer then falls back to
 * Lichess or says there is no answer, and the opponent falls back to Stockfish.
 */
internal actual fun maiaRuntimeFactory(): (suspend () -> MaiaRuntime)? = {
    LiteRtJsMaiaRuntime.open(MODEL_URL, MaiaModelFiles.WEB_TFLITE_SHA256)
}

/** The Hugging Face commit that added the file. A `resolve/<commit>/` URL never changes content. */
private const val MODEL_REVISION = "76710ab522d2724eff302be0bc49e81db3d4ab92"
private const val MODEL_URL =
    "https://huggingface.co/ber4444/maia3-5m-litert/resolve/$MODEL_REVISION/${MaiaModelFiles.WEB_TFLITE_NAME}"

internal class LiteRtJsMaiaRuntime private constructor(private val handle: JsAny) : MaiaRuntime {
    override suspend fun moveLogits(tokens: FloatArray, rating: Int): FloatArray {
        require(tokens.size == MaiaEncoder.SQUARES * MaiaEncoder.WIDTH) { "Bad token tensor: ${tokens.size}" }
        // Maia's inputs are all 0 or 1, so a '0'/'1' string crosses the Wasm boundary in one call.
        val bits = CharArray(tokens.size) { if (tokens[it] != 0f) '1' else '0' }.concatToString()
        val logits = suspendCancellableCoroutine { continuation ->
            runMaia(handle, bits, rating) { result, error ->
                if (!continuation.isActive) return@runMaia
                if (result == null) continuation.resumeWithException(IllegalStateException("LiteRT.js inference failed: $error"))
                else continuation.resume(result)
            }
        }
        check(floatArrayLength(logits) == MaiaEncoder.VOCAB) { "Unexpected Maia output size" }
        return FloatArray(MaiaEncoder.VOCAB) { floatAt(logits, it) }
    }

    override fun close() = closeMaia(handle)

    companion object {
        suspend fun open(modelUrl: String, sha256: String): LiteRtJsMaiaRuntime = suspendCancellableCoroutine { continuation ->
            openMaia(LITERT_JS_CDN, modelUrl, sha256) { handle, error ->
                if (!continuation.isActive) return@openMaia
                if (handle == null) continuation.resumeWithException(IllegalStateException("LiteRT.js could not load Maia: $error"))
                else continuation.resume(LiteRtJsMaiaRuntime(handle))
            }
        }
    }
}

private const val LITERT_JS_CDN = "https://cdn.jsdelivr.net/npm/@litertjs/core@2.6.0"

// The runtime module is loaded once per page; a failed load is forgotten so a later try can retry.
// The model bytes are hashed before LiteRT.js sees them, so a wrong or tampered file never runs.
@JsFun("""(cdn, url, sha256, done) => {
    if (!globalThis.__maiaLiteRt) {
        // webpackIgnore: without it webpack rewrites this into a bundled-module lookup that fails.
        globalThis.__maiaLiteRt = import(/* webpackIgnore: true */ cdn + '/+esm')
            .then(m => m.loadLiteRt(cdn + '/wasm/').then(() => m))
            .catch(e => { globalThis.__maiaLiteRt = null; throw e; });
    }
    const bytes = fetch(url).then(r => {
        if (!r.ok) throw new Error('model download failed: HTTP ' + r.status);
        return r.arrayBuffer();
    }).then(buffer => crypto.subtle.digest('SHA-256', buffer).then(digest => {
        const hex = Array.from(new Uint8Array(digest), b => b.toString(16).padStart(2, '0')).join('');
        if (hex !== sha256) throw new Error('model SHA-256 mismatch: ' + hex);
        return new Uint8Array(buffer);
    }));
    Promise.all([globalThis.__maiaLiteRt, bytes])
        .then(([m, model]) => m.loadAndCompile(model, { accelerator: 'wasm' }).then(compiled => done({ m: m, model: compiled }, '')))
        .catch(e => done(null, String((e && e.message) || e)));
}""")
private external fun openMaia(cdn: String, url: String, sha256: String, done: (JsAny?, String) -> Unit)

@JsFun("""(handle, bits, elo, done) => {
    const m = handle.m;
    const tokens = new Float32Array(bits.length);
    for (let i = 0; i < bits.length; i++) tokens[i] = bits.charCodeAt(i) === 49 ? 1 : 0;
    const inputs = [new m.Tensor(tokens, [1, 64, 97]), new m.Tensor(new Int32Array([elo]), [1]), new m.Tensor(new Int32Array([elo]), [1])];
    const release = () => { for (const t of inputs) { try { t.delete(); } catch (e) {} } };
    handle.model.run(inputs).then(async results => {
        const out = await results[0].moveTo('wasm');
        const logits = out.toTypedArray().slice();
        release();
        try { out.delete(); } catch (e) {}
        try { for (const r of results) r.delete(); } catch (e) {}
        done(logits, '');
    }).catch(e => { release(); done(null, String((e && e.message) || e)); });
}""")
private external fun runMaia(handle: JsAny, bits: String, elo: Int, done: (JsAny?, String) -> Unit)

@JsFun("(a) => a.length")
private external fun floatArrayLength(a: JsAny): Int

@JsFun("(a, i) => a[i]")
private external fun floatAt(a: JsAny, i: Int): Float

@JsFun("(handle) => { try { handle.model.delete(); } catch (e) {} }")
private external fun closeMaia(handle: JsAny)
