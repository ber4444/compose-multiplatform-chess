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
        // Carries a concept word so it reaches the length rule; without one it is now rejected
        // earlier, as a restatement, and this case would stop testing what it is named for.
        val long = "Nf3 develops " + "x".repeat(MoveCoachPromptBuilder.MAX_OUTPUT_CHARS)
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
    fun `strips think blocks from the response`() {
        assertEquals(
            "Nf3 develops a piece toward the center.",
            MoveCoachResponseValidator.normalize("<think>Let me see. The user played Nf3.</think>Nf3 develops a piece toward the center."),
        )
    }

    @Test
    fun `strips unclosed think blocks from the response`() {
        assertEquals(
            "",
            MoveCoachResponseValidator.normalize("<think>Let me see. The user played Nf3."),
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
        val text = "e4 develops the pawn and leaves you up 0.5 pawns. Keep the initiative."
        val v = MoveCoachResponseValidator.validate(text, request.copy(moveDisplay = "e4"))
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

    // --- prompt echo (the fourth thing this model has copied out of its own context) -------------

    @Test
    fun `rejects the verbatim prompt echo observed on-device`() {
        // Copied off the screen of a real game, both cases.
        val withMotifs = MoveCoachResponseValidator.validate(
            "The player just played hxg3. Engine assessment of that move: best. " +
                "Tactical features detected: discovered attack, pin. " +
                "Baseline explanation: \"It pins a good piece against a more valuable one.\"",
            request.copy(
                moveUci = "h4g3",
                moveDisplay = "hxg3",
                moveClassName = "BEST",
                motifs = listOf("discovered-attack", "pin"),
                deterministicExplanation = "It pins a good piece against a more valuable one.",
            ),
        )
        val invalid = assertIs<MoveCoachResponseValidator.Result.Invalid>(withMotifs)
        assertTrue(invalid.reason.startsWith("echoed the prompt"), invalid.reason)

        val withoutMotifs = MoveCoachResponseValidator.validate(
            "The player just played e3. Engine assessment of that move: best. " +
                "Baseline explanation: \"The position stays roughly balanced.\"",
            request.copy(
                moveUci = "e2e3",
                moveDisplay = "e3",
                moveClassName = "BEST",
                deterministicExplanation = "The position stays roughly balanced.",
            ),
        )
        assertIs<MoveCoachResponseValidator.Result.Invalid>(withoutMotifs)
    }

    @Test
    fun `a single echoed prompt line is enough to reject`() {
        // The model does not have to copy the whole block to have answered with it.
        val v = MoveCoachResponseValidator.validate(
            "Engine assessment of that move: best. So the knight is well placed.",
            request.copy(moveClassName = "BEST"),
        )
        assertIs<MoveCoachResponseValidator.Result.Invalid>(v)
    }

    @Test
    fun `a real answer that happens to name the move is not an echo`() {
        // The guard must not fire on ordinary prose. "Nf3" and "develops" appear in the prompt too.
        val v = MoveCoachResponseValidator.validate(
            "Nf3 develops the knight and points at the centre.",
            request.copy(moveClassName = "BEST", motifs = listOf("develops")),
        )
        assertIs<MoveCoachResponseValidator.Result.Valid>(v)
    }

    @Test
    fun `restating the baseline explanation is weak but not an echo`() {
        // The prompt line carries a "Baseline explanation:" label, so the fingerprints differ. A
        // model that paraphrases the floor has answered badly, not copied.
        val v = MoveCoachResponseValidator.validate(
            request.deterministicExplanation,
            request,
        )
        assertIs<MoveCoachResponseValidator.Result.Valid>(v)
    }

    // --- restatement: grounded, short, and worth nothing -----------------------------------------

    @Test
    fun `rejects a restatement of the move`() {
        // Reported on-device once the length rule stopped discarding everything: gemma3-270m answers
        // by naming the move back. Grounded, brief, not a forbidden phrase and not a verbatim prompt
        // line — so it passed, and an accepted restatement *replaces* a deterministic sentence that
        // names the pieces involved. Being wordy about nothing is worse than the template.
        for (restatement in listOf(
            "You played Nf3.",
            "The move is Nf3.",
            "Nf3.",
            "The player moved the knight to f3.",
        )) {
            val v = MoveCoachResponseValidator.validate(restatement, request)
            val invalid = assertIs<MoveCoachResponseValidator.Result.Invalid>(v, restatement)
            assertTrue(invalid.reason.startsWith("restates the move"), "$restatement -> ${invalid.reason}")
        }
    }

    @Test
    fun `a piece name alone is not an explanation`() {
        // "knight" was in the accepted vocabulary, so naming the piece counted as chess content.
        val v = MoveCoachResponseValidator.validate("This knight move is fine.", request)
        assertIs<MoveCoachResponseValidator.Result.Invalid>(v)
    }

    @Test
    fun `keeps an answer that says why`() {
        // The bar must not reject real explanations; each of these clears it on one concept word.
        val requestsAndText = listOf(
            request to "Nf3 develops the knight.",
            request to "It covers e5 and keeps the centre.",
            request.copy(moveDisplay = "Bc4") to "That leaves the bishop undefended.",
            request.copy(moveDisplay = "Nf3+", motifs = listOf("check")) to "It gives check.",
        )
        for ((req, good) in requestsAndText) {
            assertIs<MoveCoachResponseValidator.Result.Valid>(
                MoveCoachResponseValidator.validate(good, req), good,
            )
        }
    }

    // --- faithfulness: the claims must be supported by the facts ---------------------------------

    @Test
    fun `rejects a claimed check the move did not give`() {
        val v = MoveCoachResponseValidator.validate(
            "Nf3 develops the knight and gives check.",
            request.copy(moveDisplay = "Nf3", motifs = emptyList()),
        )
        val invalid = assertIs<MoveCoachResponseValidator.Result.Invalid>(v)
        assertTrue("check" in invalid.reason, invalid.reason)
    }

    @Test
    fun `accepts a claimed check when SAN says so`() {
        // Ground truth is the move itself: "+" is check, so the claim is supported.
        assertIs<MoveCoachResponseValidator.Result.Valid>(
            MoveCoachResponseValidator.validate(
                "Nf3+ develops the knight and gives check.",
                request.copy(moveDisplay = "Nf3+"),
            ),
        )
    }

    @Test
    fun `rejects a claimed checkmate on a quiet move`() {
        assertIs<MoveCoachResponseValidator.Result.Invalid>(
            MoveCoachResponseValidator.validate(
                "That is checkmate and wins the game.",
                request.copy(moveDisplay = "Nf3", motifs = emptyList()),
            ),
        )
    }

    @Test
    fun `rejects a claimed capture that did not happen`() {
        // The exact shape the deterministic layer produces, asserted by a model about a quiet move.
        for (claim in listOf(
            "It captures the bishop and develops.",
            "It wins the pawn on g6 and opens the centre.",
            "It takes the rook, winning material.",
        )) {
            assertIs<MoveCoachResponseValidator.Result.Invalid>(
                MoveCoachResponseValidator.validate(claim, request.copy(moveDisplay = "Nf3", motifs = emptyList())),
                claim,
            )
        }
    }

    @Test
    fun `accepts a capture claim when SAN captured and the facts name the piece`() {
        assertIs<MoveCoachResponseValidator.Result.Valid>(
            MoveCoachResponseValidator.validate(
                "It captures the bishop and keeps the centre.",
                request.copy(
                    moveDisplay = "Nxc4",
                    motifs = listOf("capture"),
                    deterministicExplanation = "It takes the bishop on c4.",
                ),
            ),
        )
    }

    @Test
    fun `naming a captured piece the facts never mentioned is still invented`() {
        // The model only sees the prompt. If the facts do not say what was taken, it cannot know.
        assertIs<MoveCoachResponseValidator.Result.Invalid>(
            MoveCoachResponseValidator.validate(
                "It captures the bishop and keeps the centre.",
                request.copy(moveDisplay = "Nxc4", motifs = listOf("capture")),
            ),
        )
    }

    @Test
    fun `takes space is not a capture claim`() {
        // "takes"/"take" as bare words rejected ordinary prose: takes space, takes control, takes aim.
        for (good in listOf(
            "The pawn takes space in the centre.",
            "It takes control of the long diagonal.",
        )) {
            assertIs<MoveCoachResponseValidator.Result.Valid>(
                MoveCoachResponseValidator.validate(good, request.copy(moveDisplay = "e4")), good,
            )
        }
    }

    // --- piece-type ------------------------------------------------------------------------------

    @Test
    fun `rejects naming a piece the move does not involve`() {
        val v = MoveCoachResponseValidator.validate(
            "The bishop develops toward the centre.",
            request.copy(moveDisplay = "Nf3", deterministicExplanation = "It develops a piece."),
        )
        val invalid = assertIs<MoveCoachResponseValidator.Result.Invalid>(v)
        assertTrue("bishop" in invalid.reason, invalid.reason)
    }

    @Test
    fun `accepts a piece the deterministic text already named`() {
        // The line now says "Your bishop on b5 pins the knight on c6", so a faithful answer may
        // repeat both. A rule allowing only the mover would reject a correct sentence.
        assertIs<MoveCoachResponseValidator.Result.Valid>(
            MoveCoachResponseValidator.validate(
                "The bishop pins the knight, so it cannot move.",
                request.copy(
                    moveDisplay = "Bb5",
                    deterministicExplanation = "Your bishop on b5 pins the knight on c6 against the king on e8.",
                ),
            ),
        )
    }

    @Test
    fun `a promotion may name both the pawn and what it became`() {
        assertIs<MoveCoachResponseValidator.Result.Valid>(
            MoveCoachResponseValidator.validate(
                "The pawn becomes a queen and controls the board.",
                request.copy(moveDisplay = "e8=Q", motifs = listOf("promotion")),
            ),
        )
    }

    @Test
    fun `castling may name the king and rook`() {
        assertIs<MoveCoachResponseValidator.Result.Valid>(
            MoveCoachResponseValidator.validate(
                "The king castles to safety and the rook activates.",
                request.copy(moveDisplay = "O-O", motifs = listOf("castle-kingside")),
            ),
        )
    }
}
