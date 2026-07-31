package com.example.ondeviceai

interface AiRouteExecutor {
    suspend fun execute(route: VendorRoute): OnDeviceTextGenerator?
}
