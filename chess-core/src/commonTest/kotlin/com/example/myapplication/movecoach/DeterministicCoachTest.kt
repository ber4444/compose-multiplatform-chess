package com.example.myapplication.movecoach

import com.example.myapplication.MoveAssessment
import com.example.myapplication.MoveClass
import com.example.myapplication.MoveRecord
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Replaces the deleted `MoveCoachFallbackTest`, which drove headlines off `deterministicTags`
 * (`TAG_CHECKMATE`, `TAG_CHECK`, …). That input model is gone: headlines now come from
 * [MoveAssessment]'s `moveClass` + motifs, so the old cases could not be ported mechanically.
 *
 * This matters more than it used to, not less. Per the RAG-3 kill criterion the deterministic text
 * is the *shipped* per-move line whenever the model loses on the scorecard — so these two functions
 * are the product, not a degraded mode.
 */
class DeterministicCoachTest {

    private fun record(
        san: String = "Nf3",
        uci: String = "g1f3",
        assessment: MoveAssessment? = null,
    ) = MoveRecord(uci = uci, san = san, fenAfter = "", assessment = assessment)

    private fun assessment(
        moveClass: MoveClass = MoveClass.GOOD,
        motifs: List<String> = emptyList(),
        cpPlayed: Int = 0,
    ) = MoveAssessment(
        cpBefore = 0,
        cpPlayed = cpPlayed,
        cpBest = 0,
        cpLoss = 0,
        moveClass = moveClass,
        motifs = motifs,
    )

    // --- headline ---------------------------------------------------------------------------

    @Test
    fun `headline degrades to the move when there is no assessment`() {
        // Backfill hasn't run, or the engine was unavailable — must still say something true.
        assertEquals("You played Nf3", DeterministicCoach.buildHeadline(record()))
    }

    @Test
    fun `headline pairs the class with the top motif`() {
        val headline = DeterministicCoach.buildHeadline(
            record(assessment = assessment(MoveClass.BLUNDER, listOf("hangs-piece"))),
        )
        assertEquals("Blunder — hangs a piece", headline)
    }

    @Test
    fun `headline finds a recognized motif behind an unmapped one`() {
        // Order-independence. buildHeadline used to take motifs.first() blindly, so a leading
        // unmapped entry suppressed the real tactic: every case in the eval golden set is tagged
        // "opening" first, and every headline came out as the bare move. buildExplanation used
        // contains() and was never order-dependent, so the two halves disagreed.
        val headline = DeterministicCoach.buildHeadline(
            record(assessment = assessment(MoveClass.BLUNDER, listOf("opening", "hangs-piece"))),
        )
        assertEquals("Blunder — hangs a piece", headline)
    }

    @Test
    fun `headline falls back to the move when every motif is unmapped`() {
        val headline = DeterministicCoach.buildHeadline(
            record(assessment = assessment(MoveClass.MISTAKE, listOf("opening", "some-future-motif"))),
        )
        assertEquals("Mistake — Nf3", headline)
    }

    @Test
    fun `headline uses uci when san is blank`() {
        val headline = DeterministicCoach.buildHeadline(
            record(san = "", assessment = assessment(MoveClass.BEST)),
        )
        assertEquals("Best move — g1f3", headline)
    }

    @Test
    fun `every move class renders a distinct label`() {
        // Guards the `when` in buildHeadline: a new MoveClass must be given a label, not fall
        // through to something generic.
        val labels = MoveClass.entries.map {
            DeterministicCoach.buildHeadline(record(assessment = assessment(it)))
        }
        assertEquals(labels.size, labels.toSet().size, "two MoveClass values render identically: $labels")
    }

    // --- explanation ------------------------------------------------------------------------

    @Test
    fun `explanation degrades without an assessment`() {
        assertEquals(
            "It is a standard choice for this position.",
            DeterministicCoach.buildExplanation(record()),
        )
    }

    @Test
    fun `motif priority puts hangs-piece ahead of positional motifs`() {
        // The `when` is first-match-wins and ordered by severity: a hung piece is the thing to say,
        // even if the move also develops.
        val explanation = DeterministicCoach.buildExplanation(
            record(assessment = assessment(motifs = listOf("develops", "hangs-piece"))),
        )
        assertTrue("undefended" in explanation, explanation)
    }

    @Test
    fun `explanation falls back to the evaluation when no motif matches`() {
        val white = DeterministicCoach.buildExplanation(
            record(assessment = assessment(cpPlayed = 300)),
        )
        assertTrue("White is measurably better" in white, white)

        val balanced = DeterministicCoach.buildExplanation(
            record(assessment = assessment(cpPlayed = 10)),
        )
        assertTrue("roughly balanced" in balanced, balanced)
    }

    @Test
    fun `explanation never exceeds the 300-char panel cap`() {
        // The cap is a layout constraint — the panel sits under a board on a phone. A Nano run was
        // rejected at 314 chars for repeating itself; the deterministic path must not do the same.
        val explanation = DeterministicCoach.buildExplanation(
            record(san = "N".repeat(400), assessment = assessment(motifs = listOf("hangs-piece"))),
        )
        assertTrue(explanation.length <= 300, "was ${explanation.length}: $explanation")
        assertTrue(explanation.endsWith("…"), explanation)
    }
}
