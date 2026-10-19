package com.example.myapplication

import co.touchlab.kermit.Logger

private val logger = Logger.withTag("Move")

/**
 * Sentinel for "no square": nothing is selected, an animation has no endpoint, or no move was
 * found. Every real square has both coordinates in `0 until BOARD_SIZE`.
 */
val INVALID_POSITION = Pair(-1, -1)

internal val WHITE_KING_HOME = Pair(7, 4)
internal val WHITE_KS_ROOK_HOME = Pair(7, 7)
internal val WHITE_QS_ROOK_HOME = Pair(7, 0)
internal val BLACK_KING_HOME = Pair(0, 4)
internal val BLACK_KS_ROOK_HOME = Pair(0, 7)
internal val BLACK_QS_ROOK_HOME = Pair(0, 0)

data class SelectedMove(
    val position: Pair<Int, Int>,
    val pieceIndex: Int,
    val promotion: PromotionType? = null,
    val evaluationCp: Int? = null
)

internal fun isPromotionMove(piece: Piece, newPosition: Pair<Int, Int>): Boolean =
    piece is Pawn && newPosition.first == if (piece.set == Set.WHITE) 0 else BOARD_SIZE - 1

/**
 * Pick a move using the Stockfish chess engine.
 * Converts the current board state to FEN, queries Stockfish for the best move,
 * and converts the result back to the app's move format.
 * Falls back to [pickMoveCPU] if Stockfish is unavailable or returns an invalid move.
 *
 * @param engine The Stockfish engine instance (may be null)
 * @param gameState The current game UI state (needed for FEN conversion)
 * @param enemyPositions Positions of the opposing team's pieces
 * @param enemyPieces The opposing team's pieces
 * @param allyPositions Positions of the current team's pieces
 * @param allyPieces The current team's pieces
 * @return A Pair of (new position, piece index) representing the chosen move
 */
internal suspend fun pickMoveStockfish(
    engine: ChessEngine?,
    gameState: GameUiState,
    enemyPositions: List<Pair<Int, Int>>, enemyPieces: List<Piece>,
    allyPositions: List<Pair<Int, Int>>, allyPieces: List<Piece>,
): SelectedMove {
    if (engine == null) {
        return pickMoveCPU(enemyPositions, enemyPieces, allyPositions, allyPieces, gameState.castlingRights, gameState.enPassantTarget)
    }

    // Convert board state to FEN
    val fen = FenConverter.gameStateToFen(gameState)
    logger.d { "Stockfish FEN: $fen" }

    // Query Stockfish for the best move
    val bestMoveResult = engine.getBestMove(fen)

    if (bestMoveResult != null) {
        // Convert UCI move to app format
        val appMove = UciMoveConverter.uciMoveToAppMove(bestMoveResult.uci, allyPositions)
        if (appMove != null) {
            // Validate that this is actually a legal move
            val allLegal = getAllLegalMoves(enemyPositions, enemyPieces, allyPositions, allyPieces, gameState.castlingRights, gameState.enPassantTarget)
            if (allLegal.any { it.first == appMove.position && it.second == appMove.pieceIndex }) {
                logger.d { "Stockfish move accepted: ${bestMoveResult.uci} -> ${appMove.position}" }
                return appMove.copy(evaluationCp = bestMoveResult.evaluationCp)
            } else {
                logger.w { "Stockfish move ${bestMoveResult.uci} is not legal in app, falling back" }
            }
        } else {
            logger.w { "Could not convert Stockfish move ${bestMoveResult.uci}, falling back" }
        }
    } else {
        logger.w { "Stockfish returned no move, falling back to CPU" }
    }

    // Fall back to the simple CPU algorithm
    return pickMoveCPU(enemyPositions, enemyPieces, allyPositions, allyPieces, gameState.castlingRights, gameState.enPassantTarget)
}

/**
 * The engine-less opponent: a random capture when one exists (en passant included), otherwise a
 * random legal move. Returns `SelectedMove(INVALID_POSITION, -1)` when the side has no legal move.
 */
fun pickMoveCPU(
    enemyPositions: List<Pair<Int, Int>>, enemyPieces: List<Piece>,
    allyPositions: List<Pair<Int, Int>>, allyPieces: List<Piece>,
    castlingRights: CastlingRights = CastlingRights.NONE,
    enPassantTarget: Pair<Int, Int>? = null,
): SelectedMove {
    val legal = getAllLegalMoves(enemyPositions, enemyPieces, allyPositions, allyPieces, castlingRights, enPassantTarget)
    if (legal.isEmpty()) return SelectedMove(INVALID_POSITION, -1)
    val captures = legal.filter { (to, mover) ->
        to in enemyPositions || (to == enPassantTarget && allyPieces[mover] is Pawn)
    }
    val (to, mover) = captures.ifEmpty { legal }.random()
    return SelectedMove(to, mover)
}

/**
 * Pseudo-legal moves for every ally piece as `(destination, pieceIndex)`, in piece order. Check,
 * castling and en passant are not considered here; [getAllLegalMoves] adds them.
 */
internal fun getPossibleMoves(
    enemyPositions: List<Pair<Int, Int>>, allyPositions: List<Pair<Int, Int>>, allyPieces: List<Piece>,
): List<Pair<Pair<Int, Int>, Int>> =
    allyPieces.flatMapIndexed { index, piece ->
        piece.getValidMovesPositions(allyPositions[index], enemyPositions, allyPositions).map { it to index }
    }

/**
 * Whether some enemy piece could move onto [kingPosition], i.e. whether that square is attacked.
 * [allyPositions] must already reflect whatever hypothetical move is being tested.
 */
internal fun checkCheck(
    kingPosition: Pair<Int, Int>,
    enemyPositions: List<Pair<Int, Int>>, enemyPieces: List<Piece>,
    allyPositions: List<Pair<Int, Int>>,
): Boolean {
    // Seen from the enemy's side, our pieces are the ones it may capture — hence the swapped lists.
    val attacker = enemyPieces.indices.firstOrNull { i ->
        kingPosition in enemyPieces[i].getValidMovesPositions(enemyPositions[i], allyPositions, enemyPositions)
    } ?: return false
    logger.d { "King at $kingPosition is attacked by ${enemyPieces[attacker].name} at ${enemyPositions[attacker]}" }
    return true
}

/**
 * Whether playing [move] `(destination, pieceIndex)` leaves the mover's king unattacked. A captured
 * enemy piece is lifted off the board first so it cannot count as an attacker. A side with no king
 * (hand-built test positions only) is never in check.
 */
private fun leavesKingSafe(
    move: Pair<Pair<Int, Int>, Int>,
    enemyPositions: List<Pair<Int, Int>>, enemyPieces: List<Piece>,
    allyPositions: List<Pair<Int, Int>>, kingIndex: Int,
): Boolean {
    if (kingIndex == -1) return true
    val (destination, mover) = move
    val alliesAfter = allyPositions.toMutableList().also { it[mover] = destination }
    val victim = enemyPositions.indexOf(destination)
    val enemyPositionsAfter = if (victim == -1) enemyPositions else enemyPositions.withoutIndex(victim)
    val enemyPiecesAfter = if (victim == -1) enemyPieces else enemyPieces.withoutIndex(victim)
    return !checkCheck(alliesAfter[kingIndex], enemyPositionsAfter, enemyPiecesAfter, alliesAfter)
}

private fun <T> List<T>.withoutIndex(index: Int): List<T> = filterIndexed { i, _ -> i != index }

/**
 * Whether the side to move has any legal move. Castling is deliberately not consulted: it needs the
 * king's neighbouring square empty and unattacked, and stepping there is already a legal move.
 */
internal fun hasLegalMoves(
    enemyPositions: List<Pair<Int, Int>>, enemyPieces: List<Piece>,
    allyPositions: List<Pair<Int, Int>>, allyPieces: List<Piece>,
    enPassantTarget: Pair<Int, Int>? = null,
): Boolean {
    val kingIndex = allyPieces.indexOfFirst { it is King }
    val anyOrdinaryMove = getPossibleMoves(enemyPositions, allyPositions, allyPieces)
        .any { leavesKingSafe(it, enemyPositions, enemyPieces, allyPositions, kingIndex) }
    return anyOrdinaryMove ||
        getEnPassantMoves(enPassantTarget, enemyPositions, enemyPieces, allyPositions, allyPieces).isNotEmpty()
}

/** Legal destinations for the ally piece at [pieceIndex], castling and en passant included. */
fun getLegalMovesForPiece(
    pieceIndex: Int,
    enemyPositions: List<Pair<Int, Int>>, enemyPieces: List<Piece>,
    allyPositions: List<Pair<Int, Int>>, allyPieces: List<Piece>,
    castlingRights: CastlingRights = CastlingRights.NONE,
    enPassantTarget: Pair<Int, Int>? = null,
): List<Pair<Int, Int>> =
    getAllLegalMoves(enemyPositions, enemyPieces, allyPositions, allyPieces, castlingRights, enPassantTarget)
        .mapNotNull { (destination, mover) -> destination.takeIf { mover == pieceIndex } }

/**
 * Every legal move for the side to move as `(destination, pieceIndex)`: the pseudo-legal moves that
 * keep the king safe, followed by castling, followed by en passant.
 */
internal fun getAllLegalMoves(
    enemyPositions: List<Pair<Int, Int>>, enemyPieces: List<Piece>,
    allyPositions: List<Pair<Int, Int>>, allyPieces: List<Piece>,
    castlingRights: CastlingRights = CastlingRights.NONE,
    enPassantTarget: Pair<Int, Int>? = null,
): List<Pair<Pair<Int, Int>, Int>> {
    val kingIndex = allyPieces.indexOfFirst { it is King }
    val ordinary = getPossibleMoves(enemyPositions, allyPositions, allyPieces)
        .filter { leavesKingSafe(it, enemyPositions, enemyPieces, allyPositions, kingIndex) }
    return ordinary +
        getCastlingMoves(castlingRights, enemyPositions, enemyPieces, allyPositions, allyPieces) +
        getEnPassantMoves(enPassantTarget, enemyPositions, enemyPieces, allyPositions, allyPieces)
}

// Returns (rookFrom, rookTo) if this king move is castling, else null
internal fun castlingRookMove(piece: Piece, from: Pair<Int, Int>, to: Pair<Int, Int>): Pair<Pair<Int, Int>, Pair<Int, Int>>? {
    if (piece !is King || kotlin.math.abs(to.second - from.second) != 2) return null
    val row = to.first
    return if (to.second == 6) { // Kingside
        Pair(Pair(row, 7), Pair(row, 5))
    } else { // Queenside
        Pair(Pair(row, 0), Pair(row, 3))
    }
}

internal fun getCastlingMoves(
    castlingRights: CastlingRights,
    enemyPositions: List<Pair<Int, Int>>, enemyPieces: List<Piece>,
    allyPositions: List<Pair<Int, Int>>, allyPieces: List<Piece>,
): List<Pair<Pair<Int, Int>, Int>> {
    val castlingMoves = mutableListOf<Pair<Pair<Int, Int>, Int>>()
    val kingIndex = allyPieces.indexOfFirst { it is King }
    if (kingIndex == -1) return castlingMoves

    val isWhite = allyPieces[kingIndex].set == Set.WHITE
    val kingHome = if (isWhite) WHITE_KING_HOME else BLACK_KING_HOME
    
    // If king isn't on home square, can't castle
    if (allyPositions[kingIndex] != kingHome) return castlingMoves

    val ksRight = if (isWhite) castlingRights.whiteKingside else castlingRights.blackKingside
    val qsRight = if (isWhite) castlingRights.whiteQueenside else castlingRights.blackQueenside

    if (!ksRight && !qsRight) return castlingMoves

    val ksRookHome = if (isWhite) WHITE_KS_ROOK_HOME else BLACK_KS_ROOK_HOME
    val qsRookHome = if (isWhite) WHITE_QS_ROOK_HOME else BLACK_QS_ROOK_HOME
    val row = kingHome.first

    // Check if in check currently
    if (checkCheck(kingHome, enemyPositions, enemyPieces, allyPositions)) {
        return castlingMoves
    }

    // Kingside
    if (ksRight) {
        val ksRookIndex = allyPositions.indexOf(ksRookHome)
        if (ksRookIndex != -1 && allyPieces[ksRookIndex] is Rook) {
            val f = Pair(row, 5)
            val g = Pair(row, 6)
            if (f !in allyPositions && f !in enemyPositions &&
                g !in allyPositions && g !in enemyPositions) {
                
                // Simulate to check for check on f and g
                val simF = allyPositions.toMutableList().apply { set(kingIndex, f) }
                val simG = allyPositions.toMutableList().apply { set(kingIndex, g) }
                
                if (!checkCheck(f, enemyPositions, enemyPieces, simF) &&
                    !checkCheck(g, enemyPositions, enemyPieces, simG)) {
                    castlingMoves.add(Pair(g, kingIndex))
                }
            }
        }
    }

    // Queenside
    if (qsRight) {
        val qsRookIndex = allyPositions.indexOf(qsRookHome)
        if (qsRookIndex != -1 && allyPieces[qsRookIndex] is Rook) {
            val b = Pair(row, 1)
            val c = Pair(row, 2)
            val d = Pair(row, 3)
            if (b !in allyPositions && b !in enemyPositions &&
                c !in allyPositions && c !in enemyPositions &&
                d !in allyPositions && d !in enemyPositions) {
                
                // Simulate to check for check on d and c (b doesn't need to be unattacked)
                val simD = allyPositions.toMutableList().apply { set(kingIndex, d) }
                val simC = allyPositions.toMutableList().apply { set(kingIndex, c) }
                
                if (!checkCheck(d, enemyPositions, enemyPieces, simD) &&
                    !checkCheck(c, enemyPositions, enemyPieces, simC)) {
                    castlingMoves.add(Pair(c, kingIndex))
                }
            }
        }
    }

    return castlingMoves
}

internal fun getEnPassantMoves(
    enPassantTarget: Pair<Int, Int>?,
    enemyPositions: List<Pair<Int, Int>>, enemyPieces: List<Piece>,
    allyPositions: List<Pair<Int, Int>>, allyPieces: List<Piece>,
): List<Pair<Pair<Int, Int>, Int>> {
    val enPassantMoves = mutableListOf<Pair<Pair<Int, Int>, Int>>()
    if (enPassantTarget == null) return enPassantMoves

    // The king is needed twice: its colour says which way the victim pawn sits, and its square is
    // what must not end up attacked.
    val kingIndex = allyPieces.indexOfFirst { it is King }.takeIf { it != -1 } ?: return enPassantMoves
    val isWhite = allyPieces[kingIndex].set == Set.WHITE

    val victimRow = if (isWhite) enPassantTarget.first + 1 else enPassantTarget.first - 1
    val victimPos = Pair(victimRow, enPassantTarget.second)
    val victimIndex = enemyPositions.indexOf(victimPos)
    if (victimIndex == -1 || enemyPieces[victimIndex] !is Pawn) return enPassantMoves

    val candidateCols = listOf(enPassantTarget.second - 1, enPassantTarget.second + 1)
    for (col in candidateCols) {
        if (col !in 0 until BOARD_SIZE) continue
        val candidatePos = Pair(victimRow, col)
        val candidateIndex = allyPositions.indexOf(candidatePos)
        if (candidateIndex != -1 && allyPieces[candidateIndex] is Pawn) {
            val simAllyPositions = allyPositions.toMutableList().apply { set(candidateIndex, enPassantTarget) }
            val simEnemyPositions = enemyPositions.filterIndexed { index, _ -> index != victimIndex }
            val simEnemyPieces = enemyPieces.filterIndexed { index, _ -> index != victimIndex }

            val kingPosition = if (candidateIndex == kingIndex) enPassantTarget else simAllyPositions[kingIndex]

            if (!checkCheck(kingPosition, simEnemyPositions, simEnemyPieces, simAllyPositions)) {
                enPassantMoves.add(Pair(enPassantTarget, candidateIndex))
            }
        }
    }

    return enPassantMoves
}

/**
 * Pure state transition: apply a single move (selected by piece index + destination square,
 * with optional promotion) to [state] and return the resulting [GameUiState]. The mover is
 * always [GameUiState.turn] — the ally/enemy split is derived from it. This is the function
 * the perft harness exercises; [GameViewModel.deriveNewGameState] delegates here.
 *
 * Behavior is identical to the former body of `deriveNewGameState`; the only change is that
 * every previously-implicit `_gameState.value.X` read is now an explicit [state].X read.
 */
fun applyMove(
    state: GameUiState,
    pieceIndex: Int,
    newPosition: Pair<Int, Int>,
    promotion: PromotionType? = null
): GameUiState {
    val turn = state.turn
    val whiteMoves = turn == Set.WHITE
    val allyPositions = if (whiteMoves) state.positionsWhite else state.positionsBlack
    val allyPieces = if (whiteMoves) state.piecesWhite else state.piecesBlack
    val enemyPositions = if (whiteMoves) state.positionsBlack else state.positionsWhite
    val enemyPieces = if (whiteMoves) state.piecesBlack else state.piecesWhite

    val mutableEnemyPieces = enemyPieces.toMutableList()
    val mutableEnemyPositions = enemyPositions.toMutableList()
    val mutableAllyPositions = allyPositions.toMutableList()
    val mutableAllyPieces = allyPieces.toMutableList()

    logger.d { "Moving $turn ${allyPieces[pieceIndex].name} from ${allyPositions[pieceIndex]} to $newPosition" }

    var updatedRights = state.castlingRights
    var captureOccurred = false
    fun removeEnemyAt(victim: Int) {
        mutableEnemyPositions.removeAt(victim)
        mutableEnemyPieces.removeAt(victim)
        captureOccurred = true
    }

    if (newPosition in enemyPositions) {
        val victim = enemyPositions.indexOf(newPosition)
        logger.i { "${when (turn) { Set.WHITE -> Set.BLACK.name; Set.BLACK -> Set.WHITE.name }} ${enemyPieces[victim].name} was captured!" }

        val capturedPiece = enemyPieces[victim]
        if (capturedPiece is Rook) {
            if (turn == Set.WHITE) {
                if (newPosition == BLACK_KS_ROOK_HOME) updatedRights = updatedRights.copy(blackKingside = false)
                if (newPosition == BLACK_QS_ROOK_HOME) updatedRights = updatedRights.copy(blackQueenside = false)
            } else {
                if (newPosition == WHITE_KS_ROOK_HOME) updatedRights = updatedRights.copy(whiteKingside = false)
                if (newPosition == WHITE_QS_ROOK_HOME) updatedRights = updatedRights.copy(whiteQueenside = false)
            }
        }

        removeEnemyAt(victim)
    } else if (allyPieces[pieceIndex] is Pawn && allyPositions[pieceIndex].second != newPosition.second && newPosition == state.enPassantTarget) {
        val victimPosition = Pair(allyPositions[pieceIndex].first, newPosition.second)
        val victim = enemyPositions.indexOf(victimPosition)
        if (victim != -1) {
            logger.i { "${when (turn) { Set.WHITE -> Set.BLACK.name; Set.BLACK -> Set.WHITE.name }} ${enemyPieces[victim].name} was captured en passant!" }
            removeEnemyAt(victim)
        }
    }

    val movingPiece = allyPieces[pieceIndex]
    val fromPosition = allyPositions[pieceIndex]

    val newHalfmoveClock = if (captureOccurred || movingPiece is Pawn) 0
                           else state.halfmoveClock + 1
    val newFullmoveNumber = state.fullmoveNumber + if (turn == Set.BLACK) 1 else 0

    val newEnPassantTarget = if (movingPiece is Pawn && kotlin.math.abs(newPosition.first - fromPosition.first) == 2) {
        Pair((newPosition.first + fromPosition.first) / 2, fromPosition.second)
    } else {
        null
    }

    if (movingPiece is King) {
        if (turn == Set.WHITE) {
            updatedRights = updatedRights.copy(whiteKingside = false, whiteQueenside = false)
        } else {
            updatedRights = updatedRights.copy(blackKingside = false, blackQueenside = false)
        }
    } else if (movingPiece is Rook) {
        if (turn == Set.WHITE) {
            if (fromPosition == WHITE_KS_ROOK_HOME) updatedRights = updatedRights.copy(whiteKingside = false)
            if (fromPosition == WHITE_QS_ROOK_HOME) updatedRights = updatedRights.copy(whiteQueenside = false)
        } else {
            if (fromPosition == BLACK_KS_ROOK_HOME) updatedRights = updatedRights.copy(blackKingside = false)
            if (fromPosition == BLACK_QS_ROOK_HOME) updatedRights = updatedRights.copy(blackQueenside = false)
        }
    }

    mutableAllyPositions[pieceIndex] = newPosition // a castling rook follows just below

    castlingRookMove(movingPiece, fromPosition, newPosition)?.let { (rookFrom, rookTo) ->
        val rookIndex = allyPositions.indexOf(rookFrom)
        if (rookIndex != -1) {
            mutableAllyPositions[rookIndex] = rookTo
        }
    }
    if (isPromotionMove(allyPieces[pieceIndex], newPosition)) {
        val promoted = (promotion ?: PromotionType.QUEEN).toPiece(turn)
        logger.i { "$turn Pawn promoted to ${promoted.name}!" }
        mutableAllyPieces[pieceIndex] = promoted
    }

    val allyKingIndex = mutableAllyPieces.indexOfFirst { it is King }
    val allyInCheck = if (allyKingIndex != -1) checkCheck(
        mutableAllyPositions[allyKingIndex],
        mutableEnemyPositions,
        mutableEnemyPieces,
        mutableAllyPositions
    ) else false

    val enemyKingIndex = mutableEnemyPieces.indexOfFirst { it is King }
    val enemyInCheck = if (enemyKingIndex != -1) checkCheck(
        mutableEnemyPositions[enemyKingIndex],
        mutableAllyPositions,
        mutableAllyPieces,
        mutableEnemyPositions
    ) else false

    val nextTurn = if (whiteMoves) Set.BLACK else Set.WHITE

    if (allyInCheck) {
        logger.i { "Ally $turn in Check!" }
    } else if (enemyInCheck) {
        logger.i { "Enemy $nextTurn in Check!" }
    }

    // Map ally/enemy back onto colours once, instead of spelling the copy out per side.
    return state.copy(
        turn = nextTurn,
        piecesWhite = if (whiteMoves) mutableAllyPieces else mutableEnemyPieces,
        positionsWhite = if (whiteMoves) mutableAllyPositions else mutableEnemyPositions,
        piecesBlack = if (whiteMoves) mutableEnemyPieces else mutableAllyPieces,
        positionsBlack = if (whiteMoves) mutableEnemyPositions else mutableAllyPositions,
        inCheckWhite = if (whiteMoves) allyInCheck else enemyInCheck,
        inCheckBlack = if (whiteMoves) enemyInCheck else allyInCheck,
        pendingPromotion = null,
        castlingRights = updatedRights,
        enPassantTarget = newEnPassantTarget,
        halfmoveClock = newHalfmoveClock,
        fullmoveNumber = newFullmoveNumber,
        drawOffer = null,
        drawOfferDeclinedBy = null,
        selectedSquare = INVALID_POSITION,
    )
}

/**
 * Evaluate win conditions on a state (checkmate / stalemate). Pure: reads only [state].
 * Top-level so [applyMove] can call it without going through the ViewModel.
 */
fun applyWinConditions(state: GameUiState): GameUiState {
    if (state.winState != WinState.NONE) return state

    // Re-evaluate check status to be safe, especially if loaded from FEN
    val whiteKingIndex = state.piecesWhite.indexOfFirst { it is King }
    val inCheckWhite = if (whiteKingIndex != -1) {
        checkCheck(state.positionsWhite[whiteKingIndex], state.positionsBlack, state.piecesBlack, state.positionsWhite)
    } else false

    val blackKingIndex = state.piecesBlack.indexOfFirst { it is King }
    val inCheckBlack = if (blackKingIndex != -1) {
        checkCheck(state.positionsBlack[blackKingIndex], state.positionsWhite, state.piecesWhite, state.positionsBlack)
    } else false

    // Update the state with actual check statuses
    var updatedState = state.copy(inCheckWhite = inCheckWhite, inCheckBlack = inCheckBlack)

    val enemyPositions = if (updatedState.turn == Set.WHITE) updatedState.positionsBlack else updatedState.positionsWhite
    val enemyPieces = if (updatedState.turn == Set.WHITE) updatedState.piecesBlack else updatedState.piecesWhite
    val allyPositions = if (updatedState.turn == Set.WHITE) updatedState.positionsWhite else updatedState.positionsBlack
    val allyPieces = if (updatedState.turn == Set.WHITE) updatedState.piecesWhite else updatedState.piecesBlack

    val hasMoves = hasLegalMoves(enemyPositions, enemyPieces, allyPositions, allyPieces, updatedState.enPassantTarget)

    if (!hasMoves) {
        val inCheck = if (updatedState.turn == Set.WHITE) inCheckWhite else inCheckBlack
        if (inCheck) {
            updatedState = updatedState.copy(winState = if (updatedState.turn == Set.WHITE) WinState.BLACK else WinState.WHITE)
        } else {
            updatedState = updatedState.copy(winState = WinState.STALEMATE)
        }
    }
    return updatedState
}