package com.example.myapplication.perft

import com.example.myapplication.GameUiState
import com.example.myapplication.PromotionType
import com.example.myapplication.Set
import com.example.myapplication.UciMoveConverter
import com.example.myapplication.applyMove
import com.example.myapplication.getAllLegalMoves
import com.example.myapplication.isPromotionMove

/**
 * A single legal move in the perft expansion, with the ally piece's [from] square cached so UCI
 * strings can be built without re-reading the state. [promotion] is non-null only when this move
 * is one of the four fan-out entries for a pawn reaching the back rank.
 */
data class PerftMove(
    val pieceIndex: Int,
    val from: Pair<Int, Int>,
    val to: Pair<Int, Int>,
    val promotion: PromotionType?
)

/**
 * Pure perft kernel: wraps [getAllLegalMoves] with the ally/enemy split derived from [state.turn],
 * and — critically for correct counts — expands each pawn promotion into four separate moves
 * (Q, R, B, N). `getAllLegalMoves` returns a single move for a pawn reaching the back rank because
 * `applyMove` defaults under-promotion to Queen; perft must count four leaves there.
 *
 * Every returned [PerftMove] is fully legal (the underlying generator already king-safety-checks
 * castling and en passant), so [perft] can apply them without re-validating.
 */
fun legalMovesFor(state: GameUiState): List<PerftMove> {
    val allyPositions = if (state.turn == Set.WHITE) state.positionsWhite else state.positionsBlack
    val allyPieces    = if (state.turn == Set.WHITE) state.piecesWhite   else state.piecesBlack
    val enemyPositions = if (state.turn == Set.WHITE) state.positionsBlack else state.positionsWhite
    val enemyPieces    = if (state.turn == Set.WHITE) state.piecesBlack   else state.piecesWhite

    val rawMoves = getAllLegalMoves(
        enemyPositions = enemyPositions,
        enemyPieces = enemyPieces,
        allyPositions = allyPositions,
        allyPieces = allyPieces,
        castlingRights = state.castlingRights,
        enPassantTarget = state.enPassantTarget
    )

    val expanded = ArrayList<PerftMove>(rawMoves.size + 8)  // worst case: +3 per promotion
    for ((to, pieceIndex) in rawMoves) {
        val piece = allyPieces[pieceIndex]
        val from = allyPositions[pieceIndex]
        if (isPromotionMove(piece, to)) {
            for (promo in PromotionType.entries) {
                expanded += PerftMove(pieceIndex, from, to, promo)
            }
        } else {
            expanded += PerftMove(pieceIndex, from, to, null)
        }
    }
    return expanded
}

/**
 * Count leaf nodes in the legal-move tree to [depth]. Recurses through the pure [applyMove]
 * transition. The depth-1 fast path avoids a final [applyMove] allocation per leaf, which is the
 * single biggest win on this list-based representation.
 */
fun perft(state: GameUiState, depth: Int): Long {
    if (depth <= 0) return 1L
    val moves = legalMovesFor(state)
    if (depth == 1) return moves.size.toLong()
    var total = 0L
    for (move in moves) {
        val nextState = applyMove(state, move.pieceIndex, move.to, move.promotion)
        total += perft(nextState, depth - 1)
    }
    return total
}

/**
 * Per-root-move subtree counts keyed by UCI move string. This is the localizer: when a perft
 * total differs from the oracle, the diverging root move is the one whose count differs, and
 * recursing one ply into *that* move narrows the search. Promotion moves use the 5-char UCI
 * form (`e7e8q`); the [promotion] field supplies the trailing piece char.
 */
fun perftDivide(state: GameUiState, depth: Int): Map<String, Long> {
    val moves = legalMovesFor(state)
    val out = LinkedHashMap<String, Long>(moves.size)
    for (move in moves) {
        val uci = UciMoveConverter.appMoveToUci(move.from, move.to) +
            (move.promotion?.let { it.uciChar.toString() } ?: "")
        val count = if (depth <= 1) {
            1L
        } else {
            val nextState = applyMove(state, move.pieceIndex, move.to, move.promotion)
            perft(nextState, depth - 1)
        }
        // Accumulate in case two fan-out moves collapse to the same UCI key (shouldn't happen,
        // but a divide map is meaningless if entries silently overwrite each other).
        val existing = out[uci]
        if (existing == null) out[uci] = count else out[uci] = existing + count
    }
    return out
}
