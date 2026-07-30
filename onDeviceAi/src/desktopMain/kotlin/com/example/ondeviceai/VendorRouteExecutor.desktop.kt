package com.example.ondeviceai

import com.example.ondeviceai.litertlm.LitertLmModelStore
import com.example.ondeviceai.litertlm.LitertLmTextGenerator

actual class VendorRouteExecutor : AiRouteExecutor {
    actual override suspend fun execute(policy: AiRoutePolicy, context: AiContextSnapshot): OnDeviceTextGenerator? {
        return cachedGenerator ?: LitertLmTextGenerator(
            modelPath = LitertLmModelStore.modelFile().absolutePath,
        ).also { cachedGenerator = it }
    }
}

@Volatile
private var cachedGenerator: LitertLmTextGenerator? = null
