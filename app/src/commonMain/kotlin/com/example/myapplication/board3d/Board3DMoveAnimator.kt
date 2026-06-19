package com.example.myapplication.board3d

/**
 * Shared per-frame interpolation for piece-move transitions, so every backend that drives its own
 * frame loop (desktop Vulkan, Android SceneView) produces the identical vkChess-style arc hop.
 *
 * [baseScene] is the *target* position (the pieces already on their destination squares). Given a
 * [transition] and normalized [progress] in [0,1], this returns a copy of [baseScene] with the
 * moving piece(s) displaced back along their arc, a captured piece re-injected and sinking, and a
 * promoting pawn kept as a pawn until the move completes. At [progress] >= 1 the result equals
 * [baseScene].
 */
object Board3DMoveAnimator {

    /** How far (world units) a captured piece sinks through the board over the move. */
    private const val CAPTURE_SINK_DEPTH = 2.0f

    fun interpolate(
        baseScene: Board3DScene,
        transition: Board3DTransition,
        progress: Float,
    ): Board3DScene {
        val moved = baseScene.pieces.map { piece ->
            when (transition) {
                is Board3DTransition.Move -> when {
                    piece.matches(transition.to, transition.kind, transition.color) ->
                        piece.copy(position = arc(transition.from, transition.to, progress))
                    transition.secondary != null && piece.matches(
                        transition.secondary.to, transition.secondary.kind, transition.secondary.color
                    ) ->
                        piece.copy(position = arc(transition.secondary.from, transition.secondary.to, progress))
                    else -> piece
                }

                is Board3DTransition.Capture ->
                    if (piece.matches(transition.move.to, transition.move.kind, transition.move.color)) {
                        piece.copy(position = arc(transition.move.from, transition.move.to, progress))
                    } else piece

                is Board3DTransition.Promotion ->
                    if (piece.matches(transition.move.to, transition.promotedTo, transition.move.color)) {
                        val pos = arc(transition.move.from, transition.move.to, progress)
                        // Show the pawn until the move finishes, then snap to the promoted piece.
                        if (progress < 1f) piece.copy(kind = PieceKind.PAWN, position = pos) else piece.copy(position = pos)
                    } else piece

                Board3DTransition.Reset -> piece
            }
        }

        // The captured piece is gone from baseScene; re-inject it sinking through the board.
        if (transition is Board3DTransition.Capture && progress < 1f) {
            val pos = BoardGeometry.squareCenter(transition.capturedSquare)
            val sunk = pos.copy(y = pos.y - progress * CAPTURE_SINK_DEPTH)
            return baseScene.copy(
                pieces = moved + Piece3DInstance(
                    kind = transition.capturedKind,
                    color = transition.capturedColor,
                    square = transition.capturedSquare,
                    position = sunk,
                    rotationYDegrees = if (transition.capturedColor == PieceColor.WHITE) 0f else 180f,
                )
            )
        }

        return baseScene.copy(pieces = moved)
    }

    private fun arc(from: BoardSquare, to: BoardSquare, progress: Float): Vec3 =
        catmullRomArc(BoardGeometry.squareCenter(from), BoardGeometry.squareCenter(to), progress)

    private fun Piece3DInstance.matches(square: BoardSquare, kind: PieceKind, color: PieceColor) =
        this.square == square && this.kind == kind && this.color == color
}
