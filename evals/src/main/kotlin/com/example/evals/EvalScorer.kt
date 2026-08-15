package com.example.evals

import com.example.ondeviceai.MoveCoachPromptBuilder
import com.example.ondeviceai.MoveCoachRequest
import com.example.ondeviceai.MoveCoachResponseValidator

data class OutputScore(
    val grounded: Boolean,
    val lengthViolation: Boolean,
    val fluencyCompliant: Boolean = true,
    /** Measured Flesch-Kincaid grade, reported alongside the pass/fail so the bound stays auditable. */
    val readingGrade: Double = 0.0,
)

data class DiagnosticScore(
    val retrievalCorrect: Boolean,
    val terminalCorrect: Boolean,
    val corpusReady: Boolean,
)

object EvalScorer {
    fun scoreMove(case: GoldenCase, text: String): OutputScore =
        scoreMove(case.toMoveCoachRequest(), text)

    /**
     * Scores against a caller-supplied request rather than the one derived from the golden case.
     *
     * The validator's verdict depends on the facts it is given — `betterMoveDisplay` widens the
     * allowed piece names, `motifs` decide whether a capture claim is supported. A device run
     * records the request it actually used, so scoring those rows through
     * [GoldenCase.toMoveCoachRequest]'s placeholder would answer a different question than the one
     * the device answered, and the two would disagree for reasons that are not about the text.
     * See [scoreDeviceRun], which cross-checks its verdicts against the device's own.
     */
    fun scoreMove(request: MoveCoachRequest, text: String): OutputScore {
        val result = MoveCoachResponseValidator.validate(text, request)
        val fluency = FluencyScorer.evaluate(text, FluencyScorer.FluencySurface.MOVE_COACH)
        return OutputScore(
            grounded = result is MoveCoachResponseValidator.Result.Valid,
            lengthViolation = text.trim().length > MoveCoachPromptBuilder.MAX_OUTPUT_CHARS,
            fluencyCompliant = fluency.isCompliant,
            readingGrade = fluency.gradeLevel,
        )
    }

    /**
     * Scores an opening explanation for grounding.
     *
     * **This used to be `expectedConcepts.all { text.contains(it) }` — verbatim containment — and
     * that measured copying, not grounding.** 97 of the 100 golden cases demand the literal string
     * `"development"` and 92 demand `"center"`; `TemplateComposer` quotes its source passage, which
     * contains both, so the deterministic template scored 0% violations *by construction* while any
     * paraphrase failed. Measured on 2026-08-05 against a real provider: 99 of 100 outputs passed
     * the production validator and 90 of them were scored ungrounded — a hand read of 12 found
     * 12 correct paraphrases ("developing minor pieces", "contesting the center") and zero answers
     * about the wrong position. The column named grounding was ranking composers by how literally
     * they copied.
     *
     * Two conditions replace it, because grounding is two claims and the old check tested neither:
     * - **Concept coverage**, paraphrase-tolerant ([ConceptVocabulary]) — does the answer discuss
     *   what this position is about?
     * - **Passage anchoring**, when the caller knows which passage was retrieved — does the answer
     *   share content with its own source, so that fluent text about a *different* opening fails?
     *   Optional because the deployed-cloud route retrieves from the live corpus and cannot know
     *   which passage came back; that route is scored on coverage alone, and says so.
     */
    fun scoreOpening(case: GoldenCase, text: String, passageText: String? = null): OutputScore {
        val fluency = FluencyScorer.evaluate(text, FluencyScorer.FluencySurface.OPENING)
        val covers = case.expectedConcepts.all { ConceptVocabulary.isCovered(it, text) }
        val anchored = passageText == null || sharesContentWords(text, passageText)
        return OutputScore(
            grounded = covers && anchored,
            lengthViolation = text.trim().length > MoveCoachPromptBuilder.MAX_OUTPUT_CHARS,
            fluencyCompliant = fluency.isCompliant,
            readingGrade = fluency.gradeLevel,
        )
    }

    /**
     * Whether [text] shares at least [minimum] substantial content words with [source]. Mirrors the
     * per-sentence rule in `OpeningExplanationValidator`, summed over the whole answer: an
     * explanation of a different position reuses almost nothing of the passage it claims to be
     * grounded in.
     */
    internal fun sharesContentWords(text: String, source: String, minimum: Int = MIN_PASSAGE_OVERLAP): Boolean {
        fun contentWords(value: String) = WORDS.findAll(value.lowercase())
            .map { it.value }
            .filter { it.length >= 4 && it !in STOP_WORDS }
            .toSet()
        return contentWords(text).intersect(contentWords(source)).size >= minimum
    }

    private val WORDS = Regex("[a-z0-9]+")
    private val STOP_WORDS = setOf(
        "this", "that", "with", "from", "they", "them", "their", "there", "here", "have", "into",
        "both", "each", "when", "then", "than", "also", "more", "most", "some", "such", "your",
        "while", "which", "these", "those", "will", "would", "should", "about", "after", "before",
    )

    /**
     * Deliberately stricter than the server's per-sentence `OpeningExplanationValidator`
     * `MIN_SOURCE_OVERLAP` (relaxed to 1), because this is applied to the *whole* answer: a compliant
     * 2-3 sentence explanation clears one word per sentence, so two across the answer is the same
     * claim, and the eval bar must not move every time the production bar is retuned.
     */
    private const val MIN_PASSAGE_OVERLAP = 2

    /**
     * Scores one turn of a multi-turn chat. Grounding passes when the accumulated turn output still
     * mentions at least one expected concept (so a later turn that drifts off the pinned position
     * fails), and length is bounded by the chat composer's cap. This is the "no grounding drift
     * across turns" check the plan calls for: every turn, even later ones, must stay anchored.
     */
    fun scoreChat(turn: ChatTurnFixture, text: String): OutputScore {
        val lower = text.lowercase()
        val grounded = turn.expectedConcepts.any { lower.contains(it.lowercase()) }
        // Was computed and then dropped from the returned score, so every chat route reported
        // 0% fluency violation — "not measured", rendered identically to "clean".
        val fluency = FluencyScorer.evaluate(text, FluencyScorer.FluencySurface.CHAT)
        return OutputScore(
            grounded = grounded,
            lengthViolation = text.trim().length > CHAT_OUTPUT_CAP,
            fluencyCompliant = fluency.isCompliant,
            readingGrade = fluency.gradeLevel,
        )
    }

    /** Chat answers are allowed a slightly larger bounded length than the move coach's 300. */
    const val CHAT_OUTPUT_CAP = 400

    fun scoreDiagnostics(expectedEco: String?, diagnostics: com.example.coachapi.CloudDiagnostics): DiagnosticScore {
        val expectedPrefix = expectedEco?.let { "lichess-${it.lowercase()}" }
        val retrievalCorrect = expectedPrefix == null || diagnostics.retrievedPassageIds.any { it.startsWith(expectedPrefix) }
        val terminalCorrect = diagnostics.finishReason in listOf("completed", "budget_rejected", "provider_error", "validator_rejected", "done", "fallback")
        val corpusReady = diagnostics.corpus.ready
        return DiagnosticScore(retrievalCorrect, terminalCorrect, corpusReady)
    }
}

internal fun GoldenCase.toMoveCoachRequest() = MoveCoachRequest(
    moveUci = bestMoveUci,
    moveDisplay = movesSan.lastOrNull() ?: bestMoveUci,
    deterministicHeadline = "You played ${movesSan.lastOrNull() ?: bestMoveUci}.",
    deterministicExplanation = "This was a good move.", // Dummy values for eval scoring where we don't have full MoveRecord
    engineDifficultyName = "EVAL"
)
