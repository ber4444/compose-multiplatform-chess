package com.example.ondeviceai

expect class VendorRouteExecutor() : AiRouteExecutor {
    override suspend fun execute(policy: AiRoutePolicy, context: AiContextSnapshot): OnDeviceTextGenerator?
}
