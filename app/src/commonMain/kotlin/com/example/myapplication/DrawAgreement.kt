package com.example.myapplication

import kotlin.math.abs

const val DRAW_ACCEPT_THRESHOLD_CP = 100      // Black accepts immediately when worse by at least 1 pawn
const val DRAW_OFFER_EVAL_WINDOW_CP = 60      // Black offers when |eval| <= this
const val DRAW_OFFER_MIN_FULLMOVE = 20
const val DRAW_OFFER_MIN_HALFMOVE_CLOCK = 8
const val DRAW_OFFER_COOLDOWN_FULLMOVES = 10

fun pieceValueCp(piece: Piece): Int = when (piece) {
    is Pawn -> 100
    is Knight -> 320
    is Bishop -> 330
    is Rook -> 500
    is Queen -> 900
    is King -> 0
    else -> 0 // Should not happen
}

fun materialBalanceCp(state: GameUiState): Int {
    val whiteSum = state.piecesWhite.sumOf { pieceValueCp(it) }
    val blackSum = state.piecesBlack.sumOf { pieceValueCp(it) }
    return whiteSum - blackSum
}

suspend fun evaluatePositionCp(engine: ChessEngine?, state: GameUiState): Int =
    engine?.evaluate(FenConverter.gameStateToFen(state)) ?: materialBalanceCp(state)

fun shouldBlackAcceptDraw(evalCp: Int): Boolean = evalCp >= DRAW_ACCEPT_THRESHOLD_CP

fun shouldBlackAcceptDraw(evalCp: Int, state: GameUiState): Boolean =
    shouldBlackAcceptDraw(evalCp) ||
        (isQuietMatureDrawOfferPosition(state) && shouldBlackOfferDraw(evalCp))

fun shouldBlackOfferDraw(evalCp: Int): Boolean = abs(evalCp) <= DRAW_OFFER_EVAL_WINDOW_CP

private fun isQuietMatureDrawOfferPosition(state: GameUiState): Boolean =
    state.fullmoveNumber >= DRAW_OFFER_MIN_FULLMOVE &&
        state.halfmoveClock >= DRAW_OFFER_MIN_HALFMOVE_CLOCK

fun blackDrawOfferPreconditions(state: GameUiState): Boolean {
    return state.winState == WinState.NONE && state.drawOffer == null && state.pendingPromotion == null &&
        state.fullmoveNumber >= DRAW_OFFER_MIN_FULLMOVE && state.halfmoveClock >= DRAW_OFFER_MIN_HALFMOVE_CLOCK &&
        (state.lastDrawOfferFullmove == 0 || state.fullmoveNumber - state.lastDrawOfferFullmove >= DRAW_OFFER_COOLDOWN_FULLMOVES)
}

fun canOfferDraw(state: GameUiState): Boolean {
    return state.turn == Set.WHITE && state.winState == WinState.NONE && state.pendingPromotion == null &&
        state.drawOffer == null && state.fullmoveNumber > state.lastDrawOfferFullmove
}
