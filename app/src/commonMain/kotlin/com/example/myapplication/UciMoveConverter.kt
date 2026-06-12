package com.example.myapplication

/**
 * Converts between UCI (Universal Chess Interface) move notation and
 * the app's internal coordinate system.
 *
 * UCI notation uses algebraic coordinates like "e2e4" (from-square to-square).
 * The app uses Pair<Int, Int> where first = row (0-7), second = column (0-7).
 *
 * Mapping:
 *   UCI file a-h  →  app column 0-7
 *   UCI rank 1-8  →  app row 7-0 (inverted)
 */
object UciMoveConverter {

    /**
     * Convert a UCI square string (e.g., "e2") to app coordinates.
     *
     * @param square A two-character UCI square string (file + rank)
     * @return App coordinates as Pair(row, column)
     * @throws IllegalArgumentException if the square string is invalid
     */
    fun uciSquareToPosition(square: String): Pair<Int, Int> {
        require(square.length == 2) { "UCI square must be exactly 2 characters: $square" }
        val file = square[0]
        val rank = square[1]
        require(file in 'a'..'h') { "Invalid UCI file: $file" }
        require(rank in '1'..'8') { "Invalid UCI rank: $rank" }

        val column = file - 'a'         // 'a'->0, 'b'->1, ..., 'h'->7
        val row = '8' - rank            // '1'->7, '2'->6, ..., '8'->0
        return Pair(row, column)
    }

    /**
     * Convert app coordinates to a UCI square string.
     *
     * @param position App coordinates as Pair(row, column)
     * @return A two-character UCI square string (e.g., "e2")
     */
    fun positionToUciSquare(position: Pair<Int, Int>): String {
        val file = 'a' + position.second    // 0->'a', 1->'b', ..., 7->'h'
        val rank = 8 - position.first       // 0->8, 1->7, ..., 7->1
        return "$file$rank"
    }

    /**
     * Parse a full UCI move string (e.g., "e2e4") into from/to positions.
     *
     * @param uciMove A UCI move string (4 or 5 characters, 5 for promotion)
     * @return Pair of (from position, to position) in app coordinates
     * @throws IllegalArgumentException if the move string is invalid
     */
    fun parseUciMove(uciMove: String): Pair<Pair<Int, Int>, Pair<Int, Int>> {
        require(uciMove.length in 4..5) { "UCI move must be 4-5 characters: $uciMove" }
        val from = uciSquareToPosition(uciMove.substring(0, 2))
        val to = uciSquareToPosition(uciMove.substring(2, 4))
        return Pair(from, to)
    }

    /**
     * Convert a UCI move to the app's move format (target position + piece index).
     *
     * @param uciMove A UCI move string (e.g., "e2e4")
     * @param allyPositions The current positions of the moving side's pieces
     * @return Pair of (new position, piece index) or null if the piece was not found
     */
    fun uciMoveToAppMove(
        uciMove: String,
        allyPositions: List<Pair<Int, Int>>
    ): SelectedMove? {
        val (from, to) = parseUciMove(uciMove)
        val pieceIndex = allyPositions.indexOf(from)
        if (pieceIndex == -1) return null
        
        val promotion = if (uciMove.length == 5) {
            PromotionType.fromUciChar(uciMove[4]) ?: return null  // malformed → caller falls back
        } else null
        return SelectedMove(to, pieceIndex, promotion)
    }

    /**
     * Build a UCI move string from app coordinates.
     *
     * @param from Source position in app coordinates
     * @param to Target position in app coordinates
     * @return A UCI move string (e.g., "e2e4")
     */
    fun appMoveToUci(from: Pair<Int, Int>, to: Pair<Int, Int>): String {
        return positionToUciSquare(from) + positionToUciSquare(to)
    }
}
