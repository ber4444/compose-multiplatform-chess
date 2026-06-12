package com.example.myapplication

import androidx.compose.runtime.Immutable

@Immutable
data class PendingPromotion(
    val pieceIndex: Int,        // index into piecesWhite/positionsWhite
    val from: Pair<Int, Int>,
    val to: Pair<Int, Int>      // back-rank square, possibly a capture
)

@Immutable
data class CastlingRights(
    val whiteKingside: Boolean = true,
    val whiteQueenside: Boolean = true,
    val blackKingside: Boolean = true,
    val blackQueenside: Boolean = true
) {
    companion object { val NONE = CastlingRights(false, false, false, false) }
}

@Immutable
data class GameUiState(
    val turn: Set = Set.WHITE,

    // Team Pieces and their locations
    // Usage: positionsWhite[pieceIndex][first = vertical/ second = horizontal]

    // White team's Pieces and their positions
    val piecesWhite: List<Piece> = listOf(
        Rook(Set.WHITE), Knight(Set.WHITE), Bishop(Set.WHITE), Queen(Set.WHITE),
        King(Set.WHITE), Bishop(Set.WHITE), Knight(Set.WHITE), Rook(Set.WHITE))
            + List(8) { Pawn(Set.WHITE) },
    val positionsWhite: List<Pair<Int, Int>> = List(8) { Pair(7, it) } + List(8) { Pair(6, it) },
    val inCheckWhite : Boolean = false,

    // Black team's Pieces and their positions
    val piecesBlack: List<Piece> = listOf(
        Rook(Set.BLACK), Knight(Set.BLACK), Bishop(Set.BLACK), Queen(Set.BLACK),
        King(Set.BLACK), Bishop(Set.BLACK), Knight(Set.BLACK), Rook(Set.BLACK))
            + List(8) { Pawn(Set.BLACK) },
    val positionsBlack: List<Pair<Int, Int>> = List(8) { Pair(0, it) } + List(8) { Pair(1, it) },
    val inCheckBlack : Boolean = false,

    val winState: WinState = WinState.NONE, // The current WinState of the game
    val autoPlay : Boolean = false,         // If the game is in autoplay mode

    val selectedSquare : Pair<Int, Int> = INVALID_POSITION, // The Position on the board that the user has selected
    val pendingPromotion: PendingPromotion? = null,
    val castlingRights: CastlingRights = CastlingRights()
)

// Current win state of the game
enum class WinState {
    NONE,       // The game has not been won
    WHITE,      // White won the game
    BLACK,      // Black won the game
    DRAW,       // THe game is over, but there is no winner
    STALEMATE   // The game is over because no more moves can be made by a Player (no winner)
}

@Immutable
data class ViewState (
    val hideWindow: Boolean = false,        // If the gameOver window should be hidden
    val buttonLock : Boolean = false,       // Lock all game modifying buttons (doesn't include reset/exit)
    val moveButtonLock: Boolean = false,    // If the 'Move' button is locked
)