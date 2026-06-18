package com.example.ondeviceai

object MoveCoachFallback {

    private const val MAX_FALLBACK_CHARS = MoveCoachPromptBuilder.MAX_OUTPUT_CHARS

    fun build(request: MoveCoachRequest): String {
        val move = request.bestMoveDisplay.ifBlank { request.bestMoveUci }
        val tags = request.deterministicTags

        val headline = when {
            tags.contains(TAG_CHECKMATE) -> "$move delivers checkmate."
            tags.contains(TAG_CHECK) -> "$move gives check."
            tags.contains(TAG_PROMOTION) -> "$move promotes the pawn."
            tags.contains(TAG_CASTLE_KS) -> "$move castles kingside."
            tags.contains(TAG_CASTLE_QS) -> "$move castles queenside."
            tags.contains(TAG_CAPTURE) -> "$move captures material."
            else -> "Engine choice: $move."
        }

        val reason = when {
            tags.contains(TAG_HANGS_PIECE) -> "It does not leave anything en prise."
            tags.contains(TAG_DEFENDS) -> "It defends a piece."
            tags.contains(TAG_MATERIAL_SWING) -> "It improves the material balance."
            tags.contains(TAG_THREATENS) -> "It creates a threat."
            else -> {
                val eval = request.evaluationAfterCp ?: request.evaluationBeforeCp
                if (eval != null) "It keeps the engine evaluation at ${formatCp(eval)}."
                else "It is the engine's strongest continuation."
            }
        }

        val combined = "$headline $reason"
        return if (combined.length <= MAX_FALLBACK_CHARS) combined
        else combined.take(MAX_FALLBACK_CHARS - 1).trimEnd() + "…"
    }

    private fun formatCp(cp: Int): String =
        if (cp == 0) "0.0" else "${(cp / 100.0)}"

    const val TAG_CAPTURE = "capture"
    const val TAG_CHECK = "check"
    const val TAG_CHECKMATE = "checkmate"
    const val TAG_CASTLE_KS = "castle-kingside"
    const val TAG_CASTLE_QS = "castle-queenside"
    const val TAG_PROMOTION = "promotion"
    const val TAG_MATERIAL_SWING = "material-swing"
    const val TAG_HANGS_PIECE = "no-hanging-piece"
    const val TAG_DEFENDS = "defends"
    const val TAG_THREATENS = "threatens"
}
