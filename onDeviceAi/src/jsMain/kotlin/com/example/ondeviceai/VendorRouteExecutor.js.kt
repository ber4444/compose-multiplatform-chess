package com.example.ondeviceai

actual class VendorRouteExecutor : AiRouteExecutor {
    actual override suspend fun execute(policy: AiRoutePolicy, context: AiContextSnapshot): OnDeviceTextGenerator? {
        return UnsupportedTextGenerator
    }
}
