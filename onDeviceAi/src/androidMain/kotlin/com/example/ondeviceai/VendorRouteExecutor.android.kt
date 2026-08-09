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
            // Cached per preference, mirroring the Cactus route. A fresh instance per execute()
            // paid ML Kit's model setup on every coached move while the Cactus route paid it once,
            // an asymmetry nobody measured because only the Cactus fallback has been exercised on
            // real hardware.
            is VendorRoute.MlKitPrompt -> cachedMlKit.getOrPut(route.preference) {
                MlKitPromptGenerator(route.preference)
            }
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

/**
 * Process-wide, like [cachedGenerator]. Keyed by preference because FAST and FULL are different
 * ML Kit models. Both caches are deliberately never cleared — `release()` borrows and returns a
 * generator, it does not destroy one.
 */
private val cachedMlKit = java.util.concurrent.ConcurrentHashMap<ModelPreference, MlKitPromptGenerator>()

