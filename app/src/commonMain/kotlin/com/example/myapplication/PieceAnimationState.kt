package com.example.myapplication

data class PieceAnimationState(
    val pieceToAnimate: Piece? = null,
    val animatePositionStart: Pair<Int, Int> = INVALID_POSITION,
    val animatePositionEnd: Pair<Int, Int> = INVALID_POSITION,
    val secondaryPiece: Piece? = null,
    val secondaryStart: Pair<Int, Int> = INVALID_POSITION,
    val secondaryEnd: Pair<Int, Int> = INVALID_POSITION
) {
    // Ensure all values are valid before animating
    fun moveIsValid() : Boolean {
        return pieceToAnimate != null &&
                animatePositionStart!= INVALID_POSITION &&
                animatePositionEnd != INVALID_POSITION
    }
}