package com.example.ondeviceai

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class RulesQaResponseValidatorTest {

    @Test
    fun `fake lookup passage id grounds a cited answer`() = runTest {
        val lookup = RuleLookupTool {
            listOf(RulePassage("en-passant", "En passant", "Available only immediately."))
        }
        val retrieved = lookup.lookup("When may I capture en passant?")

        val result = RulesQaResponseValidator.validate(
            text = "The capture is available only on the next move. [en-passant]",
            retrievedPassageIds = retrieved.map { it.id },
        )

        assertIs<RulesQaResponseValidator.Result.Valid>(result)
    }

    @Test
    fun `accepts answer citing a passage actually retrieved`() {
        val result = RulesQaResponseValidator.validate(
            text = "A king may not castle through an attacked square. [castling-check]",
            retrievedPassageIds = listOf("castling-check"),
        )

        assertIs<RulesQaResponseValidator.Result.Valid>(result)
    }

    @Test
    fun `accepts an uncited answer that is anchored to its passage`() {
        // The device failure this rule exists for: gemma3-270m answers correctly and simply does not
        // echo the bracketed id. Rejecting that discarded good answers over a formatting miss.
        val result = RulesQaResponseValidator.validate(
            text = "A king may not castle through check.",
            retrievedPassageIds = listOf("castling-check"),
        )

        val valid = assertIs<RulesQaResponseValidator.Result.Valid>(result)
        // The citation is derived from the overlap, so `Sources:` still names the right rule.
        assertEquals(listOf("castling-check"), valid.citedPassageIds)
    }

    @Test
    fun `only the best-anchored passage is cited`() {
        // Measured on device: this answer shared 22 content words with draw-dead-position and
        // exactly two — "king" and "two" — with castling-requirements, and Sources: credited both.
        val result = RulesQaResponseValidator.validate(
            text = "Yes, the game is drawn when no possible legal sequence can lead to checkmate. " +
                "Common insufficient-material examples include king against king (two kings, only " +
                "kings remain) and king plus a lone bishop or knight against King.",
            retrievedPassageIds = listOf("draw-dead-position", "castling-requirements"),
        )

        val valid = assertIs<RulesQaResponseValidator.Result.Valid>(result)
        assertEquals(listOf("draw-dead-position"), valid.citedPassageIds)
    }

    @Test
    fun `rejects an answer sharing nothing with the retrieved passage`() {
        // Anchoring is kept above zero for the reason PositionChatValidator gives: with no anchor,
        // any fluent invention validates.
        val result = RulesQaResponseValidator.validate(
            text = "Sure! Here you go.",
            retrievedPassageIds = listOf("castling-check"),
        )

        assertIs<RulesQaResponseValidator.Result.Invalid>(result)
    }

    @Test
    fun `rejects citation not returned by lookup`() {
        val result = RulesQaResponseValidator.validate(
            text = "That is a draw. [draw-fifty-move]",
            retrievedPassageIds = listOf("draw-repetition"),
        )

        assertIs<RulesQaResponseValidator.Result.Invalid>(result)
    }
}
