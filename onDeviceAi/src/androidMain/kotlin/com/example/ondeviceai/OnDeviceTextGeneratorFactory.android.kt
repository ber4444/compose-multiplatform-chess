package com.example.ondeviceai

import com.example.ondeviceai.litert.LiteRtLmTextGenerator

/**
 * Android default factory. The earlier ML Kit Prompt API path (AICore/Gemini
 * Nano) was removed because of AICore's narrow device support; LiteRT-LM with
 * a bundled `.litertlm` Gemma model is now the only Android inference path.
 *
 * The generator is **cached as a singleton** — LiteRT-LM's `Engine.initialize()`
 * loads the ~557 MB model from disk and takes 2-10 seconds. Creating a new
 * engine per coached move would re-load the model every time, exceed the
 * latency budget, and OOM after a few moves (multiple model copies in memory).
 * The cached engine stays warm across moves; `close()` on LiteRtLmTextGenerator
 * is a no-op to preserve the warm engine.
 */
actual fun defaultOnDeviceTextGeneratorFactory(): OnDeviceTextGeneratorFactory =
    OnDeviceTextGeneratorFactory {
        val wiring = AndroidCoachWiring.current ?: return@OnDeviceTextGeneratorFactory null
        if (wiring.modelPath.isBlank()) return@OnDeviceTextGeneratorFactory null
        cachedGenerator ?: LiteRtLmTextGenerator(
            pathToModel = wiring.modelPath,
            cacheDir = wiring.cacheDir,
            accelerator = LiteRtLmTextGenerator.Accelerator.GPU_PREFERRED,
        ).also { cachedGenerator = it }
    }

@Volatile
private var cachedGenerator: LiteRtLmTextGenerator? = null

/**
 * Set by the chess-app Android entry point before constructing the orchestrator.
 * Holds the path to the unpacked `.litertlm` Gemma model + a writable cacheDir.
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
