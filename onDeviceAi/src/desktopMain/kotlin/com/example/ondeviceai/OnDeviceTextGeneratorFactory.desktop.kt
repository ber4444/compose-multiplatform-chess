package com.example.ondeviceai

import com.example.ondeviceai.litertlm.LitertLmModelStore
import com.example.ondeviceai.litertlm.LitertLmTextGenerator

/**
 * Desktop default factory. Uses LiteRT-LM (`litertlm-jvm`) with a model file
 * that [LitertLmModelStore] downloads from Hugging Face on first launch
 * (default: Qwen3-0.6B-int4, ~347 MB) and caches under `~/.chess-coach-models/`.
 *
 * The generator is cached as a singleton so the model stays warm across moves
 * — mirrors [com.example.ondeviceai.cactus.CactusTextGenerator] on Android.
 *
 * On unsupported hosts (Intel Mac — `litertlm-jvm` ships no darwin-x86_64
 * native lib) [LitertLmTextGenerator.ensureInitialized] captures the
 * `UnsatisfiedLinkError` and `status()` reports [AiAvailability.Error], so the
 * orchestrator falls back to [MoveCoachFallback]. The factory itself still
 * returns a generator instance rather than `UnsupportedTextGenerator`, so the
 * first-launch model download is attempted once and its failure is surfaced
 * to the UI as a coach-panel error rather than silently hiding the panel.
 */
actual fun defaultOnDeviceTextGeneratorFactory(): OnDeviceTextGeneratorFactory =
    OnDeviceTextGeneratorFactory {
        cachedGenerator ?: LitertLmTextGenerator(
            modelPath = LitertLmModelStore.modelFile().absolutePath,
        ).also { cachedGenerator = it }
    }

@Volatile
private var cachedGenerator: LitertLmTextGenerator? = null
