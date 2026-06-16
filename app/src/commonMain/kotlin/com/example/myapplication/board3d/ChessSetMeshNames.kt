package com.example.myapplication.board3d

/**
 * Maps a [PieceKind] to the glTF node name that holds its template geometry in
 * `files/models/chess.glb`. Names confirmed by the M1 spike (see issue-32-3d-ui-m1
 * "Spike result"): the source set from vkChess exposes one template node per piece
 * type — `king`, `queen`, `rook`, `bishop`, `knight`, `pawn` — and colour is applied
 * via the `white` / `black` materials, not encoded in the node name. So the lookup is
 * colour-independent; [color] is accepted for call-site symmetry and future use.
 */
object ChessSetMeshNames {
    fun getMeshName(kind: PieceKind, color: PieceColor): String = when (kind) {
        PieceKind.KING -> "king"
        PieceKind.QUEEN -> "queen"
        PieceKind.ROOK -> "rook"
        PieceKind.BISHOP -> "bishop"
        PieceKind.KNIGHT -> "knight"
        PieceKind.PAWN -> "pawn"
    }

    /** glTF material name for each colour, per the spike (`white` / `black`). */
    fun getMaterialName(color: PieceColor): String = when (color) {
        PieceColor.WHITE -> "white"
        PieceColor.BLACK -> "black"
    }
}
