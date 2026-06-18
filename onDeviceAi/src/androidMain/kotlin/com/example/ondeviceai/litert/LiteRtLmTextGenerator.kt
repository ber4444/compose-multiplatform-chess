package com.example.ondeviceai.litert

import com.example.ondeviceai.AiAvailability
import com.example.ondeviceai.AiGenerationRequest
import com.example.ondeviceai.AiInferenceMetrics
import com.example.ondeviceai.AiRoute
import com.example.ondeviceai.AiTokenOrFinal
import com.example.ondeviceai.OnDeviceTextGenerator
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

class LiteRtLmTextGenerator(
    private val pathToModel: String?,
    private val accelerator: Accelerator = Accelerator.NPU_PREFERRED,
) : OnDeviceTextGenerator {

    enum class Accelerator { CPU_ONLY, GPU_PREFERRED, NPU_PREFERRED }

    private val runtimeClass by lazy { probeRuntimeClass() }

    override suspend fun status(): AiAvailability {
        if (pathToModel == null) return AiAvailability.Unavailable
        if (runtimeClass == null) return AiAvailability.Unavailable
        return AiAvailability.Available
    }

    override suspend fun warmup() {
        if (runtimeClass == null || pathToModel == null) return
        runCatching { invokeLitertLm(action = ACTION_WARMUP) }
    }

    override fun generate(request: AiGenerationRequest): Flow<AiTokenOrFinal> = flow {
        if (runtimeClass == null || pathToModel == null) return@flow
        val start = System.currentTimeMillis()
        val fullText = (invokeLitertLm(
            action = ACTION_GENERATE,
            systemPrompt = request.systemPrompt,
            userPrompt = request.userPrompt,
            maxTokens = request.maxOutputTokens,
            temperature = request.temperature,
        ) as? String).orEmpty()

        if (fullText.isNotEmpty()) emit(AiTokenOrFinal.Token(fullText))
        emit(
            AiTokenOrFinal.Final(
                text = "",
                metrics = AiInferenceMetrics(
                    firstTokenMs = null,
                    completeMs = System.currentTimeMillis() - start,
                    tokenCount = fullText.split(Regex("\\s+")).count { it.isNotBlank() },
                    route = AiRoute.OnDevice,
                )
            )
        )
    }

    override suspend fun close() = Unit

    private fun probeRuntimeClass(): Class<*>? = runCatching {
        val candidates = listOf(
            "com.google.ai.edge.litert.lm.InferenceEngine",
            "com.google.ai.edge.litertlm.InferenceEngine",
            "com.google.ai.edge.litert.LitertLm",
        )
        candidates.firstNotNullOfOrNull { runCatching { Class.forName(it) }.getOrNull() }
    }.getOrNull()

    private fun invokeLitertLm(
        action: Int,
        systemPrompt: String = "",
        userPrompt: String = "",
        maxTokens: Int = 0,
        temperature: Double = 0.0,
    ): Any? {
        return ""
    }

    private companion object {
        private const val ACTION_WARMUP = 0
        private const val ACTION_GENERATE = 1
    }
}
