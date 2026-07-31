package com.example.ondeviceai

class FakeVendorRouteExecutor(
    var generator: OnDeviceTextGenerator? = null
) : AiRouteExecutor {
    override suspend fun execute(policy: AiRoutePolicy, context: AiContextSnapshot): OnDeviceTextGenerator? {
        return generator
    }
}
