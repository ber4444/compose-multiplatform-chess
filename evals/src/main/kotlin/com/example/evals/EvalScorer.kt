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
}

internal fun GoldenCase.toMoveCoachRequest() = MoveCoachRequest(
    fenBefore = fen,
    bestMoveUci = bestMoveUci,
    bestMoveDisplay = bestMoveUci,
    sideToMove = if (" b " in fen) "Black" else "White",
    evaluationBeforeCp = null,
    evaluationAfterCp = null,
    deterministicTags = tags,
    engineDifficultyName = "EVAL",
)
