package com.example.ondeviceai

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class MoveCoachResponseValidatorTest {

    private val request = MoveCoachRequest(
        fenBefore = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1",
        bestMoveUci = "g1f3",
        bestMoveDisplay = "Nf3",
        sideToMove = "white",
        evaluationBeforeCp = 20,
        evaluationAfterCp = 30,
        deterministicTags = listOf("develops"),
        engineDifficultyName = "Medium",
    )

    @Test
    fun `accepts a short grounded response`() {
        val v = MoveCoachResponseValidator.validate(
            "Nf3 develops a knight and supports the centre.",
            request,
        )
        assertIs<MoveCoachResponseValidator.Result.Valid>(v)
    }

    @Test
    fun `rejects empty response`() {
        val v = MoveCoachResponseValidator.validate("   ", request)
        assertIs<MoveCoachResponseValidator.Result.Invalid>(v)
        assertEquals("empty response", v.reason)
    }

    @Test
    fun `rejects too-long response`() {
        val long = "Nf3 " + "x".repeat(MoveCoachPromptBuilder.MAX_OUTPUT_CHARS)
        val v = MoveCoachResponseValidator.validate(long, request)
        assertIs<MoveCoachResponseValidator.Result.Invalid>(v)
        assertTrue(v.reason.startsWith("response exceeds"))
    }

    @Test
    fun `rejects response with no chess relevance`() {
        // Text that mentions neither the move nor any chess vocabulary
        val v = MoveCoachResponseValidator.validate(
            "The weather is nice today.",
            request,
        )
        assertIs<MoveCoachResponseValidator.Result.Invalid>(v)
    }

    @Test
    fun `forbidden 'I think Stockfish' phrase is rejected`() {
        val v = MoveCoachResponseValidator.validate(
            "I think Stockfish chose Nf3 here.",
            request,
        )
        assertIs<MoveCoachResponseValidator.Result.Invalid>(v)
        assertTrue(v.reason.startsWith("forbidden phrase"))
    }

    @Test
    fun `forbidden 'probably depth' phrase is rejected`() {
        val v = MoveCoachResponseValidator.validate(
            "Nf3 - probably depth 30 was used.",
            request,
        )
        assertIs<MoveCoachResponseValidator.Result.Invalid>(v)
    }

    @Test
    fun `forbidden 'engine depth' phrase is rejected`() {
        val v = MoveCoachResponseValidator.validate(
            "Nf3, at engine depth 25, is strong.",
            request,
        )
        assertIs<MoveCoachResponseValidator.Result.Invalid>(v)
    }

    @Test
    fun `grounding tokens include both UCI squares and display text`() {
        val tokens = MoveCoachResponseValidator.groundingTokens(request)
        assertTrue(tokens.contains("g1f3"))
        assertTrue(tokens.contains("g1"))
        assertTrue(tokens.contains("f3"))
        assertTrue(tokens.contains("nf3"))
    }

    @Test
    fun `grounding tokens are lowercase`() {
        val tokens = MoveCoachResponseValidator.groundingTokens(
            request.copy(bestMoveDisplay = "Nf3", bestMoveUci = "G1F3")
        )
        assertTrue(tokens.all { it == it.lowercase() })
    }

    @Test
    fun `accepts response that mentions a UCI square`() {
        val v = MoveCoachResponseValidator.validate(
            "Moving the knight to f3 develops it.",
            request,
        )
        assertIs<MoveCoachResponseValidator.Result.Valid>(v)
    }

    @Test
    fun `strips echoed Good- prefix and surrounding quotes`() {
        val v = MoveCoachResponseValidator.validate(
            "Good: \"Nf3 develops the knight and controls the center.\"",
            request,
        )
        assertIs<MoveCoachResponseValidator.Result.Valid>(v)
        assertEquals("Nf3 develops the knight and controls the center.", v.text)
    }

    @Test
    fun `joins multi-line labeled echo into clean prose`() {
        val v = MoveCoachResponseValidator.validate(
            "Good: \"Nf3 develops the knight.\"\nBad: \"It controls the center.\"",
            request,
        )
        assertIs<MoveCoachResponseValidator.Result.Valid>(v)
        assertEquals("Nf3 develops the knight. It controls the center.", v.text)
    }

    @Test
    fun `leaves an unlabeled response unchanged`() {
        val v = MoveCoachResponseValidator.validate(
            "Nf3 develops the knight toward the center.",
            request,
        )
        assertIs<MoveCoachResponseValidator.Result.Valid>(v)
        assertEquals("Nf3 develops the knight toward the center.", v.text)
    }
}
