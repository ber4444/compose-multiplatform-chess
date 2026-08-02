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
