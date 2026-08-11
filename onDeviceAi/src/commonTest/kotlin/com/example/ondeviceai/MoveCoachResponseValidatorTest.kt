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
        deterministicExplanation = "You played Nf3. It develops a piece to an active square.",
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
    fun `rejects an over-long response with no sentence boundary to trim at`() {
        // Was `rejects too-long response`, asserting "response exceeds ...". Length no longer
        // rejects on its own — it trims (see the trimming tests below), because it is a layout
        // constraint and not a quality judgement. This input still fails only because it is a single
        // 300-character run-on: there is no whole sentence to keep.
        val long = "Nf3 " + "x".repeat(MoveCoachPromptBuilder.MAX_OUTPUT_CHARS)
        val v = MoveCoachResponseValidator.validate(long, request)
        assertIs<MoveCoachResponseValidator.Result.Invalid>(v)
        assertTrue(v.reason.startsWith("no complete sentence fits"), v.reason)
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

    // Conversational-preamble stripping. Runs on every target — commonTest is the point: the
    // implementation is prefix matching precisely because a regex with an inline (?i) flag throws
    // on Kotlin/JS, and only a common test would catch that.

    @Test
    fun `strips a conversational preamble and keeps the answer`() {
        assertEquals(
            "Nf3 develops a piece toward the center.",
            MoveCoachResponseValidator.normalize("Okay, here we go. Nf3 develops a piece toward the center."),
        )
    }

    @Test
    fun `strips a preamble that ends in a colon`() {
        assertEquals(
            "Nf3 develops a piece toward the center.",
            MoveCoachResponseValidator.normalize("Here's the explanation: Nf3 develops a piece toward the center."),
        )
    }

    @Test
    fun `preamble matching is case-insensitive`() {
        assertEquals(
            "Nf3 develops a piece toward the center.",
            MoveCoachResponseValidator.normalize("SURE! Nf3 develops a piece toward the center."),
        )
    }

    @Test
    fun `keeps text whose opening word merely resembles a preamble`() {
        // "Certainly" only opens a preamble when a preamble follows; here it opens the answer, and
        // eating everything to the first period would delete the whole first sentence.
        val text = "Certainly the knight belongs on f3."
        assertEquals(text, MoveCoachResponseValidator.normalize(text))
    }

    @Test
    fun `does not empty a response that is nothing but filler`() {
        // Better to hand the validator a blank-ish line it will reject than to silently produce "".
        val text = "Okay."
        assertEquals(text, MoveCoachResponseValidator.normalize(text))
    }

    @Test
    fun `strips stacked few-shot labels regardless of order`() {
        assertEquals(
            "Nf3 develops a piece.",
            MoveCoachResponseValidator.normalize("Bad: Good: Nf3 develops a piece."),
        )
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

    @Test
    fun `leaves a decimal intact when nothing is duplicated`() {
        // splitSentences breaks on every '.', so a naive split/rejoin would emit "up 0. 5 pawns".
        // Non-duplicated text must come back byte-identical.
        val text = "Nf3 develops the knight and leaves you up 0.5 pawns. Keep the initiative."
        val v = MoveCoachResponseValidator.validate(text, request)
        assertIs<MoveCoachResponseValidator.Result.Valid>(v)
        assertEquals(text, v.text)
    }

    // --- length trims, it does not reject (measured: this was rejecting every on-device answer) ---

    @Test
    fun `an over-long but valid answer is trimmed to whole sentences rather than discarded`() {
        // The logged production case: gemma3-270m answered well and ran past 300 chars, so the user
        // got the template on every single move.
        val long = "Nf3 develops the knight toward the centre. " +
            "It eyes e5 and d4 and prepares to castle. ".repeat(6)
        val result = MoveCoachResponseValidator.validate(long, request)

        val valid = assertIs<MoveCoachResponseValidator.Result.Valid>(result)
        assertTrue(valid.text.length <= MoveCoachPromptBuilder.MAX_OUTPUT_CHARS, "was ${valid.text.length}")
        assertTrue(valid.text.startsWith("Nf3 develops the knight"), valid.text)
        assertTrue(valid.text.endsWith("."), "must end on a sentence boundary: ${valid.text}")
    }

    @Test
    fun `one unbroken run-on longer than the budget is still rejected`() {
        // Not a long answer — degenerate output. The deterministic line is genuinely better.
        val runOn = "the knight " + "and then and then ".repeat(40)
        assertIs<MoveCoachResponseValidator.Result.Invalid>(
            MoveCoachResponseValidator.validate(runOn, request),
        )
    }

    @Test
    fun `trimming does not cut inside a decimal or a move number`() {
        // splitSentences' documented hazard: "0.5" and "1. e4" both look like sentence ends.
        assertEquals("Up 0.5 pawns.", MoveCoachResponseValidator.trimToBudget("Up 0.5 pawns. More text here.", 14))
        assertEquals(null, MoveCoachResponseValidator.trimToBudget("1. e4 is a fine opening move", 10))
    }

    @Test
    fun `text already within budget is returned untouched`() {
        val fits = "Nf3 develops the knight."
        assertEquals(fits, MoveCoachResponseValidator.trimToBudget(fits, 300))
    }
}
