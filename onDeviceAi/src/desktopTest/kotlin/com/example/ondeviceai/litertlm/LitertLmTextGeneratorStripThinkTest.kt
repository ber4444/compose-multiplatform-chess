package com.example.ondeviceai.litertlm

import com.example.ondeviceai.AiGenerationRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import com.example.ondeviceai.litertlm.LitertLmTextGenerator.Companion.stripThinkBlocks

/**
 * Tests for [LitertLmTextGenerator.stripThinkBlocks] — the CoT-stripping that
 * keeps the Move Coach from leaking Qwen3's `<think>` deliberation to the user.
 *
 * Desktop-only because LitertLmTextGenerator is desktop-only. The cases mirror
 * the real outputs observed from the LiteRT-LM driver: a leading `<think>…</think>`
 * block followed by the final answer, and the unterminated edge case.
 */
class LitertLmTextGeneratorStripThinkTest {

    @Test
    fun `strips a closed think block and keeps the answer`() {
        val raw = "<think>Let me consider this move. Does it give check? No.</think>\nNf3 develops the knight."
        assertEquals("Nf3 develops the knight.", stripThinkBlocks(raw))
    }

    @Test
    fun `strips think block with newlines inside`() {
        val raw = "<think>\nOkay, the move is g1 to h3.\nThis develops the knight.\n</think>\n\nNh3 develops the knight toward the edge."
        assertEquals("Nh3 develops the knight toward the edge.", stripThinkBlocks(raw))
    }

    @Test
    fun `strips an unterminated trailing think block`() {
        // A generation cut off mid-reasoning: <think> opened but never closed.
        val raw = "Nh3 develops the knight. <think>Wait, let me reconsider whether"
        assertEquals("Nh3 develops the knight.", stripThinkBlocks(raw))
    }

    @Test
    fun `returns_text_unchanged_when_no_think_block`() {
        val raw = "Nf3 develops the knight toward the center."
        assertEquals("Nf3 develops the knight toward the center.", stripThinkBlocks(raw))
    }

    @Test
    fun `handles_case_insensitive_think_tag`() {
        val raw = "<THINK>reasoning</THINK>Answer."
        assertEquals("Answer.", stripThinkBlocks(raw))
    }

    @Test
    fun `returns_empty_string_for_input_that_was_only_think`() {
        assertEquals("", stripThinkBlocks("<think>all reasoning, no answer</think>"))
    }
}

class LitertLmTokenBudgetTest {
    private fun req(max: Int) = AiGenerationRequest(
        systemPrompt = "s", userPrompt = "u", maxOutputTokens = max,
    )

    @Test
    fun `an answer-sized budget is floored, not applied verbatim`() {
        // MoveCoachPromptBuilder sends 384, sized for the answer. Applying that to a model that
        // opens with a <think> block is exactly how the Android attempt cut every generation off
        // mid-thought and stripped the result to nothing.
        assertEquals(
            LitertLmTextGenerator.REASONING_TOKEN_FLOOR,
            LitertLmTextGenerator.maxTokensFor(req(384)),
        )
    }

    @Test
    fun `a caller asking for more than the floor gets what it asked for`() {
        assertEquals(4096, LitertLmTextGenerator.maxTokensFor(req(4096)))
    }

    @Test
    fun `the ceiling is a real bound, not unlimited`() {
        // The bug this replaced: desktop ignored maxOutputTokens entirely, so generation had no
        // stop condition of its own at all.
        assertTrue(LitertLmTextGenerator.maxTokensFor(req(1)) < Int.MAX_VALUE)
    }
}
