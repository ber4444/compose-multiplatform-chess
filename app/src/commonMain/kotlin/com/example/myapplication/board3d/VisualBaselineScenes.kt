package com.example.myapplication.board3d

/**
 * Compact set of labeled canonical scenes used to compare 3D rendering quality across platforms.
 *
 * Each scene is expressed purely in terms of the existing [Board3DScene] / [CameraParams]
 * abstractions (FEN + camera + size), so the *same* definition can be rendered by every backend
 * (Android Filament, iOS SceneKit, desktop WebGPU, wasm WebGPU) without any platform-specific
 * scene logic leaking into shared code.
 *
 * Consumed by:
 *  - Desktop offscreen baseline dump (`desktopTest/.../VisualBaselineDumpTest.kt`).
 *  - iOS headless baseline dump (`iosSimulatorArm64Test/.../IosBoard3DSnapshotTest.kt`).
 *  - Web `@JsExport getBaselineScenes()` interop (`wasmJsMain/.../BaselineScenesInterop.kt`).
 *  - Android dev-menu action (instrumented capture flow).
 *
 * Filenames produced by capture flows follow the convention
 * `scene-<id>-<platform>.png` (e.g. `scene-start-high-lighting-android.png`) so cross-platform
 * eyeballing/diffing is stable across captures.
 */
data class VisualBaselineScene(
    val id: String,
    val fen: String,
    val camera: CameraParams,
    val widthPx: Int,
    val heightPx: Int,
    /** Free-form label used in docs/lists; not parsed by tooling. */
    val label: String,
)

object VisualBaselineScenes {

    /**
     * Default fixed resolution for cross-platform still captures. Square matches the shipped
     * `GameScreen` board layout (`fillMaxWidth().aspectRatio(1f)`) so the captured framing matches
     * what users actually see, and 1024 px is large enough to expose aliasing without making
     * baseline PNGs unwieldy to commit/diff.
     */
    const val DEFAULT_WIDTH_PX: Int = 1024
    const val DEFAULT_HEIGHT_PX: Int = 1024

    /**
     * Start position from the canonical White view. Exercises the highest-light scene: every piece
     * is on its home square, the bright papermill IBL hits all six piece kinds at once, and the
     * frame's engraved rim is in full silhouette. The single most useful regression image.
     */
    val START_POSITION_HIGH_LIGHTING: VisualBaselineScene = VisualBaselineScene(
        id = "start-high-lighting",
        fen = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1",
        camera = OrbitCameraController.DEFAULT_WHITE_VIEW,
        widthPx = DEFAULT_WIDTH_PX,
        heightPx = DEFAULT_HEIGHT_PX,
        label = "Start position, high lighting (White view)",
    )

    /**
     * Asymmetric midgame (Italian Game structure). Open center diagonals, both kings castled-kingside
     * implicitly via the piece placement, pieces at varied heights and facing different directions —
     * chosen so the full shadow range, contact darkening, and reflection variety are visible at once.
     */
    val MIDGAME_SHADOWS: VisualBaselineScene = VisualBaselineScene(
        id = "midgame-shadows",
        fen = "r1bqkb1r/pppp1ppp/2n2n2/4p3/2B1P3/5N2/PPPP1PPP/RNBQK2R w KQkq - 4 4",
        camera = OrbitCameraController.DEFAULT_WHITE_VIEW,
        widthPx = DEFAULT_WIDTH_PX,
        heightPx = DEFAULT_HEIGHT_PX,
        label = "Midgame shadows (Italian opening)",
    )

    /**
     * A single White king on e4 in dramatic close-up. No other geometry competes for attention, so
     * this is the scene that exposes edge aliasing, normal-map detail, and material roughness most
     * starkly — the canonical "is this renderer good enough?" image.
     */
    val ENDGAME_SINGLE_PIECE_CLOSEUP: VisualBaselineScene = VisualBaselineScene(
        id = "endgame-single-piece-closeup",
        fen = "8/8/8/8/4K3/8/8/8 w - - 0 1",
        camera = closeupOnKing(file = 4, rank = 4), // e4
        widthPx = DEFAULT_WIDTH_PX,
        heightPx = DEFAULT_HEIGHT_PX,
        label = "Endgame single-piece closeup (White king e4)",
    )

    /** All baseline scenes in stable order; capture flows iterate this. */
    val ALL: List<VisualBaselineScene> = listOf(
        START_POSITION_HIGH_LIGHTING,
        MIDGAME_SHADOWS,
        ENDGAME_SINGLE_PIECE_CLOSEUP,
    )

    /** Stable filename for a scene on a given platform, e.g. `scene-start-high-lighting-android`. */
    fun baseName(scene: VisualBaselineScene, platform: String): String = "scene-${scene.id}-$platform"

    /**
     * Tight orbit around the king square. Pulls the camera in to ~5 units (vs. the default 12) along
     * the same direction the default White view looks, so framing stays consistent across captures
     * but the piece fills more of the frame. Square convention: row 0 = rank 8, col 0 = file a.
     */
    private fun closeupOnKing(file: Int, rank: Int): CameraParams {
        val target = BoardGeometry.squareCenter(BoardSquare(row = rank, col = file))
        val base = OrbitCameraController.DEFAULT_WHITE_VIEW
        val dir = (base.position - base.target).normalized()
        val closer = target + (dir * 5f)
        return base.copy(position = closer, target = target)
    }
}
