package com.example.ondeviceai

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class DefaultAiCoachOrchestratorTest {

    // The deterministic layer supplies both fields; the model only ever rewrites the explanation.
    // A failure therefore has something true to fall back to, which is why there is no retry.
    private val request = MoveCoachRequest(
        moveUci = "g1f3",
        moveDisplay = "Nf3",
        deterministicHeadline = "Good — Nf3",
        deterministicExplanation = "Engine choice: Nf3. It develops a piece to an active square.",
        engineDifficultyName = "Medium",
    )

    private fun orchestrator(
        generator: OnDeviceTextGenerator?,
        context: AiContextSnapshot = AiContextSnapshot(
            availableLocalVendors = listOf(VendorRoute.LiteRtLm()),
            isAppForegrounded = true,
            userSetting = AiUserSetting.OFFLINE_ONLY,
        ),
    ) = DefaultAiCoachOrchestrator(
        executor = FakeVendorRouteExecutor(generator),
        contextProvider = { context },
    )

    @Test
    fun `success path returns validated explanation`() = runTest {
        val gen = FakeTextGenerator(response = """{"headline": "Develops knight", "explanation": "Nf3 develops a knight and supports the centre."}""")
        val result = orchestrator(gen).explainMove(request)
        assertIs<MoveCoachResult.Success>(result)
        assertEquals(ExplanationConfidence.HIGH, result.explanation.confidence)
        assertEquals(AiRoute.OnDevice, result.explanation.route)
        assertEquals(1, gen.generateCount)
    }

    @Test
    fun `unavailable model falls back deterministically`() = runTest {
        val gen = FakeTextGenerator(status = AiAvailability.Unavailable)
        val result = orchestrator(gen).explainMove(request)
        assertIs<MoveCoachResult.FellBack>(result)
        assertEquals(AiRoutePolicyDecider.FALLBACK_NO_LOCAL_MODEL, result.reason)
        assertEquals(0, gen.generateCount)
    }

    @Test
    fun `busy model falls back with quota reason`() = runTest {
        val gen = FakeTextGenerator(status = AiAvailability.Busy)
        val result = orchestrator(gen).explainMove(request)
        assertIs<MoveCoachResult.FellBack>(result)
        assertEquals(AiRoutePolicyDecider.FALLBACK_QUOTA, result.reason)
    }



    @Test
    fun `success path strips a markdown code fence around the prose`() = runTest {
        // Foundation Models wraps its answer in a ```…``` fence unprompted — the prompt never
        // mentions markdown. That was true when the response was JSON and is still true now that it
        // is prose, so the strip stays even though there is no longer anything to parse.
        val gen = FakeTextGenerator(
            response = "```\nNf3 develops a knight and supports the centre.\n```",
        )
        val result = orchestrator(gen).explainMove(request)
        assertIs<MoveCoachResult.Success>(result)
        assertEquals("Nf3 develops a knight and supports the centre.", result.explanation.explanation)
    }

    @Test
    fun `validation failure falls back`() = runTest {
        val gen = FakeTextGenerator()
        gen.generateInterceptor = { _, _ -> """{"headline": "Bad", "explanation": "This move does not mention the move."}""" }
        val result = orchestrator(gen).explainMove(request)
        assertIs<MoveCoachResult.FellBack>(result)
        assertEquals(AiRoutePolicyDecider.FALLBACK_VALIDATION, result.reason)
        assertEquals(1, gen.generateCount)
    }

    @Test
    fun `generator exception falls back rather than throwing`() = runTest {
        val gen = FakeTextGenerator(throwOnGenerate = RuntimeException("boom"))
        val result = orchestrator(gen).explainMove(request)
        assertIs<MoveCoachResult.FellBack>(result)
        assertTrue(result.reason.contains("generation error"))
    }

    @Test
    fun `null factory result falls back`() = runTest {
        val orchestrator = DefaultAiCoachOrchestrator(
            executor = FakeVendorRouteExecutor(null),
            contextProvider = {
                AiContextSnapshot(
                    availableLocalVendors = listOf(VendorRoute.LiteRtLm()),
                    isAppForegrounded = true,
                )
            },
        )
        val result = orchestrator.explainMove(request)
        assertIs<MoveCoachResult.FellBack>(result)
        assertEquals(AiRoutePolicyDecider.FALLBACK_NO_LOCAL_MODEL, result.reason)
    }

    @Test
    fun `streaming surfaces complete event with success result`() = runTest {
        val gen = FakeTextGenerator().apply {
            chunks = listOf("""{"headline": "Good", "explanation": "Nf3 """, "develops ", """a knight."}""")
            tokenDelayMs = 1
        }
        val events = orchestrator(gen).explainMoveStreaming(request).toListActual()
        assertTrue(events.any { it is MoveCoachEvent.Complete })
        val complete = events.last()
        assertIs<MoveCoachEvent.Complete>(complete)
        assertIs<MoveCoachResult.Success>(complete.result)
    }

    @Test
    fun `backgrounded app falls back without invoking generator`() = runTest {
        val gen = FakeTextGenerator()
        val orchestrator = orchestrator(
            gen,
            context = AiContextSnapshot(
                availableLocalVendors = listOf(VendorRoute.LiteRtLm()),
                isAppForegrounded = false,
                userSetting = AiUserSetting.OFFLINE_ONLY,
            ),
        )
        val result = orchestrator.explainMove(request)
        assertIs<MoveCoachResult.FellBack>(result)
        assertEquals(AiRoutePolicyDecider.FALLBACK_BACKGROUND, result.reason)
        assertEquals(0, gen.generateCount)
    }

    @Test
    fun `always closes generator after success`() = runTest {
        val gen = FakeTextGenerator(response = """{"headline": "Good", "explanation": "Nf3 develops the knight."}""")
        orchestrator(gen).explainMove(request)
        assertEquals(1, gen.closeCount)
    }

    @Test
    fun `always closes generator after failure`() = runTest {
        val gen = FakeTextGenerator(throwOnGenerate = RuntimeException("boom"))
        orchestrator(gen).explainMove(request)
        assertEquals(1, gen.closeCount)
    }
}

private suspend fun <T> Flow<T>.toListActual(): List<T> {
    val out = mutableListOf<T>()
    collect { out += it }
    return out
}
