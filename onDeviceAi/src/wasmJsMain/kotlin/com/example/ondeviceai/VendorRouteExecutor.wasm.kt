package com.example.ondeviceai

import com.example.ondeviceai.litertlm.LitertLmWasmTextGenerator

actual class VendorRouteExecutor : AiRouteExecutor {
    actual override suspend fun execute(route: VendorRoute): OnDeviceTextGenerator? {
        return when (route) {
            is VendorRoute.LiteRtLm -> cachedGenerator ?: LitertLmWasmTextGenerator().also { cachedGenerator = it }
            is VendorRoute.MlKitPrompt -> error("Android route on Wasm")
            is VendorRoute.AppleFoundationModels -> error("iOS route on Wasm")
            is VendorRoute.CactusLocal -> error("Android route on Wasm")
        }
    }
}

private var cachedGenerator: LitertLmWasmTextGenerator? = null
