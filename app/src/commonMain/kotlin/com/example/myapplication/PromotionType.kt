package com.example.myapplication

enum class PromotionType(val uciChar: Char) {
    QUEEN('q'), ROOK('r'), BISHOP('b'), KNIGHT('n');

    fun toPiece(set: Set): Piece = when (this) {
        QUEEN -> Queen(set)
        ROOK -> Rook(set)
        BISHOP -> Bishop(set)
        KNIGHT -> Knight(set)
    }

    companion object {
        fun fromUciChar(char: Char): PromotionType? =
            entries.firstOrNull { it.uciChar == char.lowercaseChar() }
    }
}
