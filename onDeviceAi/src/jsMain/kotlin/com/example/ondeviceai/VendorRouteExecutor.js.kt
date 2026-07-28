package com.example.ondeviceai

actual class VendorRouteExecutor {
    actual suspend fun execute(route: VendorRoute): OnDeviceTextGenerator? {
        return UnsupportedTextGenerator
    }
}

actual fun resolveVendorRoute(policy: AiRoutePolicy, context: AiContextSnapshot): VendorRoute? {
    return null
}
