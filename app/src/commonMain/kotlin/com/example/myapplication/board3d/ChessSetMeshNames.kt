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

/**
 * Single source of truth for the conventions every Filament 3D backend (wasm, Android, desktop, iOS)
 * shares because they all render the same `chess.glb` asset. Backends that can read Kotlin (wasm via
 * string interpolation into their JS glue, Android, desktop) consume these directly; native code that
 * can't (iOS `FilamentChessRenderer.mm`, Swift) keeps value-identical copies with a "keep in sync"
 * comment pointing back here.
 */
object ChessSetConventions {
    /**
     * chess.glb uses 2-unit squares (board spans +/-8); the game uses 1-unit squares (+/-4), so every
     * node is scaled by this factor — identical to iOS `kModelScale` and Android's `ModelNode` scale.
     */
    const val PIECE_SCALE: Float = 0.5f

    /** A chess board holds at most 32 pieces (promotion replaces a pawn, never adds). */
    const val MAX_PIECES: Int = 32

    /** glTF model asset filename. */
    const val GLB_ASSET: String = "chess.glb"

    /** Prefiltered IBL environment KTX filename. */
    const val IBL_ASSET: String = "papermill_ibl.ktx"

    /** Skybox environment KTX filename. */
    const val SKYBOX_ASSET: String = "papermill_skybox.ktx"

    /** IBL intensity used by the wasm/web Filament backend. */
    const val IBL_INTENSITY: Float = 35000f

    /**
     * Ordered glTF piece node names in [PieceKind] ordinal order. DERIVED from [PieceKind.entries] via
     * [ChessSetMeshNames.getMeshName] so it provably can't drift from the enum ordinals; backends that
     * index by ordinal (the encoded [Board3DScene] wire form) rely on this order.
     */
    val KIND_NAMES: List<String> =
        PieceKind.entries.map { ChessSetMeshNames.getMeshName(it, PieceColor.WHITE) }
}
