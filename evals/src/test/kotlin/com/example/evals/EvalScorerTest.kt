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
        // `movesSan` is load-bearing, not decoration: `toMoveCoachRequest` fills `moveDisplay` from
        // it, and `MoveCoachResponseValidator.validatePieceType` reads that field as **SAN** to work
        // out which piece nouns the answer may name. Drop it and the fallback is the raw UCI, whose
        // leading `g` parses as a g-file *pawn* move — so an answer that correctly says "knight" is
        // rejected as naming a piece that never moved. All 100 golden candidates carry SAN, and
        // production passes `MoveRecord.san`, so a request without it is one that cannot occur.
        val case = GoldenCase(
            id = "move",
            fen = "fen",
            bestMoveUci = "g1f3",
            tags = listOf("develops"),
            movesSan = listOf("Nf3"),
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

    /**
     * The pair P0-1 requires: the criterion must be observed **passing a paraphrase** and
     * **failing an off-position answer**. The old verbatim-containment check could do neither — it
     * passed only literal copies, so it scored the deterministic template at 0% violations by
     * construction and rejected 90 of 96 validator-approved provider answers, all of them correct
     * paraphrases (measured 2026-08-05, gemini-3.6-flash).
     */
    private val sicilianPassage =
        "The Sicilian Defence answers 1.e4 with 1...c5, developing pieces toward active squares " +
            "while both sides fight for central squares with their pawns."

    private val sicilianCase = GoldenCase(
        id = "sicilian", fen = "fen", bestMoveUci = "c7c5", tags = listOf("opening"),
        eco = "B20", movesSan = listOf("e4", "c5"), expectedConcepts = listOf("center", "development"),
    )

    @Test
    fun `a grounded paraphrase passes`() {
        // Verbatim "center"/"development" appear nowhere in this sentence. It is still the right
        // answer, and this is the exact wording the provider was failed for.
        val paraphrase = "Black develops minor pieces toward active squares and fights for " +
            "central squares with pawns [b20]."

        assertTrue(EvalScorer.scoreOpening(sicilianCase, paraphrase, sicilianPassage).grounded)
    }

    @Test
    fun `a fluent answer about a different position fails`() {
        // Fluent, confident, and about something else entirely: no concept coverage, and almost no
        // content shared with the passage it claims to explain. The failure mode the column is
        // *supposed* to catch, and never could.
        val offPosition = "White builds a kingside attack with the rook lift and the queen swings " +
            "across to h5 [b20]."

        assertFalse(EvalScorer.scoreOpening(sicilianCase, offPosition, sicilianPassage).grounded)
    }

    @Test
    fun `covering the concepts is not enough without sharing content with the source`() {
        // The anchor half, isolated: this names both concepts but is grounded in nothing — it could
        // have been written without reading the passage at all.
        val unanchored = "Center and development matter."

        assertTrue(ConceptVocabulary.isCovered("center", unanchored))
        assertFalse(
            EvalScorer.scoreOpening(
                sicilianCase,
                unanchored,
                "Nimzowitsch's overprotection doctrine assigns surplus defenders to key squares.",
            ).grounded,
        )
    }

    @Test
    fun `verbatim quotation still passes`() {
        // No regression for the deterministic template, which quotes its source.
        assertTrue(EvalScorer.scoreOpening(sicilianCase, "$sicilianPassage [b20]", sicilianPassage).grounded)
    }

    @Test
    fun `an uncovered concept still fails`() {
        val centerOnly = "Both sides fight for central squares with their pawns [b20]."

        assertFalse(EvalScorer.scoreOpening(sicilianCase, centerOnly, sicilianPassage).grounded)
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
        val passages = dependencies.passageRepository.retrieve(embedding, 4).passages

        assertEquals(listOf("lichess-d00-second"), passages.map { it.sourceId })
        // Was `assertFalse(scoreOpening(first, secondsPassage).grounded)` — using the scorer as a
        // stand-in for retrieval isolation. That only held because the old scorer demanded the
        // literal string "center" and the shared passage backbone says "central control": the
        // assertion was riding on a wording accident, and the paraphrase-tolerant scorer correctly
        // reports that this passage does discuss the centre. Assert isolation directly instead.
        assertTrue(passages.none { it.sourceId == "lichess-b00-first" })
    }

    /**
     * Regression, rewritten for the prose prompt. Originally this asserted the prompt said
     * "Knight g1→h3": the builder used to describe the move itself, deriving the piece from the
     * display string's first letter, so passing UCI labelled every move "Pawn". The prompt no
     * longer describes anything — it hands the model deterministic text to rewrite — but the
     * mapping still has to prefer SAN, because SAN is what reaches the user through
     * `deterministicHeadline`.
     */
    @Test
    fun `golden case maps SAN to the display string, not UCI`() {
        val knightCase = GoldenCase(
            id = "knight", fen = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1",
            bestMoveUci = "g1h3", tags = listOf("opening", "develops"), movesSan = listOf("Nh3"),
        )
        val request = knightCase.toMoveCoachRequest()

        assertEquals("Nh3", request.moveDisplay)
        assertEquals("g1h3", request.moveUci)
        assertTrue("Nh3" in request.deterministicHeadline, request.deterministicHeadline)
    }

    /**
     * The prompt carries only the deterministic explanation now. Asserting that keeps the
     * schema-echo regression closed: a JSON example in the prompt is what gemma3-270m copied back
     * verbatim as its answer.
     */
    @Test
    fun `prompt contains the explanation to rewrite and no schema`() {
        val case = GoldenCase(
            id = "e4", fen = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1",
            bestMoveUci = "e2e4", tags = listOf("opening"), movesSan = listOf("e4"),
        )
        val prompt = MoveCoachPromptBuilder.build(case.toMoveCoachRequest()).userPrompt

        assertTrue(case.toMoveCoachRequest().deterministicExplanation in prompt, prompt)
        assertFalse("headline" in prompt, "prompt must not carry a JSON schema: $prompt")
        assertFalse("{" in prompt, "prompt must not carry a JSON schema: $prompt")
    }

    @Test
    fun `display string falls back to uci when a case carries no san`() {
        val noSan = GoldenCase(
            id = "no-san", fen = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1",
            bestMoveUci = "e2e4", tags = listOf("opening"),
        )

        assertEquals("e2e4", noSan.toMoveCoachRequest().moveDisplay)
    }
}
