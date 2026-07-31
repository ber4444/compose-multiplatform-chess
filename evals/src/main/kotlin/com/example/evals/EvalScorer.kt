package com.example.evals

import com.example.ondeviceai.MoveCoachPromptBuilder
import com.example.ondeviceai.MoveCoachRequest
import com.example.ondeviceai.MoveCoachResponseValidator

data class OutputScore(
    val grounded: Boolean,
    val lengthViolation: Boolean,
)

object EvalScorer {
    fun scoreMove(case: GoldenCase, text: String): OutputScore {
        val result = MoveCoachResponseValidator.validate(text, case.toMoveCoachRequest())
        return OutputScore(
            grounded = result is MoveCoachResponseValidator.Result.Valid,
            lengthViolation = text.trim().length > MoveCoachPromptBuilder.MAX_OUTPUT_CHARS,
        )
    }

    fun scoreOpening(case: GoldenCase, text: String): OutputScore {
        val lower = text.lowercase()
        return OutputScore(
            grounded = case.expectedConcepts.all { lower.contains(it.lowercase()) },
            lengthViolation = text.trim().length > MoveCoachPromptBuilder.MAX_OUTPUT_CHARS,
        )
    }

    /**
     * Scores one turn of a multi-turn chat. Grounding passes when the accumulated turn output still
     * mentions at least one expected concept (so a later turn that drifts off the pinned position
     * fails), and length is bounded by the chat composer's cap. This is the "no grounding drift
     * across turns" check the plan calls for: every turn, even later ones, must stay anchored.
     */
    fun scoreChat(turn: ChatTurnFixture, text: String): OutputScore {
        val lower = text.lowercase()
        val grounded = turn.expectedConcepts.any { lower.contains(it.lowercase()) }
        return OutputScore(
            grounded = grounded,
            lengthViolation = text.trim().length > CHAT_OUTPUT_CAP,
        )
    }

    /** Chat answers are allowed a slightly larger bounded length than the move coach's 300. */
    const val CHAT_OUTPUT_CAP = 400
}

internal fun GoldenCase.toMoveCoachRequest() = MoveCoachRequest(
    fenBefore = fen,
    bestMoveUci = bestMoveUci,
    // SAN, not UCI. MoveCoachPromptBuilder.describeMove derives the piece name from this string's
    // first letter, and UCI always starts with a lowercase file letter — so passing UCI labelled
    // every move "Pawn", including knight/queen moves, and the model faithfully echoed it. Mirrors
    // production, which uses moveHistory.lastOrNull()?.san (MoveCoachContextExtractor).
    bestMoveDisplay = movesSan.lastOrNull() ?: bestMoveUci,
    sideToMove = if (" b " in fen) "Black" else "White",
    evaluationBeforeCp = null,
    evaluationAfterCp = null,
    deterministicTags = tags,
    engineDifficultyName = "EVAL",
)
