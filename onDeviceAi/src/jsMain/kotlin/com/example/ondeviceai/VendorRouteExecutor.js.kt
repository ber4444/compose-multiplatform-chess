package com.example.ondeviceai

actual class VendorRouteExecutor : AiRouteExecutor {
    actual override suspend fun execute(route: VendorRoute): OnDeviceTextGenerator? {
        return UnsupportedTextGenerator
    }
}

actual fun resolveVendorRoute(policy: AiRoutePolicy, context: AiContextSnapshot): VendorRoute? {
    if (!context.isDeviceModelAvailable) return null
    return VendorRoute.LiteRtLm()
}
