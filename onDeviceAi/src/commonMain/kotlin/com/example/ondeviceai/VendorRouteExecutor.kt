package com.example.ondeviceai

expect class VendorRouteExecutor() : AiRouteExecutor {
    override suspend fun execute(route: VendorRoute): OnDeviceTextGenerator?
}
