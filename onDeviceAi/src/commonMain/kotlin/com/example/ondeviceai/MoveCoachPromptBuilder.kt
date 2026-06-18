package com.example.ondeviceai

object MoveCoachPromptBuilder {

    private val SYSTEM_PROMPT: String = buildString {
        appendLine("You are a chess coach for a casual player.")
        appendLine("Explain only the provided move.")
        appendLine("Do not name openings, engine depth, or ratings unless they are present in the input.")
        appendLine("Use at most 2 sentences. Do not invent facts.")
    }

    fun build(request: MoveCoachRequest): AiGenerationRequest =
        AiGenerationRequest(
            systemPrompt = SYSTEM_PROMPT,
            userPrompt = userPrompt(request),
            maxOutputTokens = MAX_OUTPUT_TOKENS_STRICT,
            temperature = 0.2,
        )

    fun buildRetry(request: MoveCoachRequest, previousOutput: String): AiGenerationRequest =
        AiGenerationRequest(
            systemPrompt = SYSTEM_PROMPT,
            userPrompt = buildString {
                appendLine(userPrompt(request))
                appendLine()
                appendLine("A previous attempt was rejected for ungrounded or too-long output:")
                appendLine("\"${summarize(previousOutput)}\"")
                appendLine("Reply with at most 2 sentences and mention only the provided move. No opening names. No engine depth.")
            },
            maxOutputTokens = MAX_OUTPUT_TOKENS_STRICT,
            temperature = 0.0,
        )

    internal fun userPrompt(request: MoveCoachRequest): String = buildString {
        appendLine("Position FEN: ${request.fenBefore}")
        appendLine("Best move: ${request.bestMoveDisplay} (${request.bestMoveUci})")
        appendLine("Side to move: ${request.sideToMove}")
        appendLine("Evaluation before: ${request.evaluationBeforeCp ?: "n/a"}")
        appendLine("Evaluation after: ${request.evaluationAfterCp ?: "n/a"}")
        appendLine("Tags: ${if (request.deterministicTags.isEmpty()) "none" else request.deterministicTags.joinToString(", ")}")
    }

    private fun summarize(text: String): String =
        if (text.length <= PREVIOUS_OUTPUT_PREVIEW_CHARS) text
        else text.take(PREVIOUS_OUTPUT_PREVIEW_CHARS) + "…"

    const val MAX_OUTPUT_TOKENS_STRICT = 120
    const val MAX_OUTPUT_CHARS = 360
    private const val PREVIOUS_OUTPUT_PREVIEW_CHARS = 80
}
