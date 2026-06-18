package com.example.ondeviceai

object MoveCoachResponseValidator {

    val FORBIDDEN_PHRASES = listOf(
        "i think stockfish",
        "probably depth",
        "stockfish thinks",
        "engine depth",
        "elo ",
        "rating of",
    )

    fun groundingTokens(request: MoveCoachRequest): List<String> {
        val tokens = mutableSetOf<String>()
        val uci = request.bestMoveUci.lowercase()
        if (uci.isNotEmpty()) {
            tokens += uci
            if (uci.length >= 4) {
                tokens += uci.substring(0, 2)
                tokens += uci.substring(2, 4)
            }
        }
        request.bestMoveDisplay.lowercase()
            .replace(Regex("[^a-z0-9]"), " ")
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() }
            .forEach { tokens += it }
        return tokens.toList()
    }

    fun validate(rawText: String, request: MoveCoachRequest): Result {
        val text = rawText.trim()
        if (text.isEmpty()) return Result.Invalid("empty response")
        if (text.length > MoveCoachPromptBuilder.MAX_OUTPUT_CHARS) {
            return Result.Invalid("response exceeds ${MoveCoachPromptBuilder.MAX_OUTPUT_CHARS} chars")
        }
        val lower = text.lowercase()
        FORBIDDEN_PHRASES.firstOrNull { lower.contains(it) }?.let { phrase ->
            return Result.Invalid("forbidden phrase: $phrase")
        }
        val tokens = groundingTokens(request)
        if (tokens.isNotEmpty() && tokens.none { lower.contains(it) }) {
            return Result.Invalid("response does not mention the move")
        }
        return Result.Valid(text)
    }

    sealed interface Result {
        data class Valid(val text: String) : Result
        data class Invalid(val reason: String) : Result
    }
}
