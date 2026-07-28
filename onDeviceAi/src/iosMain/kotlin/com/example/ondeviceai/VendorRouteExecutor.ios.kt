package com.example.ondeviceai

actual class VendorRouteExecutor {
    actual suspend fun execute(route: VendorRoute): OnDeviceTextGenerator? {
        return FoundationModelsBridgeRegistry.provider?.create()
    }
}

actual fun resolveVendorRoute(policy: AiRoutePolicy, context: AiContextSnapshot): VendorRoute? {
    if (!context.isDeviceModelAvailable) return null
    return VendorRoute.AppleFoundationModels()
}
