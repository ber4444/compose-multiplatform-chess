package com.example.myapplication

const val FIFTY_MOVE_RULE_HALFMOVES = 100

/** K vs K, K+minor vs K, or K+B vs K+B with same-colored bishops.
 *  K+N vs K+N and two minors on one side are NOT treated as draws (mate is possible). */
fun isInsufficientMaterial(state: GameUiState): Boolean {
    val white = state.piecesWhite.zip(state.positionsWhite)
    val black = state.piecesBlack.zip(state.positionsBlack)
    val nonKings = (white + black).filter { it.first !is King }
    if (nonKings.any { it.first is Pawn || it.first is Rook || it.first is Queen }) return false
    return when {
        nonKings.isEmpty() -> true                       // K vs K
        nonKings.size == 1 -> true                       // K + minor vs K
        nonKings.size == 2 &&
            nonKings.all { it.first is Bishop } &&
            white.any { it.first is Bishop } && black.any { it.first is Bishop } &&
            nonKings.map { (it.second.first + it.second.second) % 2 }.distinct().size == 1 -> true
        else -> false
    }
}

/** Fifty-move rule. Deferred while the side to move is in check so a mating 100th halfmove
 *  is scored as a win (mate is detected lazily in moveCPU); a non-mate escape draws next move. */
fun isFiftyMoveDraw(state: GameUiState): Boolean {
    if (state.halfmoveClock < FIFTY_MOVE_RULE_HALFMOVES) return false
    val sideToMoveInCheck = if (state.turn == Set.WHITE) state.inCheckWhite else state.inCheckBlack
    return !sideToMoveInCheck
}

/** Relies on the invariant that positionHistory ends with the current position's key.
 *  Safe even in check: a repeated position (same side to move) had legal moves before. */
fun isThreefoldRepetition(state: GameUiState): Boolean {
    val key = state.positionHistory.lastOrNull() ?: return false
    return state.positionHistory.count { it == key } >= 3
}

/** Returns the state with winState = DRAW when any draw condition holds. */
fun applyDrawConditions(state: GameUiState): GameUiState =
    if (state.winState == WinState.NONE &&
        (isInsufficientMaterial(state) || isFiftyMoveDraw(state) || isThreefoldRepetition(state))
    ) state.copy(winState = WinState.DRAW) else state
