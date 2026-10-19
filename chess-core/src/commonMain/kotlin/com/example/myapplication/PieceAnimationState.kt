package com.example.myapplication

/**
 * The move currently being animated: the piece that moved and its two squares, plus the rook's
 * path when the move was castling. A null [pieceToAnimate] means nothing is in flight.
 */
data class PieceAnimationState(
    val pieceToAnimate: Piece? = null, // null = no animation in flight
    val animatePositionStart: Pair<Int, Int> = INVALID_POSITION, // where the piece left from
    val animatePositionEnd: Pair<Int, Int> = INVALID_POSITION,
    val secondaryPiece: Piece? = null,
    val secondaryStart: Pair<Int, Int> = INVALID_POSITION,
    val secondaryEnd: Pair<Int, Int> = INVALID_POSITION
) {
    /** True only for a real piece travelling between two real squares. */
    fun moveIsValid(): Boolean =
        pieceToAnimate != null && INVALID_POSITION !in listOf(animatePositionStart, animatePositionEnd)
}