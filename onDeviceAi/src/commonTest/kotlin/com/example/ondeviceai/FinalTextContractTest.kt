package com.example.ondeviceai

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Pins the [AiTokenOrFinal.Final] text contract at the consumer end.
 *
 * A generator that streamed Tokens *and* reported the accumulated answer on Final had that answer
 * appended to itself, because every orchestrator appended both into one buffer. On Android that
 * rendered every coach line twice, verbatim. It reached `evals/scorecard.md` as an "AICore
 * repetition loop" and stood as a model defect for a month; the arithmetic was the tell, since a
 * 314-char output against a 300-char cap is one 157-char answer doubled rather than a model
 * degenerating.
 *
 * No `commonTest` could reproduce it, because `FakeTextGenerator` honoured the contract and had no
 * way to express the violation. `FakeTextGenerator.finalText` is that way, and these are the tests
 * it exists for. The consumers now ignore Final text once a Token has arrived — the branch is kept
 * rather than deleted so a genuinely non-streaming backend still works, and that case is pinned
 * here too.
 */
class FinalTextContractTest {

    private val explanation = "Nf3 develops a knight and supports the centre."
    private val payload = """{"headline": "Develops knight", "explanation": "$explanation"}"""

    private val coachRequest = MoveCoachRequest(
        moveUci = "g1f3",
        moveDisplay = "Nf3",
        deterministicHeadline = "Good — Nf3",
        deterministicExplanation = "You played Nf3. It develops a piece to an active square.",
        engineDifficultyName = "Medium",
    )

    private val summaryRequest = GameSummaryRequest(
        pgn = "1. e4 e5 2. Nf3 Nc6",
        policy = AiRoutePolicies.moveCoachOffline,
    )

    private val context = AiContextSnapshot(
        availableLocalVendors = listOf(VendorRoute.LiteRtLm()),
        isAppForegrounded = true,
        userSetting = AiUserSetting.OFFLINE_ONLY,
    )

    private fun coach(generator: OnDeviceTextGenerator) = DefaultAiCoachOrchestrator(
        executor = FakeVendorRouteExecutor(generator),
        contextProvider = { context },
    )

    private fun summary(generator: OnDeviceTextGenerator) = DefaultGameSummaryOrchestrator(
        executor = FakeVendorRouteExecutor(generator),
        contextProvider = { context },
    )

    @Test
    fun `coach does not double when Final repeats the streamed text`() = runTest {
        // Exactly what MlKitPromptGenerator did: stream the answer, then hand the whole thing back
        // on Final.
        val gen = FakeTextGenerator(response = payload, finalText = payload)
        val result = coach(gen).explainMove(coachRequest)

        assertIs<MoveCoachResult.Success>(result)
        assertEquals(explanation, result.explanation.explanation)
    }

    @Test
    fun `coach still reads Final text when nothing streamed`() = runTest {
        // The non-streaming backend this branch exists for: no Tokens at all, Final carries the
        // whole answer.
        val gen = FakeTextGenerator(chunks = emptyList(), finalText = payload)
        val result = coach(gen).explainMove(coachRequest)

        assertIs<MoveCoachResult.Success>(result)
        assertEquals(explanation, result.explanation.explanation)
    }

    @Test
    fun `summary does not double when Final repeats the streamed text`() = runTest {
        // Game Summary is the surface with no response validator at all, so a duplicate here goes
        // straight to the user with nothing in between.
        val text = "The turning point was 12...Qh4, which dropped a rook."
        val gen = FakeTextGenerator(response = text, finalText = text)
        val result = summary(gen).summarizeGame(summaryRequest)

        assertIs<GameSummaryResult.Success>(result)
        val rendered = result.explanation.explanation
        assertEquals(
            1,
            rendered.windowed(text.length).count { it == text },
            "summary text appeared more than once: $rendered",
        )
    }

    @Test
    fun `summary still reads Final text when nothing streamed`() = runTest {
        val text = "The turning point was 12...Qh4, which dropped a rook."
        val gen = FakeTextGenerator(chunks = emptyList(), finalText = text)
        val result = summary(gen).summarizeGame(summaryRequest)

        assertIs<GameSummaryResult.Success>(result)
        assertTrue(
            result.explanation.explanation.contains("Qh4"),
            "expected Final text to be used when no token streamed",
        )
    }
}
