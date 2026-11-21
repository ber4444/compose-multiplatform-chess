package com.example.myapplication

data class PendingPromotion(
    val pieceIndex: Int,        // index into piecesWhite/positionsWhite
    val from: Pair<Int, Int>,
    val to: Pair<Int, Int>      // back-rank square, possibly a capture
)

data class CastlingRights(
    val whiteKingside: Boolean = true,
    val whiteQueenside: Boolean = true,
    val blackKingside: Boolean = true,
    val blackQueenside: Boolean = true
) {
    companion object { val NONE = CastlingRights(false, false, false, false) }
}

/**
 * The whole game: position, side to move, and the bookkeeping the rules need (castling rights,
 * en passant, clocks, repetition history) plus the move log.
 *
 * Each side is two parallel lists: `piecesWhite[i]` stands on `positionsWhite[i]`. A square is
 * `(row, column)`, row 0 being Black's back rank and column 0 the a-file. Defaults are the
 * standard starting position, White to move.
 */
data class GameUiState(
    val turn: Set = Set.WHITE, // side to move
    val piecesWhite: List<Piece> = startingArmy(Set.WHITE),
    val positionsWhite: List<Pair<Int, Int>> = startingSquares(backRow = BOARD_SIZE - 1, pawnRow = BOARD_SIZE - 2),
    val inCheckWhite: Boolean = false,
    val piecesBlack: List<Piece> = startingArmy(Set.BLACK),
    val positionsBlack: List<Pair<Int, Int>> = startingSquares(backRow = 0, pawnRow = 1),
    val inCheckBlack: Boolean = false,
    val winState: WinState = WinState.NONE, // NONE while the game is still being played
    val selectedSquare: Pair<Int, Int> = INVALID_POSITION, // square the player last tapped, if any
    val pendingPromotion: PendingPromotion? = null,
    val castlingRights: CastlingRights = CastlingRights(),
    val enPassantTarget: Pair<Int, Int>? = null,
    val halfmoveClock: Int = 0,      // halfmoves since last capture or pawn move (fifty-move rule)
    val fullmoveNumber: Int = 1,     // starts at 1, increments after each Black move
    // Repetition keys (first four FEN fields) of every position since the last irreversible
    // move (capture/pawn move). Invariant: when non-empty, the last element is the current position.
    val positionHistory: List<String> = emptyList(),
    val drawOffer: Set? = null,            // side with an unresolved offer pending (Set is the WHITE/BLACK enum)
    val drawOfferDeclinedBy: Set? = null,  // drives "declined" feedback text; cleared on next move
    val lastDrawOfferFullmove: Int = 0,    // fullmoveNumber of most recent offer (0 = never); cooldown anchor

    // PGN-style move log. Built ply-by-ply in [GameViewModel.deriveNewGameState]. Not reset on
    // irreversible moves (unlike positionHistory); cleared only by `resetGame()` / `fenToGameState`.
    val moveHistory: List<MoveRecord> = emptyList(),
)

/** How the game ended, or [NONE] while it is still being played. */
enum class WinState {
    NONE,
    WHITE,     // Black is checkmated
    BLACK,     // White is checkmated
    DRAW,      // repetition, fifty-move rule, insufficient material, or agreement
    STALEMATE, // the side to move has no legal move and is not in check
}

/** Back rank in the usual R N B Q K B N R order (indices 0..7), then eight pawns (8..15). */
private fun startingArmy(side: Set): List<Piece> =
    listOf(Rook(side), Knight(side), Bishop(side), Queen(side), King(side), Bishop(side), Knight(side), Rook(side)) +
        List(BOARD_SIZE) { Pawn(side) }

/** Squares matching [startingArmy]'s order: the back row left to right, then the pawn row. */
private fun startingSquares(backRow: Int, pawnRow: Int): List<Pair<Int, Int>> =
    (0 until BOARD_SIZE).map { backRow to it } + (0 until BOARD_SIZE).map { pawnRow to it }

/** Screen state that is not part of the chess position. */
data class ViewState(
    val hideWindow: Boolean = false,      // the game-over popup was dismissed
    val buttonLock: Boolean = false,      // game-changing controls disabled; reset/exit stay live
    val moveButtonLock: Boolean = false,  // set while the opponent thinks or a move animates
    val show3D: Boolean = true,            // If the 3D board should be displayed instead of 2D
    val board3DUnavailable: Boolean = false // Set to true if 3D initialization fails
)
