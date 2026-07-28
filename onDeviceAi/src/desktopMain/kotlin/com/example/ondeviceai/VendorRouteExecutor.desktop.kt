package com.example.ondeviceai

import com.example.ondeviceai.litertlm.LitertLmModelStore
import com.example.ondeviceai.litertlm.LitertLmTextGenerator

actual class VendorRouteExecutor {
    actual suspend fun execute(route: VendorRoute): OnDeviceTextGenerator? {
        return cachedGenerator ?: LitertLmTextGenerator(
            modelPath = LitertLmModelStore.modelFile().absolutePath,
        ).also { cachedGenerator = it }
    }
}

@Volatile
private var cachedGenerator: LitertLmTextGenerator? = null

actual fun resolveVendorRoute(policy: AiRoutePolicy, context: AiContextSnapshot): VendorRoute? {
    if (!context.isDeviceModelAvailable) return null
    return VendorRoute.LiteRtLm()
}
