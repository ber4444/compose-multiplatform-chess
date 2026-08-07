package com.example.ondeviceai

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Rewritten for the prose prompt. The previous version asserted the presence of `STYLE_EXAMPLES`
 * and `EXAMPLE_LABEL`; both are gone, and their removal is the point.
 *
 * Three on-device failures, in order, all the same bug: a 270M model copies the nearest text in its
 * context. It emitted a style example verbatim, then the `Bad:` counter-example added to forbid
 * that, then — once the examples were replaced by a JSON schema — the schema's own placeholder
 * strings, in 5 of 5 measured runs. The prompt now carries exactly one thing worth copying: the
 * deterministic explanation we actually want rewritten.
 *
 * So these tests assert absences as much as presences. An absence is what regresses silently.
 */
class MoveCoachPromptBuilderTest {

    private val request = MoveCoachRequest(
        moveUci = "g1f3",
        moveDisplay = "Nf3",
        deterministicHeadline = "Good — Nf3",
        deterministicExplanation = "Engine choice: Nf3. It develops a piece to an active square.",
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
    fun `system prompt asks for a rewrite rather than chess analysis`() {
        val system = MoveCoachPromptBuilder.build(request).systemPrompt.lowercase()
        assertTrue("rewrite" in system, system)
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
