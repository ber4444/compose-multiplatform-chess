package com.example.ondeviceai

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class DefaultGameSummaryOrchestratorTest {

    private val request = GameSummaryRequest(
        pgn = "1. e4 e5 2. Nf3 Nc6",
        policy = AiRoutePolicies.moveCoachOffline,
    )

    private fun orchestrator(
        generator: OnDeviceTextGenerator?,
        context: AiContextSnapshot = AiContextSnapshot(
            availableLocalVendors = listOf(VendorRoute.LiteRtLm()),
            isAppForegrounded = true,
            userSetting = AiUserSetting.OFFLINE_ONLY,
        ),
    ) = DefaultGameSummaryOrchestrator(
        executor = FakeVendorRouteExecutor(generator),
        contextProvider = { context },
    )

    @Test
    fun `success path returns explanation`() = runTest {
        val gen = FakeTextGenerator(response = "The greatest mistake was e5.")
        val result = orchestrator(gen).summarizeGame(request)
        assertIs<GameSummaryResult.Success>(result)
        assertEquals(AiRoute.OnDevice, result.explanation.route)
        assertEquals(1, gen.generateCount)
    }

    @Test
    fun `unavailable model falls back deterministically`() = runTest {
        val gen = FakeTextGenerator(status = AiAvailability.Unavailable)
        val result = orchestrator(gen).summarizeGame(request)
        assertIs<GameSummaryResult.FellBack>(result)
        assertEquals(AiRoutePolicyDecider.FallbackReason.NoLocalModel, result.reason)
        assertEquals(0, gen.generateCount)
    }

    @Test
    fun `busy model falls back with quota reason`() = runTest {
        val gen = FakeTextGenerator(status = AiAvailability.Busy)
        val result = orchestrator(gen).summarizeGame(request)
        assertIs<GameSummaryResult.FellBack>(result)
        assertEquals(AiRoutePolicyDecider.FallbackReason.Quota, result.reason)
    }

    @Test
    fun `blank generation falls back`() = runTest {
        val gen = FakeTextGenerator(response = "   ")
        val result = orchestrator(gen).summarizeGame(request)
        assertIs<GameSummaryResult.FellBack>(result)
        assertEquals(AiRoutePolicyDecider.FallbackReason.Validation, result.reason)
        assertEquals(1, gen.generateCount)
    }

    @Test
    fun `generator exception falls back rather than throwing`() = runTest {
        val gen = FakeTextGenerator(throwOnGenerate = RuntimeException("boom"))
        val result = orchestrator(gen).summarizeGame(request)
        assertIs<GameSummaryResult.FellBack>(result)
        val reason = assertIs<AiRoutePolicyDecider.FallbackReason.Other>(result.reason)
        assertTrue(reason.description.contains("generation error"))
    }

    @Test
    fun `null factory result falls back`() = runTest {
        val orchestrator = DefaultGameSummaryOrchestrator(
            executor = FakeVendorRouteExecutor(null),
            contextProvider = {
                AiContextSnapshot(
                    availableLocalVendors = listOf(VendorRoute.LiteRtLm()),
                    isAppForegrounded = true,
                )
            },
        )
        val result = orchestrator.summarizeGame(request)
        assertIs<GameSummaryResult.FellBack>(result)
        assertEquals(AiRoutePolicyDecider.FallbackReason.NoLocalModel, result.reason)
    }

    @Test
    fun `streaming surfaces complete event with success result`() = runTest {
        val gen = FakeTextGenerator().apply {
            chunks = listOf("The mistake ", "was ", "e5.")
            tokenDelayMs = 1
        }
        val events = orchestrator(gen).summarizeGameStreaming(request).toListActual()
        assertTrue(events.any { it is GameSummaryEvent.Complete })
        val complete = events.last()
        assertIs<GameSummaryEvent.Complete>(complete)
        assertIs<GameSummaryResult.Success>(complete.result)
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
        val result = orchestrator.summarizeGame(request)
        assertIs<GameSummaryResult.FellBack>(result)
        assertEquals(AiRoutePolicyDecider.FallbackReason.Background, result.reason)
        assertEquals(0, gen.generateCount)
    }

    @Test
    fun `always closes generator after success`() = runTest {
        val gen = FakeTextGenerator(response = "The mistake was e5.")
        orchestrator(gen).summarizeGame(request)
        assertEquals(1, gen.releaseCount)
    }

    @Test
    fun `always closes generator after failure`() = runTest {
        val gen = FakeTextGenerator(throwOnGenerate = RuntimeException("boom"))
        orchestrator(gen).summarizeGame(request)
        assertEquals(1, gen.releaseCount)
    }
}

private suspend fun <T> Flow<T>.toListActual(): List<T> {
    val out = mutableListOf<T>()
    collect { out += it }
    return out
}
