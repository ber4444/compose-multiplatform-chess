package com.example.ondeviceai

import com.example.ondeviceai.litert.LiteRtLmTextGenerator

/**
 * Android default factory. The earlier ML Kit Prompt API path (AICore/Gemini
 * Nano) was removed because of AICore's narrow device support; LiteRT-LM with
 * a bundled `.litertlm` Gemma model is now the only Android inference path.
 *
 * The chess app supplies the on-device model path + cache dir through
 * [AndroidCoachWiring] before constructing the orchestrator. Until then the
 * factory returns null and the orchestrator deterministically falls back.
 */
actual fun defaultOnDeviceTextGeneratorFactory(): OnDeviceTextGeneratorFactory =
    OnDeviceTextGeneratorFactory {
        val wiring = AndroidCoachWiring.current ?: return@OnDeviceTextGeneratorFactory null
        if (wiring.modelPath.isBlank()) return@OnDeviceTextGeneratorFactory null
        LiteRtLmTextGenerator(
            pathToModel = wiring.modelPath,
            cacheDir = wiring.cacheDir,
            accelerator = LiteRtLmTextGenerator.Accelerator.GPU_PREFERRED,
        )
    }

/**
 * Set by the chess-app Android entry point before constructing the orchestrator.
 * Holds the path to the unpacked `.litertlm` Gemma model + a writable cacheDir
 * (LiteRT-LM uses it to speed up second-load).
 *
 * The chess app typically unpacks the model from `assets/models/<gemma>.litertlm`
 * to `context.filesDir/<gemma>.litertlm` at first launch and registers the
 * resulting absolute path here. See MainActivity / demo instructions.
 */
object AndroidCoachWiring {
    @Volatile
    var current: Config? = null
        private set

    fun install(config: Config) {
        current = config
    }

    fun clear() {
        current = null
    }

    data class Config(
        val modelPath: String,
        val cacheDir: String?,
    )
}
