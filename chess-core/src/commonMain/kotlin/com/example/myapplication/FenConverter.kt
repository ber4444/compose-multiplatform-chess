package com.example.myapplication

/**
 * Converts between the app's internal board representation and FEN (Forsyth-Edwards Notation)
 * used by Stockfish and other UCI chess engines.
 *
 * Board coordinate mapping:
 *   App row 0 = rank 8 (top of board, black's back rank)
 *   App row 7 = rank 1 (bottom of board, white's back rank)
 *   App column 0 = file a (left side)
 *   App column 7 = file h (right side)
 */
object FenConverter {

    /** Standard starting position FEN. */
    const val STARTING_FEN = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"

    /**
     * Convert a piece to its FEN character representation.
     * White pieces are uppercase, black pieces are lowercase.
     */
    fun pieceToFenChar(piece: Piece): Char {
        val base = when (piece) {
            is King -> 'k'
            is Queen -> 'q'
            is Rook -> 'r'
            is Bishop -> 'b'
            is Knight -> 'n'
            is Pawn -> 'p'
            else -> '?'
        }
        return if (piece.set == Set.WHITE) base.uppercaseChar() else base
    }

    fun fenCharToPiece(pieceChar: Char): Piece {
        val set = if (pieceChar.isUpperCase()) Set.WHITE else Set.BLACK
        return when (pieceChar.lowercaseChar()) {
            'k' -> King(set)
            'q' -> Queen(set)
            'r' -> Rook(set)
            'b' -> Bishop(set)
            'n' -> Knight(set)
            'p' -> Pawn(set)
            else -> throw IllegalArgumentException("Unsupported FEN piece: $pieceChar")
        }
    }

    /** First four FEN fields (placement, active color, castling, en passant) —
     *  the position identity used for threefold-repetition detection. */
    fun positionKey(gameState: GameUiState): String {
        // Build the board array (8x8, null = empty)
        val board = Array(BOARD_SIZE) { arrayOfNulls<Piece>(BOARD_SIZE) }

        // Place white pieces
        for (i in gameState.piecesWhite.indices) {
            val pos = gameState.positionsWhite[i]
            board[pos.first][pos.second] = gameState.piecesWhite[i]
        }

        // Place black pieces
        for (i in gameState.piecesBlack.indices) {
            val pos = gameState.positionsBlack[i]
            board[pos.first][pos.second] = gameState.piecesBlack[i]
        }

        // Build FEN piece placement string (rank 8 to rank 1, i.e., row 0 to row 7)
        val fenRows = mutableListOf<String>()
        for (row in 0 until BOARD_SIZE) {
            val sb = StringBuilder()
            var emptyCount = 0
            for (col in 0 until BOARD_SIZE) {
                val piece = board[row][col]
                if (piece == null) {
                    emptyCount++
                } else {
                    if (emptyCount > 0) {
                        sb.append(emptyCount)
                        emptyCount = 0
                    }
                    sb.append(pieceToFenChar(piece))
                }
            }
            if (emptyCount > 0) {
                sb.append(emptyCount)
            }
            fenRows.add(sb.toString())
        }

        val piecePlacement = fenRows.joinToString("/")

        // Active color
        val activeColor = if (gameState.turn == Set.WHITE) "w" else "b"

        // Castling and en passant
        val rights = gameState.castlingRights
        val castling = buildString {
            if (rights.whiteKingside) append('K')
            if (rights.whiteQueenside) append('Q')
            if (rights.blackKingside) append('k')
            if (rights.blackQueenside) append('q')
            if (isEmpty()) append('-')
        }
        val enPassant = gameState.enPassantTarget
            ?.let { UciMoveConverter.positionToUciSquare(it) } ?: "-"

        return "$piecePlacement $activeColor $castling $enPassant"
    }

    /**
     * Convert the current game state to a FEN string.
     *
     * @param gameState The current game UI state
     * @return A FEN string representing the board position
     */
    fun gameStateToFen(gameState: GameUiState): String =
        "${positionKey(gameState)} ${gameState.halfmoveClock} ${gameState.fullmoveNumber}"

    /**
     * Convert a FEN string into the app's board model.
     * Halfmove clock and fullmove number are parsed to track fifty-move rule.
     */
    fun fenToGameState(fen: String): GameUiState {
        val parts = fen.trim().split(" ")
        require(parts.size >= 2) { "Invalid FEN: $fen" }

        val rows = parts[0].split("/")
        require(rows.size == BOARD_SIZE) { "Invalid FEN board rows: ${parts[0]}" }

        val piecesWhite = mutableListOf<Piece>()
        val positionsWhite = mutableListOf<Pair<Int, Int>>()
        val piecesBlack = mutableListOf<Piece>()
        val positionsBlack = mutableListOf<Pair<Int, Int>>()

        rows.forEachIndexed { rowIndex, row ->
            var columnIndex = 0
            row.forEach { symbol ->
                when {
                    symbol.isDigit() -> {
                        columnIndex += symbol.digitToInt()
                    }

                    else -> {
                        require(columnIndex in 0 until BOARD_SIZE) { "Invalid FEN column index for row: $row" }
                        val piece = fenCharToPiece(symbol)
                        val position = Pair(rowIndex, columnIndex)
                        if (piece.set == Set.WHITE) {
                            piecesWhite += piece
                            positionsWhite += position
                        } else {
                            piecesBlack += piece
                            positionsBlack += position
                        }
                        columnIndex++
                    }
                }
            }

            require(columnIndex == BOARD_SIZE) { "Invalid FEN row width: $row" }
        }

        val turn = when (parts[1]) {
            "w" -> Set.WHITE
            "b" -> Set.BLACK
            else -> throw IllegalArgumentException("Invalid active color in FEN: ${parts[1]}")
        }

        val castlingRights = if (parts.size >= 3 && parts[2] != "-") {
            CastlingRights(
                whiteKingside = parts[2].contains('K'),
                whiteQueenside = parts[2].contains('Q'),
                blackKingside = parts[2].contains('k'),
                blackQueenside = parts[2].contains('q')
            )
        } else {
            CastlingRights.NONE
        }

        val enPassantTarget = if (parts.size >= 4 && parts[3] != "-")
            UciMoveConverter.uciSquareToPosition(parts[3]) else null

        val halfmoveClock = parts.getOrNull(4)?.toIntOrNull() ?: 0
        val fullmoveNumber = parts.getOrNull(5)?.toIntOrNull() ?: 1

        return GameUiState(
            turn = turn,
            piecesWhite = piecesWhite,
            positionsWhite = positionsWhite,
            piecesBlack = piecesBlack,
            positionsBlack = positionsBlack,
            castlingRights = castlingRights,
            enPassantTarget = enPassantTarget,
            halfmoveClock = halfmoveClock,
            fullmoveNumber = fullmoveNumber
        )
    }
}
