package com.example.myapplication.board3d

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Phase A.2 Android baseline entry point — validates the canonical [VisualBaselineScene] set is
 * processable by the Android renderer pipeline (FEN parses, sizes are square, cameras sane), and
 * documents the recipe for capturing PNG references.
 *
 * The actual PixelCopy capture path is implemented in [captureToPngFile]; see its KDoc for the
 * runtime requirements (laid-out SurfaceView, Filament frame produced). That extension is wired
 * into a follow-up `renderSnapshotPng(fen, w, h, camera)` headless path on
 * `AndroidSceneViewChessRenderer` once the Android renderer grows that capability (deferred — it
 * touches the frozen Android actual). Until then, the working capture flow is the same shape as
 * `tools/ios_3d_screenshot.sh`:
 *
 *   1. Launch the app on a device/emulator with the 3D board visible.
 *   2. For each [VisualBaselineScene]:
 *      - Apply the scene FEN via a dev hook (e.g. adb am broadcast an intent extra), OR
 *        open the app on a game whose FEN matches the scene.
 *      - `adb exec-out screencap -p > docs/assets/baselines/android/scene-<id>-android.png`
 *
 * Android is the gold-standard reference (Decision 1 in `docs/plans/graphics-quality.md`); the
 * curated PNGs committed under `docs/assets/baselines/android/` are the visual reference every
 * other platform's captures are compared against.
 */
class AndroidBaselineCaptureTest {

    @Test
    fun allBaselineScenesAreAndroidProcessable() {
        for (scene in VisualBaselineScenes.ALL) {
            // AndroidSceneViewChessRenderer consumes FEN through Chess3DBoardRenderer.updatePosition,
            // which routes through Board3DSceneMapper — so the same validation as the common test
            // applies, but we re-run it here because androidDeviceTest does not see commonTest.
            val parsed = Board3DSceneMapper.fromFen(scene.fen)
            assertTrue(parsed.pieces.isNotEmpty(), "${scene.id} produced no pieces")

            // Capture surfaces are square (matches GameScreen's fillMaxWidth().aspectRatio(1f)).
            assertTrue(scene.widthPx == scene.heightPx, "${scene.id} must be square for android capture")

            // Camera direction is well-formed (position != target so we have a real lookAt).
            val dir = scene.camera.position - scene.camera.target
            assertTrue(dir.length() > 0.01f, "${scene.id} camera has degenerate direction")
        }
    }
}
