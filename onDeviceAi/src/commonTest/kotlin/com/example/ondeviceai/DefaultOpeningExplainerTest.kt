package com.example.ondeviceai

import com.example.coachapi.OpeningExplainRequest
import com.example.coachapi.OpeningExplainResponse
import com.example.coachapi.Passage
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.delay
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class DefaultOpeningExplainerTest {
    private val request = OpeningExplainRequest("fen", listOf("e4", "e5"), "C20", "en-US")
    private val response = OpeningExplainResponse(
        text = "The King's Pawn Game contests the center.",
        passages = listOf(Passage("c20", "King's Pawn Game", "A central opening.")),
        composerId = "template-v1",
    )

    @Test
    fun `cloud decision calls client and reports cloud route`() = runTest {
        var calls = 0
        val explainer = DefaultOpeningExplainer(
            client = OpeningExplainerClient {
                calls++
                response
            },
            contextProvider = { cloudContext() },
        )

        val result = explainer.explain(request)

        assertEquals(1, calls)
        assertEquals(AiRoute.Cloud, assertIs<OpeningExplainerResult.Success>(result).route)
    }

    @Test
    fun `offline decision returns normal fallback without calling client`() = runTest {
        var calls = 0
        val explainer = DefaultOpeningExplainer(
            client = OpeningExplainerClient {
                calls++
                response
            },
            contextProvider = { cloudContext().copy(isNetworkAvailable = false) },
        )

        val result = explainer.explain(request)

        assertEquals(0, calls)
        val fallback = assertIs<OpeningExplainerResult.Fallback>(result)
        assertEquals(AiRoutePolicyDecider.FALLBACK_NO_NETWORK, fallback.reason)
        assertEquals(true, fallback.response.text.contains("unavailable offline"))
    }

    @Test
    fun `client failure returns deterministic fallback`() = runTest {
        val explainer = DefaultOpeningExplainer(
            client = OpeningExplainerClient { error("503") },
            contextProvider = { cloudContext() },
        )

        val result = assertIs<OpeningExplainerResult.Fallback>(explainer.explain(request))

        assertEquals(DefaultOpeningExplainer.FALLBACK_CLOUD_ERROR, result.reason)
        assertEquals("offline-fallback", result.response.composerId)
    }

    @Test
    fun `cloud call exceeding policy completion budget falls back`() = runTest {
        val explainer = DefaultOpeningExplainer(
            client = OpeningExplainerClient {
                delay(AiRoutePolicies.openingExplainer.latencyBudget.completeMs + 1)
                response
            },
            contextProvider = { cloudContext() },
        )

        val result = assertIs<OpeningExplainerResult.Fallback>(explainer.explain(request))

        assertEquals(AiRoutePolicyDecider.FALLBACK_TIMEOUT, result.reason)
    }

    private fun cloudContext() = AiContextSnapshot(
        isDeviceModelAvailable = false,
        isNetworkAvailable = true,
        userSetting = AiUserSetting.ALLOW_CLOUD,
    )
}
