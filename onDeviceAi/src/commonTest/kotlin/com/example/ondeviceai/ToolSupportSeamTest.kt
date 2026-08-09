package com.example.ondeviceai

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse

class ToolSupportSeamTest {

    private class UnsupportedTextGenerator : OnDeviceTextGenerator {
        override suspend fun status(): AiAvailability = AiAvailability.Unavailable
        override suspend fun warmup() {}
        override fun generate(request: AiGenerationRequest): Flow<AiTokenOrFinal> = flow {
            emit(AiTokenOrFinal.Token("I am ignoring tools"))
            emit(AiTokenOrFinal.Final("", AiInferenceMetrics(0L, 0L, 4, AiRoute.OnDevice)))
        }
        override suspend fun close() {}
    }

    @Test
    fun `default on-device generator reports no tools support and emits no tool calls`() = runTest {
        val generator = UnsupportedTextGenerator()

        assertFalse(generator.supportsTools)

        val request = AiGenerationRequest(
            systemPrompt = "",
            userPrompt = "question",
            maxOutputTokens = 10,
            temperature = 0.0,
            tools = listOf(
                AiToolSpec(
                    name = "lookup_rule",
                    description = "Search the offline rules corpus",
                    parameters = emptyMap()
                )
            )
        )

        var emittedToolCall = false
        generator.generate(request).collect {
            if (it is AiTokenOrFinal.ToolCall) {
                emittedToolCall = true
            }
        }

        assertFalse(emittedToolCall)
    }
}
