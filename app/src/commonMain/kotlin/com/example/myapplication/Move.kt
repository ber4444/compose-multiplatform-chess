package com.example.myapplication

import co.touchlab.kermit.Logger

private val logger = Logger.withTag("Move")

// Used to represent an invalid position on the board
//  y and x values must always be between 0 and 8
val INVALID_POSITION = Pair(-1, -1)

val WHITE_KING_HOME = Pair(7, 4)
val WHITE_KS_ROOK_HOME = Pair(7, 7)
val WHITE_QS_ROOK_HOME = Pair(7, 0)
val BLACK_KING_HOME = Pair(0, 4)
val BLACK_KS_ROOK_HOME = Pair(0, 7)
val BLACK_QS_ROOK_HOME = Pair(0, 0)

data class SelectedMove(
    val position: Pair<Int, Int>,
    val pieceIndex: Int,
    val promotion: PromotionType? = null
)

fun isPromotionMove(piece: Piece, newPosition: Pair<Int, Int>): Boolean =
    piece is Pawn && newPosition.first == if (piece.set == Set.WHITE) 0 else BOARD_SIZE - 1

// Return a randomly selected move
fun pickMoveRandom(
    enemyPositions: List<Pair<Int, Int>>,
    enemyPieces: List<Piece>,
    allyPositions: List<Pair<Int, Int>>,
    allyPieces: List<Piece>
): SelectedMove {
    // If no newPosition is assigned, returns an invalid position (not a possible move)
    var newPosition: Pair<Int, Int> = INVALID_POSITION
    var newPositionIndex = -1

    // Go through the Pieces in a random order
    val shuffledAllyIndexes = (0 until allyPieces.size).toList().shuffled()

    // Going through all the ally Pieces,
    for (i in 0 until shuffledAllyIndexes.size) {
        // Get all possible moves for the Piece
        val possibleMoves = allyPieces[shuffledAllyIndexes[i]].
            getValidMovesPositions(allyPositions[shuffledAllyIndexes[i]], enemyPositions, allyPositions)

        // If there are possible moves,
        if (possibleMoves.isNotEmpty()) {
            // Have the Piece take a random move
            newPosition = possibleMoves.random()
            newPositionIndex = shuffledAllyIndexes[i]
            break // Break to return the random move
        }
    }

    // Return the newPosition to update the PositionIndex with
    return SelectedMove(newPosition, newPositionIndex)
}

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
suspend fun pickMoveStockfish(
    engine: ChessEngine?,
    gameState: GameUiState,
    enemyPositions: List<Pair<Int, Int>>,
    enemyPieces: List<Piece>,
    allyPositions: List<Pair<Int, Int>>,
    allyPieces: List<Piece>
): SelectedMove {
    if (engine == null) {
        return pickMoveCPU(enemyPositions, enemyPieces, allyPositions, allyPieces, gameState.castlingRights, gameState.enPassantTarget)
    }

    // Convert board state to FEN
    val fen = FenConverter.gameStateToFen(gameState)
    logger.d { "Stockfish FEN: $fen" }

    // Query Stockfish for the best move
    val bestMoveUci = engine.getBestMove(fen)

    if (bestMoveUci != null) {
        // Convert UCI move to app format
        val appMove = UciMoveConverter.uciMoveToAppMove(bestMoveUci, allyPositions)
        if (appMove != null) {
            // Validate that this is actually a legal move
            val allLegal = getAllLegalMoves(enemyPositions, enemyPieces, allyPositions, allyPieces, gameState.castlingRights, gameState.enPassantTarget)
            if (allLegal.any { it.first == appMove.position && it.second == appMove.pieceIndex }) {
                logger.d { "Stockfish move accepted: $bestMoveUci -> ${appMove.position}" }
                return appMove
            } else {
                logger.w { "Stockfish move $bestMoveUci is not legal in app, falling back" }
            }
        } else {
            logger.w { "Could not convert Stockfish move $bestMoveUci, falling back" }
        }
    } else {
        logger.w { "Stockfish returned no move, falling back to CPU" }
    }

    // Fall back to the simple CPU algorithm
    return pickMoveCPU(enemyPositions, enemyPieces, allyPositions, allyPieces, gameState.castlingRights, gameState.enPassantTarget)
}

fun pickMoveCPU(
    enemyPositions: List<Pair<Int, Int>>,
    enemyPieces: List<Piece>,
    allyPositions: List<Pair<Int, Int>>,
    allyPieces: List<Piece>,
    castlingRights: CastlingRights = CastlingRights.NONE,
    enPassantTarget: Pair<Int, Int>? = null
): SelectedMove {
    // Determine all possible moves given the state of the board
    val allPossibleMoves = getAllLegalMoves(
        enemyPositions = enemyPositions,
        enemyPieces = enemyPieces,
        allyPositions = allyPositions,
        allyPieces = allyPieces,
        castlingRights = castlingRights,
        enPassantTarget = enPassantTarget
    )
    if(allPossibleMoves.isEmpty()) return SelectedMove(INVALID_POSITION, -1)


    // Focus on capturing enemy Pieces
    val captureMoves = allPossibleMoves.filter { it.first in enemyPositions || (it.first == enPassantTarget && allyPieces[it.second] is Pawn) }
    if(captureMoves.isNotEmpty()) {
        val move = captureMoves.random()
        return SelectedMove(move.first, move.second)
    }

    // Otherwise, return a random possible move
    val move = allPossibleMoves.random()
    return SelectedMove(move.first, move.second)
}

// Get the possible moves for all ally Pieces
fun getPossibleMoves(
    enemyPositions: List<Pair<Int, Int>>,
    //enemyPieces: List<Piece>, // [REMOVE]: Could pass to getValidMoves to determine if a taken move would put the Enemy King in Check (not efficient, only needed for CPU)
    allyPositions: List<Pair<Int, Int>>,
    allyPieces: List<Piece>) : List<Pair<Pair<Int, Int>, Int>> {
    // Determine what moves are possible for the given team, given the board information
    val possibleMoves : MutableList<Pair<Pair<Int, Int>, Int>> = mutableListOf() // List of (Position(y, x), PieceIndex) pairs

    // For every allied Piece,
    for(pieceIndex in 0 until allyPieces.size) {
        // Determine the current Piece's possible move locations
        val pieceType = allyPieces[pieceIndex]
        val allyPosition = allyPositions[pieceIndex]
        val pieceMoves = pieceType.getValidMovesPositions(allyPosition, enemyPositions, allyPositions)

        // Add each possible location, paired with the current pieceIndex
        for (move in pieceMoves) {
            possibleMoves += Pair(move, pieceIndex)
        }
    }
    return possibleMoves
}

// Return if the King is in Check/Checkmate
fun checkCheck(
    kingPosition : Pair<Int, Int>,
    enemyPositions: List<Pair<Int, Int>>,
    enemyPieces: List<Piece>,
    allyPositions: List<Pair<Int, Int>>
): Boolean {
    // DEBUG: When is Check looked for, [BUG] sometimes movement happens after a move resulting in Check
    //println("Checking King at ${kingPosition}..")

    // TODO [EFFICIENCY]: Rewrite King.amIDead logic to check if anyone can get to the King
    //  instead of checking all Enemy movement with getPossibleMoves
    //  Can use enemyPieces to determine what types of moves to look for
    //  (if no queens or bishops, don't need to check diagonals)
    // Using getPossibleMoves,
    val enemyMoves = getPossibleMoves(allyPositions, enemyPositions, enemyPieces)

    // Determine if the King can be attacked by any possible Enemy move
    val checkMoveIndex = enemyMoves.map { it.first }.indexOf(kingPosition)

    // DEBUG: Show which Piece poses a threat]
    if(checkMoveIndex != -1) {
        val attackerIndex = enemyMoves[checkMoveIndex].second
        logger.d { "King at ${kingPosition} is at risk of attack from ${enemyPieces[attackerIndex].name} at ${enemyPositions[attackerIndex]}!" }
    }

    // If an enemy can reach the King, they are in Check
    return checkMoveIndex != -1

    /*
    // Return if the Piece is at risk by one or more Enemy Pieces
    return kingPosition in enemyMoves.map {
        it.first
    }*/

    // Logic from previous King.amIDead
    // Ignores Pawns and the opposing King (explicitly checks Enemy Piece type)
    /*
    val bishopMovement = listOf(Pair(1, 1), Pair(1, -1), Pair(-1, 1), Pair(-1, -1))
    val rookMovement = listOf(Pair(1, 0), Pair(-1, 0), Pair(0, 1), Pair(0, -1))
    val knightMovement = listOf(
        Pair(2, 1), Pair(1, 2), Pair(-1, 2), Pair(-2, 1),
        Pair(-2, -1), Pair(-1, -2), Pair(1, -2), Pair(2, -1)
    )

    // Not sure what 4 stands for here
    for (i in 0 until 4) {
        var rookX = kingPosition.first + rookMovement[i].first
        var rookY = kingPosition.second + rookMovement[i].second
        var bishopX = kingPosition.first + bishopMovement[i].first
        var bishopY = kingPosition.second + bishopMovement[i].second

        // Checks if Pieces that move like a Rook to get to the King
        while (rookX in 0 until BOARD_SIZE && rookY in 0 until BOARD_SIZE) {
            val pos = listOf(rookX, rookY)
            if (enemyPositions.contains(pos)) { // if this space has a rook or queen, we're dead!
                val pieceIndex = enemyPositions.indexOfFirst { it == pos }
                when (enemyPieces[pieceIndex]) {
                    is Queen, is Rook -> return true
                }
            } else if (allyPositions.contains(pos)) { // friend is blocking!
                break
            }
            rookX += rookMovement[i].first
            rookY += rookMovement[i].second
        }

        // Checks if Pieces that move like a Bishop can get to the King
        while (bishopX in 0 until BOARD_SIZE && bishopY in 0 until BOARD_SIZE) {
            val pos = listOf(bishopX, bishopY)
            if (enemyPositions.contains(pos)) { // if this space has a bishop or queen, we're dead!
                val pieceIndex = enemyPositions.indexOfFirst { it == pos }
                when (enemyPieces[pieceIndex]) {
                    is Queen, is Bishop -> return true
                }
            } else if (allyPositions.contains(pos)) { // friend is blocking!
                break
            }
            // move in the direction and see if the next square is also good
            bishopX += bishopMovement[i].first
            bishopY += bishopMovement[i].second
        }
    }

    // Checks if Pieces that move like a Knight can get to the King
    for (direction in knightMovement) {
        var x = kingPosition.first + direction.first
        var y = kingPosition.second + direction.second
        if (x in 0 until BOARD_SIZE && y in 0 until BOARD_SIZE && enemyPositions.contains(listOf(x, y))) {
            val pieceIndex = enemyPositions.indexOfFirst { it == listOf(x, y) }
            when (enemyPieces[pieceIndex]) {
                is Knight -> return true
            }
        }
    }

    // Nobody can reach the King right now
    return false */
}

// Return if the given team has any valid moves
fun hasLegalMoves(
    enemyPositions: List<Pair<Int, Int>>,
    enemyPieces: List<Piece>,
    allyPositions: List<Pair<Int, Int>>,
    allyPieces: List<Piece>,
    enPassantTarget: Pair<Int, Int>? = null
): Boolean {
    // Using getPossibleMoves,
    val possibleMoves = getPossibleMoves(enemyPositions, allyPositions, allyPieces)
    val kingIndex = allyPieces.indexOfFirst { it is King }
    for (move in possibleMoves) {
        // move = Pair(Pair(y,x), pieceIndex)
        // If there is at least one valid move, return true
        val kingPosition = if (move.second == kingIndex) move.first else allyPositions[kingIndex]
        val updatedAllyPositions = allyPositions.toMutableList()
        updatedAllyPositions[move.second] = move.first
        var tempEnemyPositions = enemyPositions
        var tempEnemyPieces = enemyPieces
        val capturedEnemyIndex = enemyPositions.indexOf(move.first)
        if (capturedEnemyIndex != -1) {
            // If a capture happened, create new lists WITHOUT the captured piece.
            tempEnemyPositions = enemyPositions.filterIndexed { index, _ -> index != capturedEnemyIndex }
            tempEnemyPieces = enemyPieces.filterIndexed { index, _ -> index != capturedEnemyIndex }
        }
        val isKingSafe = !checkCheck(
            kingPosition = kingPosition,
            enemyPositions = tempEnemyPositions,
            enemyPieces = tempEnemyPieces,
            allyPositions = updatedAllyPositions
        )
        if (isKingSafe) {
            return true
        }
    }
    
    val enPassantMoves = getEnPassantMoves(enPassantTarget, enemyPositions, enemyPieces, allyPositions, allyPieces)
    if (enPassantMoves.isNotEmpty()) {
        return true
    }

    return false
}

fun getLegalMovesForPiece(
    pieceIndex: Int,
    enemyPositions: List<Pair<Int, Int>>,
    enemyPieces: List<Piece>,
    allyPositions: List<Pair<Int, Int>>,
    allyPieces: List<Piece>,
    castlingRights: CastlingRights = CastlingRights.NONE,
    enPassantTarget: Pair<Int, Int>? = null
) : List<Pair<Int, Int>> {
    val allLegalMoves = getAllLegalMoves(
        enemyPositions = enemyPositions,
        enemyPieces = enemyPieces,
        allyPositions = allyPositions,
        allyPieces = allyPieces,
        castlingRights = castlingRights,
        enPassantTarget = enPassantTarget
    )
    return allLegalMoves.filter { it.second == pieceIndex }.map { it.first }
}

fun getAllLegalMoves(
    enemyPositions: List<Pair<Int, Int>>,
    enemyPieces: List<Piece>,
    allyPositions: List<Pair<Int, Int>>,
    allyPieces: List<Piece>,
    castlingRights: CastlingRights = CastlingRights.NONE,
    enPassantTarget: Pair<Int, Int>? = null
) : List<Pair<Pair<Int, Int>, Int>> {
    val legalMoves : MutableList<Pair<Pair<Int, Int>, Int>> = mutableListOf()
    // Using getPossibleMoves,
    val possibleMoves = getPossibleMoves(enemyPositions, allyPositions, allyPieces)
    val kingIndex = allyPieces.indexOfFirst { it is King }
    for (move in possibleMoves) {
        // move = Pair(Pair(y,x), pieceIndex)
        val kingPosition = if (move.second == kingIndex) move.first else allyPositions[kingIndex]

        val updatedAllyPositions = allyPositions.toMutableList()
        updatedAllyPositions[move.second] = move.first

        var tempEnemyPositions = enemyPositions
        var tempEnemyPieces = enemyPieces
        val capturedEnemyIndex = enemyPositions.indexOf(move.first)
        if (capturedEnemyIndex != -1) {
            // If a capture happened, create new lists WITHOUT the captured piece.
            tempEnemyPositions = enemyPositions.filterIndexed { index, _ -> index != capturedEnemyIndex }
            tempEnemyPieces = enemyPieces.filterIndexed { index, _ -> index != capturedEnemyIndex }
        }
        val isKingSafe = !checkCheck(
            kingPosition = kingPosition,
            enemyPositions = tempEnemyPositions,
            enemyPieces = tempEnemyPieces,
            allyPositions = updatedAllyPositions
        )
        if (isKingSafe) {
            legalMoves.add(move)
        }
    }
    legalMoves.addAll(getCastlingMoves(castlingRights, enemyPositions, enemyPieces, allyPositions, allyPieces))
    legalMoves.addAll(getEnPassantMoves(enPassantTarget, enemyPositions, enemyPieces, allyPositions, allyPieces))
    return legalMoves
}

// Returns (rookFrom, rookTo) if this king move is castling, else null
fun castlingRookMove(piece: Piece, from: Pair<Int, Int>, to: Pair<Int, Int>): Pair<Pair<Int, Int>, Pair<Int, Int>>? {
    if (piece !is King || kotlin.math.abs(to.second - from.second) != 2) return null
    val row = to.first
    return if (to.second == 6) { // Kingside
        Pair(Pair(row, 7), Pair(row, 5))
    } else { // Queenside
        Pair(Pair(row, 0), Pair(row, 3))
    }
}

fun getCastlingMoves(
    castlingRights: CastlingRights,
    enemyPositions: List<Pair<Int, Int>>,
    enemyPieces: List<Piece>,
    allyPositions: List<Pair<Int, Int>>,
    allyPieces: List<Piece>
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

fun getEnPassantMoves(
    enPassantTarget: Pair<Int, Int>?,
    enemyPositions: List<Pair<Int, Int>>,
    enemyPieces: List<Piece>,
    allyPositions: List<Pair<Int, Int>>,
    allyPieces: List<Piece>
): List<Pair<Pair<Int, Int>, Int>> {
    val enPassantMoves = mutableListOf<Pair<Pair<Int, Int>, Int>>()
    if (enPassantTarget == null) return enPassantMoves
    
    val kingIndex = allyPieces.indexOfFirst { it is King }
    if (kingIndex == -1) return enPassantMoves
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