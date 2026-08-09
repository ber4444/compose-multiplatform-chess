package com.example.ondeviceai

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Rewritten for the prose prompt. An earlier version asserted the presence of `STYLE_EXAMPLES`
 * and `EXAMPLE_LABEL`; both are gone, and their removal is the point.
 *
 * Three on-device failures, in order, all the same bug: a 270M model copies the nearest text in its
 * context. It emitted a style example verbatim, then the `Bad:` counter-example added to forbid
 * that, then — once the examples were replaced by a JSON schema — the schema's own placeholder
 * strings, in 5 of 5 measured runs. So these tests assert absences as much as presences: an absence
 * is what regresses silently.
 *
 * The prompt no longer carries *only* the deterministic explanation. It also carries the
 * code-detected facts for the ply — move class, centipawn loss, motifs — because a prompt holding
 * one finished sentence can only produce a reworded copy of that sentence. Everything copyable in
 * there is a fact from `MoveAssessment`, which is the distinction that matters: the model may not
 * invent, but it now has something to reason across.
 */
class MoveCoachPromptBuilderTest {

    private val request = MoveCoachRequest(
        moveUci = "g1f3",
        moveDisplay = "Nf3",
        deterministicHeadline = "Good — Nf3",
        deterministicExplanation = "You played Nf3. It develops a piece to an active square.",
        engineDifficultyName = "Medium",
    )

    @Test
    fun `user prompt carries the explanation to rewrite`() {
        val prompt = MoveCoachPromptBuilder.userPrompt(request)
        assertTrue(request.deterministicExplanation in prompt, prompt)
    }

    @Test
    fun `prompt carries no JSON schema`() {
        // The schema-echo regression: showing the model a shape made the shape the answer.
        val built = MoveCoachPromptBuilder.build(request)
        val whole = built.systemPrompt + "\n" + built.userPrompt

        assertFalse("{" in whole, "no JSON in the prompt: $whole")
        assertFalse("headline" in whole.lowercase(), "no schema field names in the prompt: $whole")
    }

    @Test
    fun `prompt carries no examples for the model to copy`() {
        val built = MoveCoachPromptBuilder.build(request)
        val whole = (built.systemPrompt + "\n" + built.userPrompt).lowercase()

        assertFalse("example" in whole, "examples are echo bait: $whole")
        assertFalse(
            MoveCoachPromptBuilder.GENERIC_FILLER.lowercase() in whole,
            "the filler is a validator rule now, not a counter-example in the prompt: $whole",
        )
    }

    @Test
    fun `prompt does not leak the headline the model must not write`() {
        // The headline is computed in code from the assessment. Putting it in the prompt would hand
        // the model a string to return unchanged — exactly how the placeholder shipped.
        val built = MoveCoachPromptBuilder.build(request)
        assertFalse(request.deterministicHeadline in built.userPrompt, built.userPrompt)
    }

    @Test
    fun `generation stays inside the panel budget`() {
        val built = MoveCoachPromptBuilder.build(request)

        assertEquals(MoveCoachPromptBuilder.MAX_OUTPUT_TOKENS_STRICT, built.maxOutputTokens)
        // Low but non-zero: the task is rewriting, not inventing.
        assertTrue(built.temperature in 0.0..0.5, "temperature was ${built.temperature}")
    }

    @Test
    fun `system prompt forbids invention rather than inviting free analysis`() {
        // This used to assert the word "rewrite". The fence it protects is "the model narrates, it
        // does not analyse" — but the user prompt stopped being a rewrite task, so the system
        // prompt was telling the model to reword one sentence while the user prompt told it to
        // reason across several facts. The fence is now stated directly instead.
        val system = MoveCoachPromptBuilder.build(request).systemPrompt.lowercase()

        assertTrue("only the facts" in system, system)
        assertTrue("never invent" in system, system)
        assertFalse("rewrite" in system, "the two prompts must not disagree about the task: $system")
    }

    @Test
    fun `user prompt states the same no-invention rule as the system prompt`() {
        val prompt = MoveCoachPromptBuilder.userPrompt(request).lowercase()
        assertTrue("do not invent" in prompt, prompt)
    }

    @Test
    fun `the assessment reaches the prompt so the model has something to reason about`() {
        // Without these the prompt holds one finished sentence, and a rewording of that sentence is
        // the only answer available. `MoveCoachRequest` carried the move the whole time and the
        // prompt used none of it, while the validator graded the answer on squares the model had
        // never been shown.
        val prompt = MoveCoachPromptBuilder.userPrompt(
            request.copy(
                moveClassName = "BLUNDER",
                centipawnLoss = 240,
                motifs = listOf("fork"),
            ),
        )

        assertTrue("Nf3" in prompt, prompt)
        assertTrue("blunder" in prompt, "the move class is lowercased into prose: $prompt")
        assertTrue("240" in prompt, prompt)
        assertTrue("fork" in prompt, prompt)
    }

    @Test
    fun `an unassessed move degrades to the baseline explanation alone`() {
        // No engine attached means no MoveAssessment, so the extra lines must vanish rather than
        // appear empty — "Engine assessment of that move: ." is one more contentless line to copy.
        val prompt = MoveCoachPromptBuilder.userPrompt(request).lowercase()

        assertFalse("engine assessment" in prompt, prompt)
        assertFalse("centipawns" in prompt, prompt)
        assertFalse("tactical features" in prompt, prompt)
    }

    @Test
    fun `a zero centipawn loss is not reported as a loss`() {
        // BEST moves carry cpLoss = 0. "It gives up about 0 centipawns" invites the model to say
        // the move lost something.
        val prompt = MoveCoachPromptBuilder.userPrompt(request.copy(centipawnLoss = 0)).lowercase()

        assertFalse("centipawns" in prompt, prompt)
    }

    @Test
    fun `motifs are spelled out instead of pasted in as corpus slugs`() {
        val prompt = MoveCoachPromptBuilder.userPrompt(
            request.copy(motifs = listOf("discovered-attack", "hangs-piece")),
        )

        assertTrue("discovered attack" in prompt, prompt)
        assertTrue("hanging piece" in prompt, prompt)
        assertFalse("discovered-attack" in prompt, "a slug in the prompt is a slug in the panel: $prompt")
        assertFalse("hangs-piece" in prompt, prompt)
    }

    @Test
    fun `every motif the detector can emit is readable in the prompt`() {
        // The general rule, not the lookup table, is what makes this hold: adding a motif to
        // MotifDetector must not require remembering that this function exists.
        com.example.myapplication.MotifDetector.ALL_MOTIFS.forEach { motif ->
            val phrase = MoveCoachPromptBuilder.humanizeMotif(motif)
            assertFalse('-' in phrase, "$motif rendered as $phrase")
            assertFalse('_' in phrase, "$motif rendered as $phrase")
            assertTrue(phrase.isNotBlank(), motif)
        }
    }

    @Test
    fun `recently used openings are banned by name`() {
        val built = MoveCoachPromptBuilder.build(
            request.copy(bannedOpeningFrames = listOf("This move develops", "Nice job on")),
        )

        assertTrue("This move develops" in built.systemPrompt, built.systemPrompt)
        assertTrue("Nice job on" in built.systemPrompt, built.systemPrompt)
    }

    @Test
    fun `the ban instruction is absent on the first move of a game`() {
        // Nothing has been said yet, so an empty ban list must not leave a dangling instruction —
        // "do not start with ''" is one more contentless sentence for a 270M model to copy.
        val system = MoveCoachPromptBuilder.build(request).systemPrompt

        assertFalse("Do not start" in system, system)
        assertEquals(MoveCoachPromptBuilder.systemPrompt(emptyList()), system)
    }
}
