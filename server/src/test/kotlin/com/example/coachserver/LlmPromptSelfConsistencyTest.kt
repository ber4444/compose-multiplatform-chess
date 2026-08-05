package com.example.coachserver

import com.example.coachapi.OpeningExplainRequest
import com.example.coachapi.Passage
import kotlin.test.Test
import kotlin.test.assertNotNull

/**
 * Does the prompt's own worked example pass the validator the output is judged by?
 *
 * `LlmComposer.userPrompt` generates an "Example of the required format" from the retrieved
 * passages. If that example is itself rejected, then the composer is showing the model a
 * non-compliant target and grading it against a different rule — and a 100% fallback rate says
 * nothing about the provider, because no provider that followed instructions perfectly could pass.
 *
 * This needs no API key: the example is deterministic given the passages.
 */
class LlmPromptSelfConsistencyTest {

    /** Mirrors the eval harness's `openingConceptsPassage`, which is what the LLM row retrieves. */
    private fun evalPassage(caseId: String, eco: String) = Passage(
        sourceId = "eval-$caseId",
        title = "$eco opening concepts",
        text = "This opening's key ideas are contesting the center, developing the minor pieces. " +
            "Both sides fight for central squares with their pawns and develop their minor pieces " +
            "toward active squares. King safety matters: players castle early to shield the king " +
            "and connect the rooks. Piece development, central control, and king safety are the " +
            "main themes.",
    )

    @Test
    fun `the example the prompt tells the model to imitate passes the validator`() {
        val passages = listOf(evalPassage("case-1", "C20"))
        val request = OpeningExplainRequest(fen = "fen", movesSan = listOf("e4", "e5"), eco = "C20")

        val example = LlmComposer.exampleOutputFor(request, passages)

        assertNotNull(
            OpeningExplanationValidator.validate(example, passages),
            "The prompt's own example is rejected by the validator, so the instructions and the " +
                "grading rule disagree. Example was:\n$example\n" +
                "Reason: ${OpeningExplanationValidator.rejectionReason(example, passages)}",
        )
    }

    @Test
    fun `a well formed hand written answer passes`() {
        // Independent of the generated example: if this fails too, the rule is unsatisfiable
        // against the eval passages regardless of what any model writes.
        val passages = listOf(evalPassage("case-1", "C20"))
        val answer = "Both sides fight for central squares with their pawns [eval-case-1]. " +
            "Players castle early to shield the king and connect the rooks [eval-case-1]."

        assertNotNull(
            OpeningExplanationValidator.validate(answer, passages),
            "Reason: ${OpeningExplanationValidator.rejectionReason(answer, passages)}",
        )
    }
}
