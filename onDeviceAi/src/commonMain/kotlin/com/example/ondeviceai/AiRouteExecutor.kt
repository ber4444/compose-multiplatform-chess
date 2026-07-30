package com.example.ondeviceai

interface AiRouteExecutor {
    suspend fun execute(policy: AiRoutePolicy, context: AiContextSnapshot): OnDeviceTextGenerator?
}
