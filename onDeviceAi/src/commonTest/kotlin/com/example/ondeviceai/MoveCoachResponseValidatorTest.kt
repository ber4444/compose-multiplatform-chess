package com.example.ondeviceai

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class MoveCoachResponseValidatorTest {

    private val request = MoveCoachRequest(
        moveUci = "g1f3",
        moveDisplay = "Nf3",
        deterministicHeadline = "Good — Nf3",
        deterministicExplanation = "Engine choice: Nf3. It develops a piece to an active square.",
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
            request.copy(moveDisplay = "Nf3", moveUci = "G1F3")
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

    /**
     * `STYLE_EXAMPLES` no longer exists — the examples came out of the prompt precisely because the
     * model kept returning them. The filler survives as a validator rule rather than a prompt
     * counter-example, so it is what this case now pins.
     */
    @Test
    fun `rejects a verbatim echo of the generic filler`() {
        val v = MoveCoachResponseValidator.validate(
            MoveCoachPromptBuilder.GENERIC_FILLER,
            request.copy(moveUci = "b8c6", moveDisplay = "Nc6"),
        )
        assertIs<MoveCoachResponseValidator.Result.Invalid>(v)
        assertTrue(v.reason.startsWith("echoed a prompt example"))
    }

    @Test
    fun `rejects the generic filler that used to be prompted as a Bad example`() {
        val v = MoveCoachResponseValidator.validate(
            MoveCoachPromptBuilder.GENERIC_FILLER,
            request,
        )
        assertIs<MoveCoachResponseValidator.Result.Invalid>(v)
        assertTrue(v.reason.startsWith("echoed a prompt example"))
    }

    @Test
    fun `rejects the labeled multi-sentence echo observed on-device`() {
        // Verbatim gemma3-270m output from a Galaxy Z Fold3 run: style example #1 plus the old
        // `Bad:` filler, both relabeled "Good:" — and neither about the move being explained.
        val v = MoveCoachResponseValidator.validate(
            "Good: \"Nf3 develops the knight and controls the central e5/d4 squares.\"\n" +
                "Good: \"This is a good move that improves the position.\"",
            request.copy(moveUci = "b8c6", moveDisplay = "Nc6"),
        )
        assertIs<MoveCoachResponseValidator.Result.Invalid>(v)
        assertTrue(v.reason.startsWith("echoed a prompt example"))
    }

    @Test
    fun `accepts a genuine move-specific explanation`() {
        val v = MoveCoachResponseValidator.validate(
            "Nc6 develops the knight and puts pressure on the d4 square.",
            request.copy(moveUci = "b8c6", moveDisplay = "Nc6"),
        )
        assertIs<MoveCoachResponseValidator.Result.Valid>(v)
        assertEquals("Nc6 develops the knight and puts pressure on the d4 square.", v.text)
    }

    @Test
    fun `prompt no longer teaches the generic filler as a Bad example`() {
        // The filler must exist only as a validator constraint, never as prompt text for a small
        // model to copy. Guards the regression that produced the on-device echo.
        val prompt = MoveCoachPromptBuilder.build(request).systemPrompt
        assertTrue(!prompt.contains(MoveCoachPromptBuilder.GENERIC_FILLER))
        assertTrue(!prompt.contains("Bad:"))
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

    @Test
    fun `deduplicates repeated sentence loop into valid response`() {
        val sentence = "Nf3 develops the knight toward the center."
        val repeatedText = "$sentence $sentence"
        val v = MoveCoachResponseValidator.validate(repeatedText, request)
        assertIs<MoveCoachResponseValidator.Result.Valid>(v)
        assertEquals(sentence, v.text)
    }
}
