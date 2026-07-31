package com.example.ondeviceai

class FakeVendorRouteExecutor(
    var generator: OnDeviceTextGenerator? = null
) : AiRouteExecutor {
    override suspend fun execute(route: VendorRoute): OnDeviceTextGenerator? {
        return generator
    }
}
