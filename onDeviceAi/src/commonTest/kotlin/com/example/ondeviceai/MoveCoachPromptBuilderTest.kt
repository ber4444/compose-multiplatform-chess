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
        deterministicTags = listOf("develops", "center-control"),
    )

    @Test
    fun `user prompt includes move description and key points`() {
        val built = MoveCoachPromptBuilder.build(request)
        assertTrue(built.userPrompt.contains("Knight"))
        assertTrue(built.userPrompt.contains("g1"))
        assertTrue(built.userPrompt.contains("f3"))
        assertTrue(built.userPrompt.contains("develops"))
    }

    @Test
    fun `system prompt never contains user-specific data`() {
        val built = MoveCoachPromptBuilder.build(request)
        // System prompt is static; user-specific FEN/UCI must not leak in.
        assertFalse(built.systemPrompt.contains(request.fenBefore))
        assertFalse(built.systemPrompt.contains("g1f3"))
        // "Nf3" may appear as part of a static example — that's fine.
    }

    @Test
    fun `system prompt includes a good example`() {
        val built = MoveCoachPromptBuilder.build(request)
        assertTrue(built.systemPrompt.contains("Good:"))
        assertTrue(built.systemPrompt.contains("Bad:"))
    }

    @Test
    fun `pawn moves produce Pawn description`() {
        val req = request.copy(bestMoveUci = "e2e4", bestMoveDisplay = "e4")
        val built = MoveCoachPromptBuilder.build(req)
        assertTrue(built.userPrompt.contains("Pawn"))
        assertTrue(built.userPrompt.contains("e2"))
        assertTrue(built.userPrompt.contains("e4"))
    }

    @Test
    fun `castling produces plain-English description`() {
        val req = request.copy(bestMoveDisplay = "O-O", bestMoveUci = "e1g1")
        val built = MoveCoachPromptBuilder.build(req)
        assertTrue(built.userPrompt.contains("Castles kingside"))
    }

    @Test
    fun `empty tags produce engine top-choice hint`() {
        val built = MoveCoachPromptBuilder.build(request.copy(deterministicTags = emptyList()))
        assertTrue(built.userPrompt.contains("engine's top choice"))
    }

    @Test
    fun `maxOutputTokens is conservative`() {
        val built = MoveCoachPromptBuilder.build(request)
        assertTrue(built.maxOutputTokens <= 120)
    }

    @Test
    fun `MAX_OUTPUT_CHARS bounds validator`() {
        assertEquals(300, MoveCoachPromptBuilder.MAX_OUTPUT_CHARS)
    }
}
