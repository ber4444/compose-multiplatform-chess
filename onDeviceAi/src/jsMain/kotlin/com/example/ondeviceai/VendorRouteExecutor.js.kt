package com.example.ondeviceai

actual class VendorRouteExecutor : AiRouteExecutor {
    actual override suspend fun execute(route: VendorRoute): OnDeviceTextGenerator? {
        return when (route) {
            is VendorRoute.AppleFoundationModels -> error("iOS route on JS")
            is VendorRoute.MlKitPrompt -> error("Android route on JS")
            is VendorRoute.LiteRtLm -> error("Desktop/Wasm route on JS")
            is VendorRoute.CactusLocal -> error("Android route on JS")
        }
    }
}
