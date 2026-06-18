package com.example.ondeviceai

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MoveCoachPromptBuilderTest {

    private val request = MoveCoachRequest(
        fenBefore = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1",
        bestMoveUci = "g1f3",
        bestMoveDisplay = "Nf3",
        sideToMove = "white",
        evaluationBeforeCp = 20,
        evaluationAfterCp = 30,
        deterministicTags = listOf("develops", "controls-centre"),
    )

    @Test
    fun `build emits only whitelisted fields`() {
        val built = MoveCoachPromptBuilder.build(request)
        assertTrue(built.userPrompt.contains("Position FEN: ${request.fenBefore}"))
        assertTrue(built.userPrompt.contains("Best move: Nf3 (g1f3)"))
        assertTrue(built.userPrompt.contains("Side to move: white"))
        assertTrue(built.userPrompt.contains("Evaluation before: 20"))
        assertTrue(built.userPrompt.contains("Evaluation after: 30"))
        assertTrue(built.userPrompt.contains("Tags: develops, controls-centre"))
    }

    @Test
    fun `build never interpolates the system prompt with user data`() {
        val built = MoveCoachPromptBuilder.build(request)
        assertFalse(built.systemPrompt.contains(request.fenBefore))
        assertFalse(built.systemPrompt.contains("Nf3"))
    }

    @Test
    fun `empty tags render as 'none'`() {
        val built = MoveCoachPromptBuilder.build(request.copy(deterministicTags = emptyList()))
        assertTrue(built.userPrompt.contains("Tags: none"))
    }

    @Test
    fun `null evaluations render as 'n-a'`() {
        val built = MoveCoachPromptBuilder.build(
            request.copy(evaluationBeforeCp = null, evaluationAfterCp = null)
        )
        assertTrue(built.userPrompt.contains("Evaluation before: n/a"))
        assertTrue(built.userPrompt.contains("Evaluation after: n/a"))
    }

    @Test
    fun `retry prompt references the rejected attempt and tightens constraints`() {
        val built = MoveCoachPromptBuilder.buildRetry(
            request,
            previousOutput = "Stockfish thinks depth 30",
        )
        assertTrue(built.userPrompt.contains("A previous attempt was rejected"))
        assertTrue(built.userPrompt.contains("No opening names"))
        assertEquals(0.0, built.temperature)
    }

    @Test
    fun `maxOutputTokens is conservative`() {
        val built = MoveCoachPromptBuilder.build(request)
        assertTrue(built.maxOutputTokens <= 120)
    }

    @Test
    fun `MAX_OUTPUT_CHARS bounds validator`() {
        assertEquals(360, MoveCoachPromptBuilder.MAX_OUTPUT_CHARS)
    }
}
