package com.example.myapplication.multiplayer

import com.example.myapplication.FenConverter
import com.example.myapplication.GameUiState
import com.example.myapplication.King
import com.example.myapplication.MoveRecord
import com.example.myapplication.Pawn
import com.example.myapplication.PromotionType
import com.example.myapplication.SanConverter
import com.example.myapplication.Set as Side
import com.example.myapplication.UciMoveConverter
import com.example.myapplication.applyMove
import com.example.myapplication.getLegalMovesForPiece

internal object LichessMoveCodec {
    /** Keep the provider's outcome separate from this board reconstruction. */
    fun replay(
        initialFen: String,
        moves: List<String>,
        previous: GameUiState? = null,
        previousMoves: List<String> = emptyList(),
    ): GameUiState {
        val keepPrevious = previous != null && previousMoves.size <= moves.size &&
            previousMoves.indices.all { previousMoves[it] == moves[it] }
        val initial = if (keepPrevious) checkNotNull(previous) else
            if (initialFen == "startpos") GameUiState() else FenConverter.fenToGameState(initialFen)
        val startIndex = if (keepPrevious) previousMoves.size else 0
        return moves.subList(startIndex, moves.size).fold(initial, ::apply)
    }

    fun outgoing(
        board: GameUiState,
        from: Pair<Int, Int>,
        to: Pair<Int, Int>,
        promotion: PromotionType?,
    ): String? {
        val white = board.turn == Side.WHITE
        val ownPositions = if (white) board.positionsWhite else board.positionsBlack
        val ownPieces = if (white) board.piecesWhite else board.piecesBlack
        val enemyPositions = if (white) board.positionsBlack else board.positionsWhite
        val enemyPieces = if (white) board.piecesBlack else board.piecesWhite
        val index = ownPositions.indexOf(from)
        if (index < 0) return null
        val promotes = ownPieces[index] is Pawn && to.first in setOf(0, 7)
        if (promotes != (promotion != null)) return null
        val targets = getLegalMovesForPiece(
            index, enemyPositions, enemyPieces, ownPositions, ownPieces, board.castlingRights, board.enPassantTarget,
        )
        return if (to in targets) {
            UciMoveConverter.appMoveToUci(from, to) + (promotion?.uciChar?.toString() ?: "")
        } else null
    }

    private fun apply(board: GameUiState, uci: String): GameUiState {
        val (from, rawTo) = UciMoveConverter.parseUciMove(uci)
        val ownPositions = if (board.turn == Side.WHITE) board.positionsWhite else board.positionsBlack
        val ownPieces = if (board.turn == Side.WHITE) board.piecesWhite else board.piecesBlack
        val pieceIndex = ownPositions.indexOf(from)
        require(pieceIndex >= 0) { "Lichess move starts on an empty or opposing square." }
        // Lichess may stream castling as king to rook. Standard core positions use king to g/c.
        val to = if (ownPieces[pieceIndex] is King && from.second == 4 && from.first == rawTo.first &&
            from.first in setOf(0, 7) && rawTo.second in setOf(0, 7)
        ) {
            rawTo.first to if (rawTo.second == 0) 2 else 6
        } else rawTo
        val promotion = uci.getOrNull(4)?.let { requireNotNull(PromotionType.fromUciChar(it)) }
        val normalized = requireNotNull(outgoing(board, from, to, promotion)) { "Illegal Lichess move." }
        val san = requireNotNull(SanConverter.sanForUci(board, normalized))
        val moved = applyMove(board, pieceIndex, to, promotion)
        return moved.copy(
            moveHistory = board.moveHistory + MoveRecord(normalized, san, FenConverter.gameStateToFen(moved)),
        )
    }
}
