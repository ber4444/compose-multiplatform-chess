package com.example.ondeviceai

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The invariant these pin: **a successful retrieval can never come out as `RulesQaFallback`.**
 *
 * `RuleLookupToolTest` already pinned "Game is a draw when only kings remain?" to
 * `draw-dead-position` and passed, while the shipped Android screen answered "I couldn't verify
 * that rule from the offline reference." Both were true at once because retrieval was never the
 * failing step: the answer was thrown away afterwards, when the ~270M model did not reproduce an
 * exact `[passage-id]` and the validator rejected the turn.
 *
 * A retrieval test cannot catch that, and `StructuredOutputRulesQaAnswerer` is `androidMain` with no
 * test source set, so nothing could. The decision now lives in `commonMain` and is asserted here on
 * every target.
 */
class RulesQaGroundingTest {

    private val passage = RulePassage(
        id = "draw-dead-position",
        title = "Draw by dead position and insufficient material",
        text = "The game is drawn when no possible legal sequence can lead to checkmate.",
    )

    @Test
    fun `the passage alone is a valid cited answer`() {
        val text = RulesQaGrounding.composeFromPassages(listOf(passage))

        assertTrue(text.contains("[draw-dead-position]"), text)
        assertTrue(text.contains("no possible legal sequence"), text)
        assertIs<RulesQaResponseValidator.Result.Valid>(
            RulesQaResponseValidator.validate(text, listOf(passage.id)),
        )
    }

    @Test
    fun `the title is not prefixed, because Sources already names the rule`() {
        val text = RulesQaGrounding.composeFromPassages(listOf(passage))

        // Otherwise the screen reads "Draw by dead position and insufficient material: The game is
        // drawn…" directly above "Sources: Draw by dead position and insufficient material".
        assertFalse(text.contains(passage.title), text)
        assertTrue(text.startsWith("The game is drawn"), text)
    }

    @Test
    fun `no passages composes to nothing, which is the one honest fallback`() {
        assertEquals("", RulesQaGrounding.composeFromPassages(emptyList()))
        // Empty text + no ids is exactly what should reach RulesQaFallback: nothing was found.
        assertIs<RulesQaResponseValidator.Result.Invalid>(
            RulesQaResponseValidator.validate("", emptyList()),
        )
    }

    @Test
    fun `an over-long passage is trimmed to the validator budget and still cites`() {
        val long = passage.copy(text = "word ".repeat(400))

        val text = RulesQaGrounding.composeFromPassages(listOf(long))

        assertTrue(text.length <= RulesQaResponseValidator.MAX_OUTPUT_CHARS, "${text.length}")
        assertIs<RulesQaResponseValidator.Result.Valid>(
            RulesQaResponseValidator.validate(text, listOf(long.id)),
        )
    }

    @Test
    fun `model wording is kept when it cites`() {
        val model = "Yes — with only kings on the board neither side can mate [draw-dead-position]."

        assertEquals(model, RulesQaGrounding.answerOrReference(model, listOf(passage)))
    }

    @Test
    fun `uncited wording is kept when it is anchored to the passage`() {
        // The observed 270M failure: fluent, correct, and with no bracketed id anywhere. This is now
        // an accepted answer rather than a discarded one — the citation is derived from the overlap.
        val model = "Yes, that is a draw because neither side has enough material to checkmate."

        assertEquals(model, RulesQaGrounding.answerOrReference(model, listOf(passage)))
    }

    @Test
    fun `wording that shares nothing with the passage falls back to it`() {
        val model = "Sure! Here you go."

        val text = RulesQaGrounding.answerOrReference(model, listOf(passage))

        assertTrue(text.contains("[draw-dead-position]"), text)
        assertIs<RulesQaResponseValidator.Result.Valid>(
            RulesQaResponseValidator.validate(text, listOf(passage.id)),
        )
    }

    @Test
    fun `an empty generation still answers from the corpus`() {
        val text = RulesQaGrounding.answerOrReference("", listOf(passage))

        assertIs<RulesQaResponseValidator.Result.Valid>(
            RulesQaResponseValidator.validate(text, listOf(passage.id)),
        )
    }

    @Test
    fun `the reported question survives the whole path with a useless model`() = runTest {
        // End to end over the *real* bundled corpus, with the model contributing nothing usable —
        // the exact condition under which the screenshot showed the fallback.
        val passages = BundledRuleLookupTool().lookup("Game is a draw when only kings remain?")
        assertEquals("draw-dead-position", passages.first().id)

        val text = RulesQaGrounding.answerOrReference("Okay! Sure thing.", passages)

        val validation = RulesQaResponseValidator.validate(text, passages.map { it.id })
        assertIs<RulesQaResponseValidator.Result.Valid>(validation)
        assertTrue(text.contains("checkmate"), text)
    }
}
