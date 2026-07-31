package com.example.ondeviceai

actual class VendorRouteExecutor : AiRouteExecutor {
    actual override suspend fun execute(route: VendorRoute): OnDeviceTextGenerator? {
        return when (route) {
            is VendorRoute.AppleFoundationModels -> FoundationModelsBridgeRegistry.provider?.create()
            is VendorRoute.MlKitPrompt -> error("Android route on iOS")
            is VendorRoute.LiteRtLm -> error("Desktop/Wasm route on iOS")
            is VendorRoute.CactusLocal -> error("Android route on iOS")
        }
    }
}
