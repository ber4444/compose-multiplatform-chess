package com.example.ondeviceai

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class DefaultRulesQaOrchestratorTest {

    @Test
    fun `valid locally grounded answer succeeds`() = runTest {
        val orchestrator = DefaultRulesQaOrchestrator(
            answerer = RulesQaAnswerer {
                RulesQaModelOutput(
                    text = "A king may not castle through check. [castling-check]",
                    retrievedPassageIds = listOf("castling-check"),
                )
            },
            contextProvider = { localContext() },
        )

        val result = orchestrator.answer("Can I castle through check?")

        assertIs<RulesQaResult.Success>(result)
        assertEquals(listOf("castling-check"), result.passageIds)
    }

    @Test
    fun `ungrounded answer uses static rules fallback`() = runTest {
        val orchestrator = DefaultRulesQaOrchestrator(
            answerer = RulesQaAnswerer {
                RulesQaModelOutput("Yes, whenever you want.", emptyList())
            },
            contextProvider = { localContext() },
        )

        val result = orchestrator.answer("Can I castle through check?")

        assertIs<RulesQaResult.FellBack>(result)
        assertEquals(RulesQaFallback.TEXT, result.text)
    }

    @Test
    fun `missing local model falls back and never calls answerer`() = runTest {
        var called = false
        val orchestrator = DefaultRulesQaOrchestrator(
            answerer = RulesQaAnswerer {
                called = true
                RulesQaModelOutput("unused", emptyList())
            },
            contextProvider = { localContext(isDeviceModelAvailable = false) },
        )

        val result = orchestrator.answer("What is stalemate?")

        assertIs<RulesQaResult.FellBack>(result)
        assertEquals(false, called)
    }

    private fun localContext(isDeviceModelAvailable: Boolean = true) = AiContextSnapshot(
        isDeviceModelAvailable = isDeviceModelAvailable,
        isAppForegrounded = true,
        isNetworkAvailable = true,
        userSetting = AiUserSetting.PREFER_LOCAL,
    )
}
