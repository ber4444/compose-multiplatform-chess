package com.example.ondeviceai

import com.example.ondeviceai.litert.LiteRtLmTextGenerator
import com.example.ondeviceai.mlkit.MlKitPromptTextGenerator

actual fun defaultOnDeviceTextGeneratorFactory(): OnDeviceTextGeneratorFactory =
    OnDeviceTextGeneratorFactory {
        if (litertDebugFlagEnabled()) {
            val path = defaultLitertLmModelPath()
            if (!path.isNullOrBlank()) {
                val generator = LiteRtLmTextGenerator(pathToModel = path)
                if (generator.statusBlocking() is AiAvailability.Available) {
                    return@OnDeviceTextGeneratorFactory generator
                }
            }
        }
        MlKitPromptTextGenerator()
    }

internal fun litertDebugFlagEnabled(): Boolean =
    try {
        System.getProperty("chess.coach.litert.enabled")?.toBoolean() ?: false
    } catch (t: Throwable) {
        false
    }

internal fun defaultLitertLmModelPath(): String? = null

private suspend fun OnDeviceTextGenerator.statusBlocking(): AiAvailability = status()
