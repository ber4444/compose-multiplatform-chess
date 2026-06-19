package com.example.myapplication

import android.content.Context
import co.touchlab.kermit.Logger
import java.io.File
import java.io.FileOutputStream

/**
 * Unpacks the bundled `.litertlm` Gemma model from `assets/models/gemma.litertlm`
 * to `context.filesDir/gemma.litertlm` on first launch, then returns the
 * absolute path for the LiteRT-LM engine. LiteRT-LM's native layer needs a real
 * filesystem path (not an asset URL), and `filesDir` is app-private and stable.
 *
 * Uses streaming copy (8 KB chunks) — NOT `readBytes()` — because the model is
 * ~557 MB and loading it all into a single ByteArray exceeds Android's app heap.
 *
 * Demo behaviour when no model is bundled:
 *  - The asset is absent → returns null → the orchestrator reports
 *    `AiAvailability.Unavailable` → coach panel shows deterministic
 *    fallback text (still demo-able end-to-end).
 *
 * To upgrade the demo to real model output, drop any `.litertlm` Gemma file
 * from https://huggingface.co/litert-community into
 * `app/src/androidMain/assets/models/gemma.litertlm` and rebuild.
 */
object MoveCoachModelAsset {

    private const val ASSET_NAME = "models/gemma.litertlm"
    private const val UNPACKED_NAME = "gemma.litertlm"

    private val logger = Logger.withTag("MoveCoachModelAsset")

    fun ensureUnpacked(context: Context): String? {
        val target = File(context.filesDir, UNPACKED_NAME)
        val marker = File(context.filesDir, "$UNPACKED_NAME.unpacked")

        if (target.exists() && marker.exists()) {
            return target.absolutePath
        }

        return try {
            target.parentFile?.mkdirs()
            // Stream-copy in 8 KB chunks — the model is ~557 MB, far larger than
            // Android's app heap. readBytes() would OOM; copyTo() streams safely.
            context.assets.open(ASSET_NAME).use { input ->
                FileOutputStream(target).use { output ->
                    input.copyTo(output)
                }
            }
            marker.writeText("ok")
            val sizeMb = target.length() / 1_048_576
            logger.i { "Unpacked LiteRT-LM Gemma model ($sizeMb MB) to ${target.absolutePath}" }
            target.absolutePath
        } catch (t: Throwable) {
            logger.w(t) { "Failed to unpack Gemma model from assets/$ASSET_NAME" }
            null
        }
    }
}
