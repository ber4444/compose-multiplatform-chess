package com.example.ondeviceai

import com.example.ondeviceai.litertlm.LitertLmWasmTextGenerator

actual class VendorRouteExecutor : AiRouteExecutor {
    actual override suspend fun execute(policy: AiRoutePolicy, context: AiContextSnapshot): OnDeviceTextGenerator? {
        return cachedGenerator ?: LitertLmWasmTextGenerator().also { cachedGenerator = it }
    }
}

private var cachedGenerator: LitertLmWasmTextGenerator? = null
