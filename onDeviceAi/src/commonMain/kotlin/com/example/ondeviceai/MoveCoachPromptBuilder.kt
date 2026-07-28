package com.example.ondeviceai

object MoveCoachPromptBuilder {

    private val SYSTEM_PROMPT: String = buildString {
        appendLine("You are a chess coach explaining a single move to a casual player.")
        appendLine("Say WHY the move is good in 1-2 short sentences.")
        appendLine("Be specific: mention the piece, the square, and what it does (attacks, defends, controls, develops).")
        appendLine("Do not mention openings by name, engine depth, or ratings.")
        appendLine()
        appendLine("Good: \"Nf3 develops the knight and controls the central e5/d4 squares.\"")
        appendLine("Good: \"Bb5 pins the knight to the king and prepares to win material on the next move.\"")
        appendLine("Bad: \"This is a good move that improves the position.\"")
        appendLine()
        appendLine("Respond with a valid JSON object matching this schema:")
        appendLine("{")
        appendLine("  \"headline\": \"A short, punchy headline for the move.\",")
        appendLine("  \"explanation\": \"The full explanation of the move.\"")
        appendLine("}")

    fun build(request: MoveCoachRequest): AiGenerationRequest =
        AiGenerationRequest(
            systemPrompt = SYSTEM_PROMPT,
            userPrompt = userPrompt(request),
            maxOutputTokens = MAX_OUTPUT_TOKENS_STRICT,
            temperature = 0.3,
        )

    internal fun userPrompt(request: MoveCoachRequest): String = buildString {
        appendLine("Move: ${describeMove(request)}")
        appendLine("Engine Difficulty: ${request.engineDifficultyName}")
        appendLine("Key points: ${describeTags(request.deterministicTags)}")
        if (request.evaluationBeforeCp != null || request.evaluationAfterCp != null) {
            appendLine("Evaluation: ${request.evaluationBeforeCp ?: "?"} → ${request.evaluationAfterCp ?: "?"} cp")
        }
        appendLine()
        append("Explain this move in 1-2 sentences:")
    }

    /**
     * Human-readable move description from UCI + display text.
     * "g1f3" + "Nf3" → "Knight g1→f3"
     * "e2e4" + "e4" → "Pawn e2→e4"
     */
    private fun describeMove(request: MoveCoachRequest): String {
        val display = request.bestMoveDisplay.trim()
        val uci = request.bestMoveUci.lowercase()

        // Castling
        if (display == "O-O" || display == "0-0") return "Castles kingside"
        if (display == "O-O-O" || display == "0-0-0") return "Castles queenside"

        // Piece type from display letter
        val pieceName = when {
            display.isEmpty() -> "Piece"
            display.first().isUpperCase() -> when (display.first()) {
                'N' -> "Knight"
                'B' -> "Bishop"
                'R' -> "Rook"
                'Q' -> "Queen"
                'K' -> "King"
                else -> "Piece"
            }
            else -> "Pawn"
        }

        // From/to from UCI
        if (uci.length >= 4) {
            val from = uci.substring(0, 2)
            val to = uci.substring(2, 4)
            val promo = if (display.contains("=")) {
                " (promoting to ${display.substringAfter("=")})"
            } else ""
            return "$pieceName $from→$to$promo"
        }
        return display.ifBlank { uci }
    }

    /**
     * Translate tag codes into natural-language hints the model can use.
     */
    private fun describeTags(tags: List<String>): String {
        if (tags.isEmpty()) return "engine's top choice for this position"
        val parts = mutableListOf<String>()
        for (tag in tags) {
            parts += when (tag) {
                MoveCoachFallback.TAG_CAPTURE -> "captures an enemy piece"
                MoveCoachFallback.TAG_CHECK -> "gives check"
                MoveCoachFallback.TAG_CHECKMATE -> "delivers checkmate"
                MoveCoachFallback.TAG_CASTLE_KS -> "castles kingside"
                MoveCoachFallback.TAG_CASTLE_QS -> "castles queenside"
                MoveCoachFallback.TAG_PROMOTION -> "promotes a pawn"
                MoveCoachFallback.TAG_MATERIAL_SWING -> "wins material"
                MoveCoachFallback.TAG_DEFENDS -> "defends a piece"
                MoveCoachFallback.TAG_THREATENS -> "creates a threat"
                MoveCoachFallback.TAG_DEVELOPS -> "develops a piece"
                MoveCoachFallback.TAG_CENTER_CONTROL -> "controls the center"
                MoveCoachFallback.TAG_KING_SAFETY -> "improves king safety"
                MoveCoachFallback.TAG_PAWN_PUSH -> "gains space"
                MoveCoachFallback.TAG_RECAPTURE -> "recaptures"
                MoveCoachFallback.TAG_OPENING -> "opening-phase move"
                else -> tag
            }
        }
        return parts.joinToString(", ")
    }

    const val MAX_OUTPUT_TOKENS_STRICT = 100
    const val MAX_OUTPUT_CHARS = 300
}
