package com.example.coachserver

import com.example.coachapi.Passage
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The objective floor under `OpeningExplanationValidator`: does a retrieved passage carry enough
 * distinct vocabulary for a *satisfiable* answer?
 *
 * The validator requires 2–3 sentences, each citing a passage and each sharing **at least two**
 * content words (≥4 chars, non-stopword) with the passage it cites. That is a constraint on the
 * corpus as much as on the writer. With the old passage shape — `"Ruy Lopez is classified as ECO
 * C60. A representative move sequence is 1. e4 e5 2. Nf3 Nc6 3. Bb5."` — a passage carried five
 * content words, four of which (`classified`, `representative`, `sequence`, `move`) were identical
 * in every one of the ~3,800 rows. That left **one** distinctive word. Two different sentences
 * both clearing a two-word bar from a one-word distinctive vocabulary have to either repeat each
 * other or talk about the ECO classification, which is exactly the single-cited-sentence collapse
 * the provider LLMs were blamed for.
 *
 * So this test pins the substrate, not the model: if a future corpus edit drops passages back
 * below the bar, the per-sentence citation rule silently becomes unsatisfiable again and the LLM
 * composer's fallback rate goes to 100% for a reason that has nothing to do with the provider.
 */
class CorpusCitabilityProbe {

    /** Mirrors OpeningExplanationValidator's own stop list. */
    private val stopWords = setOf(
        "a", "an", "and", "are", "as", "at", "be", "because", "by", "for", "from", "in", "is",
        "it", "of", "on", "or", "that", "the", "this", "to", "with", "your",
    )

    /**
     * Boilerplate shared by every lichess row. These count toward the validator's overlap bar but
     * carry no information, so they are excluded when measuring what a passage actually says.
     */
    private val boilerplate = setOf("classified", "representative", "sequence", "move", "moves")

    private fun contentTokens(passage: Passage): Set<String> =
        Regex("[a-z0-9]+").findAll("${passage.title} ${passage.text}".lowercase())
            .map { it.value }
            .filter { it.length >= 4 && it !in stopWords }
            .toSet()

    @Test
    fun `every corpus passage carries enough distinctive vocabulary to cite twice`() {
        val entries = SeedMain.loadCorpus(java.nio.file.Path.of("corpus"))
            .filter { it.eco != null }

        val starved = entries.filter { entry ->
            (contentTokens(entry.passage) - boilerplate).size < MIN_DISTINCTIVE_TOKENS
        }

        assertTrue(
            starved.isEmpty(),
            "${starved.size} passages carry fewer than $MIN_DISTINCTIVE_TOKENS distinctive content " +
                "words, so no two sentences citing them can both clear the validator's two-word " +
                "overlap bar without repeating. First few: " +
                starved.take(3).joinToString("; ") { it.passage.title },
        )
    }

    @Test
    fun `the old passage shape would fail this floor`() {
        // Guards the test itself: if this ever passes, the floor is set too low to detect the
        // regression it exists to detect.
        val old = Passage(
            sourceId = "lichess-c-1013-c60",
            title = "C60 — Ruy Lopez",
            text = "Ruy Lopez is classified as ECO C60. A representative move sequence is 1. e4 e5 2. Nf3 Nc6 3. Bb5.",
        )

        val distinctive = contentTokens(old) - boilerplate

        assertTrue(
            distinctive.size < MIN_DISTINCTIVE_TOKENS,
            "Expected the pre-fix passage shape to be starved, but it carried $distinctive",
        )
    }

    private companion object {
        /**
         * Two sentences × two overlapping words each, with no reuse between them. Four is the
         * arithmetic minimum for a non-repetitive two-sentence answer; the bar is set there
         * deliberately rather than at a comfortable number, so it measures satisfiability rather
         * than taste.
         */
        const val MIN_DISTINCTIVE_TOKENS = 4
    }
}
