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
        // Grounding check: accept if the response mentions the move (UCI squares,
        // display text) OR contains chess-relevant vocabulary (piece names,
        // tactical terms). This is permissive enough to let natural-language
        // explanations through ("Develops the knight toward the center") while
        // still rejecting completely irrelevant output.
        val tokens = groundingTokens(request)
        val chessVocab = listOf(
            "knight", "bishop", "rook", "queen", "king", "pawn",
            "develop", "center", "centre", "attack", "defend", "defends",
            "control", "threat", "pressure", "castle", "promot",
            "capture", "check", "space", "square", "file", "rank",
            "pin", "fork", "skewer", "battery", "open", "block",
        )
        val mentionsMove = tokens.any { lower.contains(it) }
        val mentionsChess = chessVocab.any { lower.contains(it) }
        if (!mentionsMove && !mentionsChess) {
            return Result.Invalid("response is not grounded in the move or chess vocabulary")
        }
        return Result.Valid(text)
    }

    sealed interface Result {
        data class Valid(val text: String) : Result
        data class Invalid(val reason: String) : Result
    }
}
