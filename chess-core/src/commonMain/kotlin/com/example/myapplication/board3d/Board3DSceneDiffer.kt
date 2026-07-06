package com.example.myapplication.board3d

sealed interface Board3DTransition {
    data class Move(
        val from: BoardSquare,
        val to: BoardSquare,
        val kind: PieceKind,
        val color: PieceColor,
        val secondary: Move? = null,
    ) : Board3DTransition

    data class Capture(
        val move: Move,
        val capturedSquare: BoardSquare,
        val capturedKind: PieceKind,
        val capturedColor: PieceColor,
    ) : Board3DTransition

    data class Promotion(
        val move: Move,
        val promotedTo: PieceKind,
    ) : Board3DTransition

    data object Reset : Board3DTransition
}

object Board3DSceneDiffer {
    /**
     * Heuristically computes the transition that occurred between [previous] and [next].
     */
    fun diff(previous: Board3DScene, next: Board3DScene): Board3DTransition {
        // If it's the exact same state (e.g. just selection change), or if one is empty, return Reset
        if (previous.pieces == next.pieces) return Board3DTransition.Reset

        // Find pieces that remained at the same square with the same kind and color
        val nextUnmoved = mutableSetOf<Piece3DInstance>()
        val prevUnmoved = mutableSetOf<Piece3DInstance>()

        for (p in previous.pieces) {
            val match = next.pieces.find { it.square == p.square && it.kind == p.kind && it.color == p.color }
            if (match != null) {
                prevUnmoved.add(p)
                nextUnmoved.add(match)
            }
        }

        val prevDiff = previous.pieces.filter { it !in prevUnmoved }
        val nextDiff = next.pieces.filter { it !in nextUnmoved }

        val activeColor = previous.sideToMove

        // Filter moving pieces by the active color
        val prevActive = prevDiff.filter { it.color == activeColor }
        val nextActive = nextDiff.filter { it.color == activeColor }
        val prevEnemy = prevDiff.filter { it.color != activeColor }
        val nextEnemy = nextDiff.filter { it.color != activeColor }

        // If active pieces don't map neatly, we can't heuristically determine the move
        if (prevActive.isEmpty() || nextActive.isEmpty()) return Board3DTransition.Reset

        if (prevActive.size == 1 && nextActive.size == 1) {
            val from = prevActive.first()
            val to = nextActive.first()

            val move = Board3DTransition.Move(from.square, to.square, from.kind, from.color)

            // Promotion check
            if (from.kind == PieceKind.PAWN && to.kind != PieceKind.PAWN) {
                return Board3DTransition.Promotion(move, to.kind)
            }

            // Capture check
            if (prevEnemy.size == 1 && nextEnemy.isEmpty()) {
                val captured = prevEnemy.first()
                return Board3DTransition.Capture(move, captured.square, captured.kind, captured.color)
            }

            // Normal Move
            if (prevEnemy.isEmpty() && nextEnemy.isEmpty() && from.kind == to.kind) {
                return move
            }
        }

        // Castling check: King and Rook move
        if (prevActive.size == 2 && nextActive.size == 2 && prevEnemy.isEmpty() && nextEnemy.isEmpty()) {
            val prevKing = prevActive.find { it.kind == PieceKind.KING }
            val nextKing = nextActive.find { it.kind == PieceKind.KING }
            val prevRook = prevActive.find { it.kind == PieceKind.ROOK }
            val nextRook = nextActive.find { it.kind == PieceKind.ROOK }

            if (prevKing != null && nextKing != null && prevRook != null && nextRook != null) {
                val move = Board3DTransition.Move(
                    prevKing.square, nextKing.square, prevKing.kind, prevKing.color,
                    secondary = Board3DTransition.Move(prevRook.square, nextRook.square, prevRook.kind, prevRook.color)
                )
                return move
            }
        }

        return Board3DTransition.Reset
    }
}
