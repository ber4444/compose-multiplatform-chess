package com.example.ondeviceai

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
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
    fun `rejects answer without a passage citation`() {
        val result = RulesQaResponseValidator.validate(
            text = "A king may not castle through check.",
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
