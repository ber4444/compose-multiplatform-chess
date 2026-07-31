package com.example.evals

import com.example.ondeviceai.AiTokenOrFinal
import com.example.ondeviceai.MoveCoachPromptBuilder
import kotlin.io.path.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import com.example.coachserver.OpeningQueryBuilder

class EvalScorerTest {
    @Test
    fun `token chunks flatten in order`() {
        assertEquals(
            "Nf3 develops",
            tokenText(listOf(AiTokenOrFinal.Token("Nf3 "), AiTokenOrFinal.Token("develops"))),
        )
    }

    @Test
    fun `golden candidate set contains one hundred cases`() {
        val cases = GoldenCaseLoader.load(Path("golden/candidates.json"))

        assertEquals(100, cases.size)
        assertTrue(cases.all { it.fen.isNotBlank() && it.bestMoveUci.length >= 4 })
        assertEquals(
            100,
            cases.map { listOf(it.fen, it.bestMoveUci, it.eco, it.movesSan.joinToString(" ")) }.toSet().size,
            "golden candidates must be semantically distinct, not repeated under new ids",
        )
    }

    @Test
    fun `move scorer uses the production validator`() {
        val case = GoldenCase(
            id = "move",
            fen = "fen",
            bestMoveUci = "g1f3",
            tags = listOf("develops"),
        )

        assertFalse(EvalScorer.scoreMove(case, "Generic prose with no relevant content.").grounded)
        assertTrue(EvalScorer.scoreMove(case, "Nf3 develops the knight toward the center.").grounded)
    }

    @Test
    fun `opening scorer requires expected concept coverage`() {
        val case = GoldenCase(
            id = "opening",
            fen = "fen",
            bestMoveUci = "e2e4",
            tags = listOf("opening"),
            eco = "B00",
            expectedConcepts = listOf("center", "development"),
        )

        assertFalse(EvalScorer.scoreOpening(case, "This is a flexible setup.").grounded)
        assertTrue(EvalScorer.scoreOpening(case, "It fights for the center and supports development.").grounded)
    }

    @Test
    fun `local opening retrieval returns the passage for the matching production query`() {
        val first = GoldenCase(
            id = "first", fen = "fen-1", bestMoveUci = "e2e4", tags = listOf("opening"),
            eco = "B00", movesSan = listOf("e4"), expectedConcepts = listOf("center"),
        )
        val second = GoldenCase(
            id = "second", fen = "fen-2", bestMoveUci = "d2d4", tags = listOf("opening"),
            eco = "D00", movesSan = listOf("d4"), expectedConcepts = listOf("development"),
        )
        val dependencies = caseSpecificOpeningDependencies(listOf(first, second))

        val embedding = dependencies.embedder.embed(OpeningQueryBuilder.build(second.toOpeningRequest()))
        val passages = dependencies.passageRepository.retrieve(embedding, 4)

        assertEquals(listOf("eval-second"), passages.map { it.sourceId })
        assertFalse(EvalScorer.scoreOpening(first, passages.single().text).grounded)
    }

    /**
     * Regression: this used to pass `bestMoveUci` as the display string. `describeMove` reads the
     * piece from the first letter and UCI always starts with a lowercase file, so every prompt said
     * "Pawn" — a knight move was fed to the model as "Pawn g1→h3" and it echoed that back. Any
     * piece-accuracy measurement taken that way scores the harness, not the model.
     */
    @Test
    fun `move coach prompt names the real piece for a knight move`() {
        val knightCase = GoldenCase(
            id = "knight", fen = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1",
            bestMoveUci = "g1h3", tags = listOf("opening", "develops"), movesSan = listOf("Nh3"),
        )

        assertEquals("Nh3", knightCase.toMoveCoachRequest().bestMoveDisplay)
        val prompt = MoveCoachPromptBuilder.build(knightCase.toMoveCoachRequest()).userPrompt
        assertTrue("Knight g1→h3" in prompt, "expected 'Knight g1→h3' in prompt, got: $prompt")
        assertFalse("Pawn" in prompt, "knight move must not be described as a pawn: $prompt")
    }

    @Test
    fun `move coach prompt falls back to uci when a case carries no san`() {
        val noSan = GoldenCase(
            id = "no-san", fen = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1",
            bestMoveUci = "e2e4", tags = listOf("opening"),
        )

        assertEquals("e2e4", noSan.toMoveCoachRequest().bestMoveDisplay)
    }
}
