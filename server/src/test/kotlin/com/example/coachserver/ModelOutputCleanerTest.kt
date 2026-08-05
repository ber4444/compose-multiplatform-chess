package com.example.coachserver

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The cleaner must remove deliberation without ever rewriting an answer. A cleaner that guessed
 * which prose was "really" the answer would manufacture passes and make the eval score itself.
 */
class ModelOutputCleanerTest {

    @Test
    fun `a think block is removed`() {
        val raw = "<think>The user wants two sentences. Let me check the sources.</think>\n" +
            "The centre is contested [a]. Development follows [b]."

        assertEquals("The centre is contested [a]. Development follows [b].", ModelOutputCleaner.clean(raw))
    }

    @Test
    fun `an unclosed think block running to the end is removed`() {
        // Hitting the token ceiling mid-deliberation leaves no closing tag.
        val raw = "The centre is contested [a]. Development follows [b].\n<think>Now let me verify"

        assertEquals("The centre is contested [a]. Development follows [b].", ModelOutputCleaner.clean(raw))
    }

    @Test
    fun `a json code fence is unwrapped`() {
        val raw = "```json\nThe centre is contested [a]. Development follows [b].\n```"

        assertEquals("The centre is contested [a]. Development follows [b].", ModelOutputCleaner.clean(raw))
    }

    @Test
    fun `leading scratchpad lines observed in production are dropped`() {
        // Verbatim shape from `fly logs`, 2026-08-04.
        val raw = " - \"From here, 3. d5 leads to the Anti-Grünfeld, Advance Variation\" -> [lichess-e-233-e60]\n" +
            "The King's Indian cedes the centre [lichess-e-233-e60]. Black then strikes back [lichess-e-237-e60]."

        val cleaned = ModelOutputCleaner.clean(raw)

        assertTrue(cleaned.startsWith("The King's Indian"), "Scratchpad survived: $cleaned")
        assertTrue("->" !in cleaned)
    }

    @Test
    fun `prose is never rewritten`() {
        val answer = "In the Sicilian, Black plays 1...c5 to trade a flank pawn [a]. " +
            "White can respond with 2. Ke2 [b]."

        assertEquals(answer, ModelOutputCleaner.clean(answer))
    }

    @Test
    fun `an all-scratchpad response is left intact so it fails as invalid, not empty`() {
        // Reporting "empty response" would hide the real cause. The validator should see the text
        // and reject it on its merits.
        val raw = "]\" -> wait, \"3. Qb3\" is in [lichess-d-145-d06]."

        assertEquals(raw, ModelOutputCleaner.clean(raw))
    }

    @Test
    fun `a note appearing mid-answer is not removed`() {
        // Only a *leading* preamble is dropped; cutting from the middle would be editing the answer.
        val raw = "The centre is contested [a].\n- a bullet in the middle\nDevelopment follows [b]."

        assertTrue("bullet in the middle" in ModelOutputCleaner.clean(raw))
    }
}
