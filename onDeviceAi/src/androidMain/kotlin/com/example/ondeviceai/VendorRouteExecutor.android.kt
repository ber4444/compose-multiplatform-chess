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
    cactusInitialized = true
}

@Volatile
private var cactusInitialized = false

internal fun isCactusInitialized(): Boolean = cactusInitialized

actual class VendorRouteExecutor : AiRouteExecutor {
    actual override suspend fun execute(route: VendorRoute): OnDeviceTextGenerator? {
        return when (route) {
            is VendorRoute.MlKitPrompt -> MlKitPromptGenerator(route.preference)
            is VendorRoute.AppleFoundationModels -> error("iOS route on Android")
            is VendorRoute.LiteRtLm -> error("Desktop/Wasm route on Android")
            is VendorRoute.CactusLocal -> getCactus()
        }
    }

    private fun getCactus(): CactusTextGenerator {
        check(isCactusInitialized()) {
            "Cactus Native context not initialized in Application/Activity"
        }
        return cachedGenerator ?: CactusTextGenerator().also { cachedGenerator = it }
    }
}

@Volatile
private var cachedGenerator: CactusTextGenerator? = null

