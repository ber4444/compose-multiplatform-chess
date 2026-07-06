package com.example.myapplication.board3d

object Board3DSceneMapper {
    /** Parses only the placement + active-color FEN fields. Throws IllegalArgumentException on bad FEN. */
    fun fromFen(fen: String): Board3DScene {
        val parts = fen.split(" ")
        if (parts.isEmpty()) throw IllegalArgumentException("Empty FEN")
        
        val placement = parts[0]
        val sideToMoveRaw = if (parts.size > 1) parts[1] else "w"
        
        val sideToMove = when (sideToMoveRaw) {
            "w" -> PieceColor.WHITE
            "b" -> PieceColor.BLACK
            else -> throw IllegalArgumentException("Invalid side to move: $sideToMoveRaw")
        }

        val pieces = mutableListOf<Piece3DInstance>()
        val rows = placement.split("/")
        if (rows.size != 8) throw IllegalArgumentException("Invalid FEN placement: must have 8 rows")

        for (row in 0 until 8) {
            var col = 0
            for (char in rows[row]) {
                if (char.isDigit()) {
                    col += char.digitToInt()
                } else {
                    val color = if (char.isUpperCase()) PieceColor.WHITE else PieceColor.BLACK
                    val kind = when (char.lowercaseChar()) {
                        'k' -> PieceKind.KING
                        'q' -> PieceKind.QUEEN
                        'r' -> PieceKind.ROOK
                        'b' -> PieceKind.BISHOP
                        'n' -> PieceKind.KNIGHT
                        'p' -> PieceKind.PAWN
                        else -> throw IllegalArgumentException("Invalid piece char: $char")
                    }
                    
                    val square = BoardSquare(row, col)
                    val position = BoardGeometry.squareCenter(square)
                    val rotationY = if (color == PieceColor.BLACK) 180f else 0f

                    pieces.add(
                        Piece3DInstance(
                            kind = kind,
                            color = color,
                            square = square,
                            position = position,
                            rotationYDegrees = rotationY
                        )
                    )
                    col++
                }
            }
            if (col != 8) throw IllegalArgumentException("Invalid FEN placement: row $row has $col columns")
        }

        return Board3DScene(pieces = pieces, sideToMove = sideToMove)
    }
}
