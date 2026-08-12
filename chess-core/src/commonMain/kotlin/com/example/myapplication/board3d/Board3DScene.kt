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

/**
 * What a highlighted square is *saying*, which every backend turns into a colour.
 *
 * [NEUTRAL] is the tone the asset was authored in (translucent blue) and the only one that means
 * "no judgement": Hint squares and Explain-mode square taps carry it, because neither is a verdict
 * on a move the player made. The other three come from `MoveClass` and exist so the coach can stop
 * *writing* "Good — a3" and show it instead.
 *
 * Ordinals are the wire form (see [encode]) and are copied into the native backends, so **append
 * only** — reordering silently repaints every board.
 */
enum class HighlightTone { NEUTRAL, GOOD, INACCURATE, BAD }

/** A square to highlight and the tone to draw it in. */
data class HighlightedSquare(
    val square: BoardSquare,
    val tone: HighlightTone = HighlightTone.NEUTRAL,
)

data class Board3DScene(
    val pieces: List<Piece3DInstance>,
    val sideToMove: PieceColor,
    val selectedSquare: BoardSquare? = null,  // unused until M5 highlight
    val highlightedSquares: List<HighlightedSquare> = emptyList(),
)

/**
 * Compact wire form for Filament-backed renderers: each piece as
 * `kindOrdinal,colorOrdinal,x,y,z,rotationYDegrees`, pieces joined by `;`. Native and JS peers
 * reconcile a fixed instance pool against this list every frame. Contains only digits, `.`, `-`,
 * `,`, `;`, and `|`, so it's safe for JS interop and native bridge string calls. Built with one
 * StringBuilder since it's serialised per animation frame.
 * Highlighted squares are appended after a `|` character as `x,y,z,tone;x,y,z,tone...` using the
 * square center and the [HighlightTone] ordinal.
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
    if (highlightedSquares.isNotEmpty()) {
        sb.append('|')
        for ((i, h) in highlightedSquares.withIndex()) {
            if (i > 0) sb.append(';')
            val center = BoardGeometry.squareCenter(h.square)
            sb.append(center.x).append(',').append(center.y).append(',').append(center.z)
                .append(',').append(h.tone.ordinal)
        }
    }
    return sb.toString()
}
