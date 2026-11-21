package com.example.myapplication

/**
 * Every legal move for the side to move, as UCI strings ("e2e4", "e7e8q").
 *
 * A pawn reaching the back rank is expanded into its four promotions (q, r, b, n), so the list is
 * exactly the move set UCI tools and move-prediction models enumerate. Order follows
 * [getAllLegalMoves]; callers that need a stable order should sort.
 *
 * Exists so code outside the core (the human-move explorer in `:app`) can enumerate legal moves
 * without reaching into the internal move generator or reimplementing one.
 */
fun legalUciMoves(state: GameUiState): List<String> {
    val white = state.turn == Set.WHITE
    val allyPositions = if (white) state.positionsWhite else state.positionsBlack
    val allyPieces = if (white) state.piecesWhite else state.piecesBlack
    val enemyPositions = if (white) state.positionsBlack else state.positionsWhite
    val enemyPieces = if (white) state.piecesBlack else state.piecesWhite
    val backRow = if (white) 0 else BOARD_SIZE - 1
    return getAllLegalMoves(
        enemyPositions, enemyPieces, allyPositions, allyPieces, state.castlingRights, state.enPassantTarget,
    ).flatMap { (destination, pieceIndex) ->
        val uci = UciMoveConverter.positionToUciSquare(allyPositions[pieceIndex]) +
            UciMoveConverter.positionToUciSquare(destination)
        if (allyPieces[pieceIndex] is Pawn && destination.first == backRow) {
            PROMOTION_SUFFIXES.map { uci + it }
        } else {
            listOf(uci)
        }
    }
}

private val PROMOTION_SUFFIXES = listOf('q', 'r', 'b', 'n')
