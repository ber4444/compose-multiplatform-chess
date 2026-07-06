package com.example.myapplication

/**
 * Generates Standard Algebraic Notation (SAN) for a single move, given the pre-move state and
 * enough move metadata (capture / castling / promotion / check / mate).
 *
 * Inputs mirror what [GameViewModel.deriveNewGameState] already computes, so SAN generation is a
 * pure transformation on that data — no engine access, no global state.
 *
 * SAN rules implemented:
 *  - Castling: `O-O` / `O-O-O` (suffix still appended: `O-O+`).
 *  - Piece letter: `K`/`Q`/`R`/`B`/`N`; pawns have none.
 *  - Disambiguation (non-pawn): if another ally piece of the same type can also legally move to
 *    the destination square, add the minimum disambiguator — source file letter if unique among
 *    the candidates, else source rank digit, else both.
 *  - Capture: `x` before destination. Pawn captures prefix the source file letter (`exd5`).
 *  - Destination square via [UciMoveConverter.positionToUciSquare].
 *  - Promotion: `=Q` / `=N` (uppercase).
 *  - Check/mate suffix appended last (`+` / `#`).
 */
object SanConverter {

    fun toSan(
        preMove: GameUiState,
        pieceIndex: Int,
        from: Pair<Int, Int>,
        to: Pair<Int, Int>,
        movingPiece: Piece,
        isCapture: Boolean,
        promotion: PromotionType?,
        castleRook: Pair<Pair<Int, Int>, Pair<Int, Int>>?,
        checkSuffix: String,
    ): String {
        // Castling has its own movetext shape and ignores piece-letter/disambiguation rules.
        if (castleRook != null) {
            val base = if (to.second == 6) "O-O" else "O-O-O"
            return base + checkSuffix
        }

        val isPawn = movingPiece is Pawn
        val pieceLetter = when (movingPiece) {
            is King -> "K"
            is Queen -> "Q"
            is Rook -> "R"
            is Bishop -> "B"
            is Knight -> "N"
            is Pawn -> ""
            else -> ""
        }

        val destination = UciMoveConverter.positionToUciSquare(to)

        // Disambiguation: which other ally pieces of the same type can also legally reach `to`.
        // Pawns never use this; pawn captures encode the source file separately (below).
        val disambiguator = if (isPawn) {
            ""
        } else {
            disambiguator(preMove, pieceIndex, from, to, movingPiece)
        }

        // Pawn captures prefix the source file letter (`exd5`); en-passant looks the same as a
        // regular pawn capture in SAN. Non-pawn captures just use `x` before the destination.
        val captureMark = if (isCapture) {
            if (isPawn) "${fileChar(from.second)}x$destination"
            else "x$destination"
        } else {
            if (isPawn) destination else destination
        }

        val promotionMark = promotion?.let { "=${it.uciChar.uppercaseChar()}" } ?: ""

        return pieceLetter + disambiguator + captureMark + promotionMark + checkSuffix
    }

    /**
     * Minimum disambiguator (file letter, rank digit, or both) per FIDE SAN rules. Returns "" when
     * no other same-type ally piece can legally reach `to`.
     */
    private fun disambiguator(
        preMove: GameUiState,
        pieceIndex: Int,
        from: Pair<Int, Int>,
        to: Pair<Int, Int>,
        movingPiece: Piece,
    ): String {
        val allyPieces: List<Piece>
        val allyPositions: List<Pair<Int, Int>>
        val enemyPieces: List<Piece>
        val enemyPositions: List<Pair<Int, Int>>
        if (preMove.turn == Set.WHITE) {
            allyPieces = preMove.piecesWhite
            allyPositions = preMove.positionsWhite
            enemyPieces = preMove.piecesBlack
            enemyPositions = preMove.positionsBlack
        } else {
            allyPieces = preMove.piecesBlack
            allyPositions = preMove.positionsBlack
            enemyPieces = preMove.piecesWhite
            enemyPositions = preMove.positionsWhite
        }

        val legalMoves = getAllLegalMoves(
            enemyPositions = enemyPositions,
            enemyPieces = enemyPieces,
            allyPositions = allyPositions,
            allyPieces = allyPieces,
            castlingRights = preMove.castlingRights,
            enPassantTarget = preMove.enPassantTarget,
        )

        // Other ally pieces of the same piece-type that can also legally move to `to`.
        val sameTypeCandidates = legalMoves.filter { (target, idx) ->
            target == to &&
                idx != pieceIndex &&
                // Compare by exact piece class (Knight vs Knight, Rook vs Rook, ...) — not just
                // the interface, so e.g. a King is never ambiguous with a Queen.
                allyPieces[idx]::class == movingPiece::class
        }

        if (sameTypeCandidates.isEmpty()) return ""

        val candidateFroms = sameTypeCandidates.map { allyPositions[it.second] }
        val candidateFiles = candidateFroms.map { it.second }.toSet()
        val candidateRanks = candidateFroms.map { it.first }.toSet()

        return when {
            from.second !in candidateFiles -> fileChar(from.second)
            from.first !in candidateRanks -> rankDigit(from.first)
            else -> fileChar(from.second) + rankDigit(from.first)
        }
    }

    private fun fileChar(column: Int): String = ('a' + column).toString()
    private fun rankDigit(row: Int): String = ('0' + (8 - row)).toString()
}
