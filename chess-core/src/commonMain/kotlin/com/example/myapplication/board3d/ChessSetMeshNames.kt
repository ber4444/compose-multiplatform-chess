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

    /**
     * glTF material name for each colour, per the spike (`white` / `black`). Every backend resolves
     * a piece's material by looking this name up in its `FilamentInstance`'s material-instance list
     * and rebinding the primitive to it.
     *
     * > [!CAUTION]
     * > **`black` is kept alive by one hidden quad, and nothing else in the asset references it.**
     * > All six piece templates in `chess.glb` are authored to `white`; the *only* primitive bound to
     * > `black` is the hidden `Plane` node (mesh `Plane.064`). gltfio instantiates a `MaterialInstance`
     * > only for materials some primitive actually uses, so if `Plane` stops referencing `black` the
     * > instance is never created, the lookup here returns null, and **every black piece silently
     * > falls back to its authored `white` material** — the whole set renders silvery. There is no
     * > crash and no log line.
     * >
     * > This was hit for real: the B16 highlight quad was first built by repointing `Plane` at a new
     * > translucent material, which turned all the black pieces white on every backend at once. The
     * > highlight now lives on its own `Highlight` node so `Plane` can keep carrying `black`.
     * >
     * > So: do not repoint, delete, or merge the `Plane` node, and do not "clean up" `black` as an
     * > apparently-unused material. If you need to touch it, first give the piece templates a real
     * > per-colour material binding so the runtime lookup no longer depends on a hidden quad.
     */
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

    /**
     * Highlight-quad pool size, mirroring [MAX_PIECES] for the coach's cited squares.
     *
     * Deliberately small: every slot is a full instance of `chess.glb` (72 nodes) on all four
     * Filament backends, created eagerly at init, so each one costs ~72 entities and material
     * instances to draw a single quad. The coach cites a move, i.e. a from/to pair, so 4 leaves
     * headroom without paying for 10. Callers must cap their list to this — the backends silently
     * drop the overflow.
     */
    const val MAX_HIGHLIGHTS: Int = 4

    /**
     * glTF node name for each [HighlightTone], in ordinal order. One quad per tone, same geometry,
     * differing only in material.
     *
     * **The tone is chosen in the asset, not at runtime, and that is deliberate.** The obvious
     * alternative — one quad whose `MaterialInstance` gets recoloured per frame — founders on what
     * actually makes this quad blue: the colour is the material's `emissiveFactor`, *not*
     * `baseColorFactor` (which is white), and the see-through look is `KHR_materials_transmission`,
     * *not* alpha blending (`alphaMode` is OPAQUE). Comments across this repo asserted the opposite
     * for both, so anyone "fixing" the colour by setting `baseColorFactor` gets a silent no-op. The
     * emissive parameter's ubershader name is also Filament-version-dependent, and four backends
     * pin different Filament versions. Selecting a node by name is something all four already do.
     *
     * Regenerate the added quads with `tools/add_highlight_tones_to_glb.py` (idempotent). Every
     * backend must hide **all** of these on its board and piece instances — leaving one out parks a
     * stray quad at the origin.
     */
    val HIGHLIGHT_NODE_NAMES: List<String> =
        listOf("Highlight", "HighlightGood", "HighlightInaccurate", "HighlightBad")

    /** Node name for a wire tone ordinal, falling back to [HighlightTone.NEUTRAL]'s quad. */
    fun highlightNodeName(toneOrdinal: Int): String =
        HIGHLIGHT_NODE_NAMES.getOrElse(toneOrdinal) { HIGHLIGHT_NODE_NAMES[0] }

    /** glTF model asset filename. */
    const val GLB_ASSET: String = "chess.glb"

    /** Prefiltered IBL environment KTX filename. */
    const val IBL_ASSET: String = "papermill_ibl.ktx"

    /** Skybox environment KTX filename. */
    const val SKYBOX_ASSET: String = "papermill_skybox.ktx"

    /** Heavily blurred skybox environment KTX filename for Web and iOS. */
    const val SKYBOX_ASSET_BLURRED: String = "papermill_skybox_blurred.ktx"

    /** IBL intensity used by the wasm/web Filament backend. */
    const val IBL_INTENSITY: Float = 30000f

    /**
     * Ordered glTF piece node names in [PieceKind] ordinal order. DERIVED from [PieceKind.entries] via
     * [ChessSetMeshNames.getMeshName] so it provably can't drift from the enum ordinals; backends that
     * index by ordinal (the encoded [Board3DScene] wire form) rely on this order.
     */
    val KIND_NAMES: List<String> =
        PieceKind.entries.map { ChessSetMeshNames.getMeshName(it, PieceColor.WHITE) }
}
