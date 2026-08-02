package com.example.evals

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FluencyScorerTest {

    @Test
    fun `scores readability within target grade level for clean coaching text`() {
        val text = "Nf3 develops the knight toward the center and protects the king."
        val (gradeLevel, passes) = FluencyScorer.scoreReadability(text, maxGradeLevel = 6.0)
        assertTrue(passes, "Expected grade level <= 6.0 but got $gradeLevel")
    }

    @Test
    fun `surface bounds are ordered coach tightest, and admit their own deterministic floor`() {
        val coach = FluencyScorer.FluencySurface.MOVE_COACH.maxGradeLevel
        val opening = FluencyScorer.FluencySurface.OPENING.maxGradeLevel
        val chat = FluencyScorer.FluencySurface.CHAT.maxGradeLevel
        assertTrue(coach < opening, "Move coach must stay the tightest bound ($coach vs $opening)")
        assertTrue(opening <= chat, "Opening bound should not exceed chat's ($opening vs $chat)")

        // The measured deterministic floors these bounds were calibrated against
        // (docs/benchmarks/on-device-ai/fluency-calibration.md). If a composer's wording drifts
        // past its bound, recalibrate — don't widen the bound to make this pass.
        assertTrue(coach >= 5.2 + 1.0, "Coach bound lost its headroom over the 5.2 floor")
        assertTrue(opening >= 12.4 + 1.0, "Opening bound lost its headroom over the 12.4 p90 floor")
        assertTrue(chat >= 12.5 + 1.0, "Chat bound lost its headroom over the 12.5 p90 floor")
    }

    @Test
    fun `chat scoring reports fluency instead of silently defaulting to compliant`() {
        // scoreChat used to compute the result and drop it, so every chat route read 0% violation.
        val dense = "Prophylactic restraint of counterplay necessitates reevaluating " +
            "positional considerations underlying incremental accumulation of advantages."
        val score = EvalScorer.scoreChat(
            ChatTurnFixture(userMessage = "why?", expectedConcepts = listOf("counterplay")),
            dense,
        )
        assertTrue(score.readingGrade > 0.0, "Reading grade should be measured, not left at 0")
        assertFalse(score.fluencyCompliant, "Dense prose at grade ${score.readingGrade} should fail CHAT's bound")
    }

    @Test
    fun `syllable counter strips at most one inflection`() {
        // Chained removeSuffix("es")/("s")/("e") turned "pieces" into "piec" and scored it 1.
        assertTrue(FluencyScorer.countSyllables("pieces") >= 2, "pieces should score at least 2 syllables")
        assertTrue(FluencyScorer.countSyllables("moves") == 1, "moves is one syllable")
    }

    @Test
    fun `rejects person praise in favor of process praise`() {
        assertFalse(FluencyScorer.scoreProcessPraise("You are a genius for playing Nf3!"))
        assertTrue(FluencyScorer.scoreProcessPraise("Nf3 is a strong move controlling the center."))
    }

    @Test
    fun `requires criticism to carry next step advice`() {
        assertFalse(FluencyScorer.scoreCriticismNextStep("Playing e5 was a terrible blunder."))
        assertTrue(FluencyScorer.scoreCriticismNextStep("Playing e5 was a blunder; try Nf3 instead to control the center."))
    }

    @Test
    fun `rejects conversational self reference phrases using word boundaries`() {
        assertFalse(FluencyScorer.scoreNoSelfReference("I see that you played Nf3."))
        assertFalse(FluencyScorer.scoreNoSelfReference("As an AI, I notice your move."))
        // "multi seeded" should NOT match "i see" because of \b word boundaries
        assertTrue(FluencyScorer.scoreNoSelfReference("Multi seeded positions are fun."))
        assertTrue(FluencyScorer.scoreNoSelfReference("Nf3 develops the knight and controls e5."))
    }

    @Test
    fun `full fluency evaluation passes clean coaching output`() {
        val text = "Nf3 develops your knight to contest central squares."
        val result = FluencyScorer.evaluate(text)
        assertTrue(result.isCompliant, "Expected compliant fluency result but got violations: ${result.violations}")
    }
}
