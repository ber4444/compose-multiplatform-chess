package com.example.ondeviceai

import com.example.ondeviceai.litertlm.LitertLmWasmTextGenerator

/**
 * Wasm/JS default factory. Uses LiteRT-LM for Web (`@litert-lm/core`, loaded from
 * the jsdelivr CDN at runtime) running inside a module Web Worker, so inference is
 * off the main thread. The model (Qwen3-0.6B-int4 `.litertlm`, ~347 MB) is streamed
 * from Hugging Face by the LiteRT-LM `Engine.create()` call — no model is bundled
 * into the webpack distribution.
 *
 * Cached as a singleton so the worker + engine stay warm across moves — mirrors
 * the Android/desktop factories.
 *
 * Requires **WebGPU** (`navigator.gpu`); on browsers without it the generator's
 * `status()` returns [AiAvailability.Unavailable] *without* any network fetch,
 * and the orchestrator falls back to [MoveCoachFallback]. This is the graceful
 * degradation path for Firefox/Safari.
 */
actual fun defaultOnDeviceTextGeneratorFactory(): OnDeviceTextGeneratorFactory =
    OnDeviceTextGeneratorFactory {
        cachedGenerator ?: LitertLmWasmTextGenerator().also { cachedGenerator = it }
    }

// wasmJs is single-threaded — no @Volatile needed.
private var cachedGenerator: LitertLmWasmTextGenerator? = null
