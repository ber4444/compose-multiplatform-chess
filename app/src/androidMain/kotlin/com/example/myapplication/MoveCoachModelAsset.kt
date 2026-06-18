package com.example.myapplication

import android.content.Context
import co.touchlab.kermit.Logger
import java.io.File

/**
 * Unpacks the bundled `.litertlm` Gemma model from `assets/models/gemma.litertlm`
 * to `context.filesDir/gemma.litertlm` on first launch, then returns the
 * absolute path for the LiteRT-LM engine. LiteRT-LM's native layer needs a real
 * filesystem path (not an asset URL), and `filesDir` is app-private and stable.
 *
 * Demo behaviour when no model is bundled:
 *  - The asset is absent → returns null → the orchestrator reports
 *    `AiAvailability.Unavailable` → coach panel mounts with deterministic
 *    fallback text (still demo-able end-to-end).
 *
 * To upgrade the demo to real model output, drop any `.litertlm` Gemma file
 * from https://huggingface.co/litert-community into
 * `app/src/androidMain/assets/models/gemma.litertlm` and rebuild. Tested against
 * `Gemma3-1B-IT` CPU/GPU builds (≈700 MB–1.7 GB depending on quantization).
 * Per-SoC NPU-specific artifacts (e.g. `_q8_ekv1280_Google_Tensor_G5.litertlm`)
 * also work but require the vendor dispatch library — see plan §6.1.1.
 */
object MoveCoachModelAsset {

    private const val ASSET_NAME = "models/gemma.litertlm"
    private const val UNPACKED_NAME = "gemma.litertlm"

    private val logger = Logger.withTag("MoveCoachModelAsset")

    /**
     * Returns the absolute path to the unpacked `.litertlm` model, or null if
     * no model is bundled in assets. Idempotent: a marker file is written next
     * to the unpacked model so we skip the copy on subsequent launches.
     */
    fun ensureUnpacked(context: Context): String? {
        val target = File(context.filesDir, UNPACKED_NAME)
        val marker = File(context.filesDir, "$UNPACKED_NAME.unpacked")

        if (target.exists() && marker.exists()) {
            return target.absolutePath
        }

        val assetBytes = runCatching {
            context.assets.open(ASSET_NAME).use { it.readBytes() }
        }.getOrNull()

        if (assetBytes == null) {
            logger.i { "No bundled Gemma model at assets/$ASSET_NAME — coach will fall back to deterministic text" }
            return null
        }

        return try {
            target.parentFile?.mkdirs()
            target.writeBytes(assetBytes)
            marker.writeText("ok")
            logger.i { "Unpacked LiteRT-LM Gemma model (${assetBytes.size / 1_048_576} MB) to ${target.absolutePath}" }
            target.absolutePath
        } catch (t: Throwable) {
            logger.w(t) { "Failed to unpack Gemma model" }
            null
        }
    }
}
