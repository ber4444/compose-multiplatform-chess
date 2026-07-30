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
            is VendorRoute.MlKitPrompt -> {
                val mlkit = MlKitPromptGenerator(route.preference)
                if (mlkit.status() is AiAvailability.Available) mlkit
                else getCactus()
            }
            is VendorRoute.FirebaseHybrid -> FirebaseHybridGenerator(route.mode)
            is VendorRoute.CactusLocal -> getCactus()
            else -> null
        }
    }

    private fun getCactus(): CactusTextGenerator {
        if (!isCactusInitialized()) {
            // Assume context was initialized earlier, otherwise this will throw
        }
        return cachedGenerator ?: CactusTextGenerator().also { cachedGenerator = it }
    }
}

@Volatile
private var cachedGenerator: CactusTextGenerator? = null

actual fun resolveVendorRoute(policy: AiRoutePolicy, context: AiContextSnapshot): VendorRoute? {
    if (!context.isDeviceModelAvailable) return null

    val effectiveOfflineOnly = policy.requireOffline ||
        policy.privacyClass == PrivacyClass.LOCAL_ONLY ||
        context.userSetting == AiUserSetting.OFFLINE_ONLY

    val preference = if (policy.privacyClass == PrivacyClass.LOCAL_ONLY) "FAST" else "FULL"

    // `FirebaseHybrid` is a CLOUD-CAPABLE vendor, but it is returned as a `VendorRoute` — which
    // `AiRoutePolicyDecider` wraps in `Decision.Route`, a decision callers treat as "handled
    // on-device". Selecting it therefore opens a second cloud path that bypasses `:server` and its
    // validator/cost accounting, so it must be gated on the policy actually permitting cloud.
    //
    // `effectiveOfflineOnly` alone is NOT that gate: a policy can be non-LOCAL_ONLY and
    // non-requireOffline while still having `allowCloud = false` or a zero cost budget, and would
    // previously have been handed Firebase anyway. `permitsCloud()` is the same predicate the
    // decider uses for `RunCloud`, so the two cannot disagree.
    val cloudCapableVendorAllowed = policy.permitsCloud()

    return if (effectiveOfflineOnly || !cloudCapableVendorAllowed) {
        VendorRoute.MlKitPrompt(preference)
    } else {
        VendorRoute.FirebaseHybrid("HYBRID")
    }
}
