package com.example.ondeviceai

import android.content.Context
import com.cactus.CactusContextInitializer
import com.example.ondeviceai.cactus.CactusTextGenerator

/**
 * Initialize the Cactus native runtime. Must be called from the Activity's
 * onCreate before any CactusLM use. Routed through :onDeviceAi so :app
 * doesn't need a direct Cactus dependency.
 */
fun initializeCactus(context: Context) {
    CactusContextInitializer.initialize(context)
}

/**
 * Android default factory. Uses Cactus (llama.cpp) with a pre-packaged small
 * model (default: gemma3-270m, ~200 MB, ~1-2s cold start). The generator is
 * cached as a singleton so the model stays warm across moves.
 */
actual fun defaultOnDeviceTextGeneratorFactory(): OnDeviceTextGeneratorFactory =
    OnDeviceTextGeneratorFactory {
        cachedGenerator ?: CactusTextGenerator().also { cachedGenerator = it }
    }

@Volatile
private var cachedGenerator: CactusTextGenerator? = null
