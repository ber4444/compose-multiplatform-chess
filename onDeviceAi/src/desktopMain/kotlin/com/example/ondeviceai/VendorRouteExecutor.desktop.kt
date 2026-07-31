package com.example.ondeviceai

import com.example.ondeviceai.litertlm.LitertLmModelStore
import com.example.ondeviceai.litertlm.LitertLmTextGenerator

actual class VendorRouteExecutor : AiRouteExecutor {
    actual override suspend fun execute(route: VendorRoute): OnDeviceTextGenerator? {
        return when (route) {
            is VendorRoute.LiteRtLm -> {
                cachedGenerator ?: LitertLmTextGenerator(
                    modelPath = LitertLmModelStore.modelFile().absolutePath,
                ).also { cachedGenerator = it }
            }
            is VendorRoute.MlKitPrompt -> error("Android route on Desktop")
            is VendorRoute.AppleFoundationModels -> error("iOS route on Desktop")
            is VendorRoute.CactusLocal -> error("Android route on Desktop")
        }
    }
}

@Volatile
private var cachedGenerator: LitertLmTextGenerator? = null
