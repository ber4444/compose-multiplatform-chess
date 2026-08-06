package com.example.evals

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The `book-retrieval` row is AUTOMATED, which means it can fail the build — so it has to be shown
 * both passing and failing. A criterion never observed failing is untested, and this project has
 * already published one number that could only ever come out clean.
 */
class BookRetrievalEvalTest {

    private fun case(id: String, eco: String, moves: List<String>) = GoldenCase(
        id = id,
        fen = "8/8/8/8/8/8/8/8 w - - 0 1",
        bestMoveUci = "e2e4",
        tags = emptyList(),
        eco = eco,
        movesSan = moves,
    )

    @Test
    fun `correctly labelled openings produce no violation`() {
        val stats = evaluateBookRetrieval(
            listOf(
                case("sicilian", "B20", listOf("e4", "c5")),
                case("french", "C00", listOf("e4", "e6")),
                case("ruy-lopez", "C60", listOf("e4", "e5", "Nf3", "Nc6", "Bb5")),
            ),
            corpusDirectory = CORPUS_DIRECTORY,
        )

        assertEquals(3, stats.cases)
        assertEquals(0, stats.groundingViolations)
    }

    @Test
    fun `an opening resolved to the wrong ECO is a violation`() {
        // The failure this gate is for, staged: retrieval that answers 1.e4 c5 with a Catalan is
        // exactly what shipped, and it was fluent, cited and validator-approved on the way out.
        val stats = evaluateBookRetrieval(
            listOf(case("sicilian-mislabelled", "E06", listOf("e4", "c5"))),
            corpusDirectory = CORPUS_DIRECTORY,
        )

        assertEquals(1, stats.groundingViolations)
        assertTrue(stats.note.contains("resolved B20"), "the note must name what came back: ${stats.note}")
    }

    @Test
    fun `a position with no moves cannot be identified and counts as a violation`() {
        // Not a book miss to shrug at: the opening explainer is only ever invoked on a finished
        // game, so an empty move list means the request lost its history on the way in.
        val stats = evaluateBookRetrieval(
            listOf(case("no-moves", "B20", emptyList())),
            corpusDirectory = CORPUS_DIRECTORY,
        )

        assertEquals(1, stats.groundingViolations)
        assertTrue(stats.note.contains("resolved nothing"), stats.note)
    }

    @Test
    fun `the shipped golden set resolves every case`() {
        // The real gate, run against the real corpus and the real 100 cases — the same call
        // main() makes. If this needs relaxing, the corpus or the normalizer changed.
        val stats = evaluateBookRetrieval(
            GoldenCaseLoader.load(java.nio.file.Path.of("golden/candidates.json")).filter { it.eco != null },
        )

        assertEquals(100, stats.cases)
        assertEquals(0, stats.groundingViolations, stats.note)
    }
}
