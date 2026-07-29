package com.example.ondeviceai.litertlm

import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Downloads + caches the LiteRT-LM model file (`.litertlm`) used by
 * [LitertLmTextGenerator] on desktop.
 *
 * Mirrors Cactus's `downloadModel` / `initializeModel` split on Android: the
 * model is fetched from Hugging Face on first launch and cached under
 * `~/.chess-coach-models/`, so subsequent launches load from disk
 * (~1-2s `Engine()` construction) instead of re-downloading.
 *
 * Default model is `Qwen3-0.6B-int4` (~347 MB, q4 quantized). This is used
 * instead of the originally-planned `gemma3-270m` because the
 * `litert-community/gemma-3-270m-it` repo became Hugging Face license-gated
 * (401 on the weights) after this feature was scoped, so it can no longer be
 * auto-downloaded without bundling credentials. `Qwen3-0.6B-int4` is publicly
 * downloadable, in the same `.litertlm` format that `litertlm-jvm` consumes,
 * and a similar size/quality tier.
 *
 * The `.part` temp file + atomic rename guards against a half-written cache
 * if the download is interrupted (the JVM killed, network dropped). A file
 * smaller than [MIN_VALID_BYTES] is treated as incomplete and re-downloaded.
 */
object LitertLmModelStore {

    /**
     * Override the model source via system property `chess.coach.modelUrl`
     * (e.g. to point at a self-hosted mirror or a different `.litertlm`).
     * Falls back to [DEFAULT_MODEL_URL].
     */
    private const val DEFAULT_MODEL_URL =
        "https://huggingface.co/litert-community/Qwen3-0.6B-int4/resolve/main/qwen3_0.6b_q4_block32_ekv1280.litertlm"
    private const val DEFAULT_MODEL_FILENAME = "qwen3_0.6b_q4_block32_ekv1280.litertlm"

    /** Re-download if the cached file is smaller than this (guards partial/corrupt downloads). */
    private const val MIN_VALID_BYTES = 10L * 1024 * 1024 // 10 MB

    val modelUrl: String =
        System.getProperty("chess.coach.modelUrl")?.takeIf { it.isNotBlank() } ?: DEFAULT_MODEL_URL

    val modelFilename: String = modelUrl.substringAfterLast('/').ifBlank { DEFAULT_MODEL_FILENAME }

    fun cacheDir(): File = File(System.getProperty("user.home"), ".chess-coach-models")

    fun modelFile(): File = File(cacheDir(), modelFilename)

    fun isDownloaded(): Boolean = modelFile().let { it.exists() && it.length() >= MIN_VALID_BYTES }

    /**
     * Blocking streaming download with progress. Follows HF's 302 to the CDN.
     * Call from an IO dispatcher (the generator wraps this in `withContext`).
     *
     * @param onProgress called with a 0..1 fraction (based on Content-Length);
     *                   called on the downloading thread, not the UI thread.
     */
    fun download(onProgress: (Float) -> Unit = {}) {
        if (isDownloaded()) {
            onProgress(1f)
            return
        }
        cacheDir().mkdirs()
        val target = modelFile()
        val tmp = File(target.parentFile, "${target.name}.part")

        var conn: HttpURLConnection? = null
        try {
            var currentUrl = modelUrl
            var redirects = 0
            while (true) {
                conn = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = false
                    connectTimeout = 30_000
                    readTimeout = 60_000
                    // HF serves the CDN URL with a UA check; use a browser-like UA to be safe.
                    setRequestProperty("User-Agent", "compose-multiplatform-chess/1.0 (desktop coach)")
                }
                val status = conn.responseCode
                if (status == HttpURLConnection.HTTP_MOVED_TEMP ||
                    status == HttpURLConnection.HTTP_MOVED_PERM ||
                    status == HttpURLConnection.HTTP_SEE_OTHER) {
                    val location = conn.getHeaderField("Location")
                    conn.disconnect()
                    currentUrl = location ?: error("Redirect missing Location header")
                    redirects++
                    if (redirects > 5) error("Too many redirects")
                    continue
                }
                if (status !in 200..299) {
                    error("Failed to download model: HTTP $status")
                }
                break
            }
            val total = conn!!.contentLengthLong.takeIf { it > 0 } ?: -1L
            conn.inputStream.use { input ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var read: Int
                    var seen = 0L
                    var lastReported = -1L
                    while (true) {
                        read = input.read(buf)
                        if (read <= 0) break
                        out.write(buf, 0, read)
                        seen += read
                        // Report ~1% granularity to avoid flooding the UI thread.
                        if (total > 0) {
                            val pct = (seen * 100 / total)
                            if (pct != lastReported) {
                                onProgress(pct.toFloat() / 100f)
                                lastReported = pct
                            }
                        } else if (seen - lastReported > 5_000_000) {
                            // No Content-Length: report progress in 5MB chunks, fraction unknown.
                            onProgress(-1f)
                            lastReported = seen
                        }
                    }
                    out.flush()
                }
            }
            if (tmp.length() < MIN_VALID_BYTES) {
                error("Downloaded model is too small (${tmp.length()} bytes); URL may be wrong: $modelUrl")
            }
            // Atomic swap so a crash mid-rename never leaves a corrupt cache.
            if (target.exists()) target.delete()
            if (!tmp.renameTo(target)) {
                Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            }
            onProgress(1f)
        } finally {
            conn?.disconnect()
            if (tmp.exists()) tmp.delete()
        }
    }
}
