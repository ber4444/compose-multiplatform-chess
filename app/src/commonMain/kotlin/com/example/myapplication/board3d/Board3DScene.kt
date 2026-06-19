package com.example.myapplication.board3d

enum class PieceKind { KING, QUEEN, ROOK, BISHOP, KNIGHT, PAWN }
enum class PieceColor { WHITE, BLACK }

/** Same convention as the 2D app: row 0 = rank 8 (black's back rank), col 0 = file a. */
data class BoardSquare(val row: Int, val col: Int)

data class Piece3DInstance(
    val kind: PieceKind,
    val color: PieceColor,
    val square: BoardSquare,
    val position: Vec3,            // world-space center from BoardGeometry.squareCenter
    val rotationYDegrees: Float,   // 0f for white, 180f for black (knights face each other)
)

data class Board3DScene(
    val pieces: List<Piece3DInstance>,
    val sideToMove: PieceColor,
    val selectedSquare: BoardSquare? = null,  // unused until M5 highlight
)

/**
 * Compact wire form for the three.js backend (web/iOS): each piece as
 * `kindOrdinal,colorOrdinal,x,y,z,rotationYDegrees`, pieces joined by `;`. The JS `chess3d.setScene`
 * reconciles a fixed node pool against this list every frame. Contains only digits, `.`, `-`, `,`
 * and `;`, so it's safe to drop straight into a single-quoted `evaluateJavaScript` string (iOS) or a
 * `@JsFun` string arg (wasm). Built with one StringBuilder since it's serialised per animation frame.
 */
fun Board3DScene.encode(): String {
    val sb = StringBuilder()
    for ((i, p) in pieces.withIndex()) {
        if (i > 0) sb.append(';')
        sb.append(p.kind.ordinal).append(',')
            .append(if (p.color == PieceColor.WHITE) 0 else 1).append(',')
            .append(p.position.x).append(',')
            .append(p.position.y).append(',')
            .append(p.position.z).append(',')
            .append(p.rotationYDegrees)
    }
    return sb.toString()
}
