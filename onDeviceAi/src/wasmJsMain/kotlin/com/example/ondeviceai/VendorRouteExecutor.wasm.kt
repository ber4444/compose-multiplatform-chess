package com.example.ondeviceai

import com.example.ondeviceai.litertlm.LitertLmWasmTextGenerator

actual class VendorRouteExecutor : AiRouteExecutor {
    actual override suspend fun execute(route: VendorRoute): OnDeviceTextGenerator? {
        return cachedGenerator ?: LitertLmWasmTextGenerator().also { cachedGenerator = it }
    }
}

private var cachedGenerator: LitertLmWasmTextGenerator? = null

actual fun resolveVendorRoute(policy: AiRoutePolicy, context: AiContextSnapshot): VendorRoute? {
    if (!context.isDeviceModelAvailable) return null
    return VendorRoute.LiteRtLm()
}
