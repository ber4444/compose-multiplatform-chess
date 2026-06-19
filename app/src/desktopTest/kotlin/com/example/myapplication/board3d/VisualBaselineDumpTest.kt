package com.example.myapplication.board3d

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.junit.Assume
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Phase A baseline capture — renders each [VisualBaselineScene] offscreen at a fixed resolution via
 * the production `DesktopWgpuChessRenderer` and writes a PNG per scene under
 * `app/build/baseline/desktop/`. macOS-only (the desktop renderer needs Metal/CAMetalLayer);
 * silently skipped on Linux CI, exactly like `Wgpu4kFrameDumpTest`.
 *
 * Run: `./gradlew :app:desktopTest --tests "*VisualBaselineDumpTest*"`
 * Output: `app/build/baseline/desktop/scene-<id>-desktop.png` (look for `BASELINE_DESKTOP_DIR=`).
 *
 * The renderer's render loop is free-running (~60 fps); per scene we apply (fen, camera) and give
 * the geometry rebuild + a few frames time to land, then snapshot the latest bitmap from the frame
 * callback. There is no per-frame deterministic sync because the wgpu loop continuously pushes
 * frames; stabilization-by-delay mirrors what a human-driven eyeball flow would do.
 *
 * Phase D.3 adds [compareFrameTimingsAcrossPresets] — a sibling test that drives the renderer
 * through a fixed-length orbit under both `DEFAULT` and `HIGH_QUALITY` presets, measures the mean
 * per-frame wall-clock interval (the frame callback fires once per render-loop iteration), and
 * prints a comparison line for the docs. It does NOT assert a hard FPS target because absolute
 * numbers vary by host GPU; the relative ratio between presets is the durable signal.
 */
class VisualBaselineDumpTest {

    @Test
    fun renderAllBaselineScenes() = runBlocking {
        Assume.assumeTrue("macOS-only (Metal/CAMetalLayer)", System.getProperty("os.name").contains("Mac"))
        val glb = listOf(
            File("src/commonMain/composeResources/files/models/chess.glb"),
            File("app/src/commonMain/composeResources/files/models/chess.glb"),
        ).firstOrNull { it.exists() }?.readBytes() ?: error("chess.glb not found")

        val outDir = File("app/build/baseline/desktop").apply { mkdirs() }
        val w = VisualBaselineScenes.DEFAULT_WIDTH_PX
        val h = VisualBaselineScenes.DEFAULT_HEIGHT_PX

        val latest = AtomicReference<ImageBitmap?>(null)
        val renderer = DesktopWgpuChessRenderer(glb)
        val surface = ImageBitmapChess3DSurface(w, h) { bmp -> latest.set(bmp) }
        try {
            renderer.attach(surface)
            // Wait for the very first frame so we know the render loop is alive before driving it.
            withTimeout(30_000) { while (latest.get() == null) delay(50) }

            for (scene in VisualBaselineScenes.ALL) {
                renderer.updatePosition(scene.fen)
                renderer.onUserInteraction(Board3DInput.SetCamera(scene.camera))
                // All baseline scenes are square 1024×1024; no surface re-attach needed.
                renderer.onUserInteraction(Board3DInput.Resize(scene.widthPx, scene.heightPx))
                // Let the async geometry rebuild (scope.launch in updatePosition) land and a few
                // frames render at the new scene. 500 ms ≈ 8 frames at 60 fps.
                delay(500)
                val bmp = latest.get() ?: error("no frame produced for scene ${scene.id}")
                val png = Image.makeFromBitmap(bmp.asSkiaBitmap())
                    .encodeToData(EncodedImageFormat.PNG) ?: error("PNG encode failed for ${scene.id}")
                val out = File(outDir, "${VisualBaselineScenes.baseName(scene, "desktop")}.png")
                out.writeBytes(png.bytes)
                println("BASELINE_DESKTOP_${scene.id}=${out.absolutePath} bytes=${png.bytes.size}")
                assertTrue(png.bytes.size > 10_000, "scene ${scene.id} PNG suspiciously small")
            }
            println("BASELINE_DESKTOP_DIR=${outDir.absolutePath}")
        } finally {
            renderer.detach()
            renderer.dispose()
        }
    }

    /**
     * Phase D.3 — A/B frame-timing comparison between [DesktopRendererQualityPreset.DEFAULT] and
     * [DesktopRendererQualityPreset.HIGH_QUALITY] (4× MSAA).
     *
     * Drives the renderer through the closeup baseline scene while slowly orbiting the camera,
     * counts frames delivered to the surface callback over a fixed wall-clock window per preset,
     * and prints `PRESET_FRAME_TIMING_<preset>=<frames> frames / <ms> ms (≈ <fps> fps)`. The
     * assertion is only that HIGH_QUALITY does not collapse to <15 fps (i.e. <25% of typical
     * DEFAULT) — a regression that severe indicates the MSAA path is misconfigured, not just slower.
     * The relative ratio between presets is recorded in `docs/graphics/desktop-renderer-notes.md`.
     *
     * Skipped on non-Mac hosts (no Metal).
     */
    @Test
    fun compareFrameTimingsAcrossPresets() = runBlocking {
        Assume.assumeTrue("macOS-only (Metal/CAMetalLayer)", System.getProperty("os.name").contains("Mac"))
        val glb = listOf(
            File("src/commonMain/composeResources/files/models/chess.glb"),
            File("app/src/commonMain/composeResources/files/models/chess.glb"),
        ).firstOrNull { it.exists() }?.readBytes() ?: error("chess.glb not found")

        val scene = VisualBaselineScenes.ENDGAME_SINGLE_PIECE_CLOSEUP
        val w = scene.widthPx
        val h = scene.heightPx

        // Warm-up + measurement windows. 2 s warm-up lets the geometry rebuild + first MSAA-only
        // allocations settle; 4 s measurement is long enough to absorb a couple of vsync hiccups
        // without making the test annoyingly slow on every iteration.
        val warmupMs = 2_000L
        val measureMs = 4_000L

        val results = LinkedHashMap<DesktopRendererQualityPreset, String>()
        for (preset in listOf(DesktopRendererQualityPreset.DEFAULT, DesktopRendererQualityPreset.HIGH_QUALITY)) {
            var frameCount = 0L
            val latest = AtomicReference<ImageBitmap?>(null)
            val renderer = DesktopWgpuChessRenderer(glb, preset)
            // Surface callback: track frame count for FPS AND store the latest bitmap so the
            // first-frame wait can complete (the callback fires per render-loop iteration).
            val surface = ImageBitmapChess3DSurface(w, h) { bmp ->
                latest.set(bmp)
                frameCount++
            }
            try {
                renderer.attach(surface)
                renderer.updatePosition(scene.fen)
                renderer.onUserInteraction(Board3DInput.SetCamera(scene.camera))
                renderer.onUserInteraction(Board3DInput.Resize(w, h))
                // Wait for first frame so warm-up window starts at a known-good state.
                withTimeout(30_000) { while (latest.get() == null) delay(50) }

                // Warm-up: drive the renderer but discard timings.
                val warmupEnd = System.currentTimeMillis() + warmupMs
                var orbitWarmup = 0.0f
                while (System.currentTimeMillis() < warmupEnd) {
                    orbitWarmup += 0.01f
                    renderer.onUserInteraction(
                        Board3DInput.SetCamera(scene.camera.orbit(orbitWarmup))
                    )
                    delay(16) // ~60 fps tick; the renderer is free-running so this just paces the camera
                }

                // Measurement window.
                frameCount = 0L
                val measureStart = System.currentTimeMillis()
                val measureEnd = measureStart + measureMs
                var orbit = 0.0f
                while (System.currentTimeMillis() < measureEnd) {
                    orbit += 0.01f
                    renderer.onUserInteraction(
                        Board3DInput.SetCamera(scene.camera.orbit(orbit))
                    )
                    delay(16)
                }
                val elapsed = System.currentTimeMillis() - measureStart
                val fps = (frameCount.toDouble() / elapsed.toDouble()) * 1000.0
                val line = "$frameCount frames / ${elapsed} ms (≈ ${"%.1f".format(fps)} fps)"
                results[preset] = line
                println("PRESET_FRAME_TIMING_${preset.name}=$line")
            } finally {
                renderer.detach()
                renderer.dispose()
            }
        }

        // Hard assertion: HIGH_QUALITY must not collapse to <15 fps. That floor is well below the
        // slowest reasonable MSAA regression on the slowest target Mac (an M1 at 1024×1024 with 4×
        // MSAA still clears 30 fps). A result below this indicates the MSAA path is misconfigured
        // (e.g. failing to resolve, allocating per-frame) rather than just slower.
        val hqLine = results.getValue(DesktopRendererQualityPreset.HIGH_QUALITY)
        val hqFps = hqLine.substringAfter("≈ ").substringBefore(" fps").toDouble()
        assertTrue(hqFps >= 15.0, "HIGH_QUALITY collapsed to $hqFps fps — MSAA path likely broken: $hqLine")

        println("PRESET_FRAME_TIMING_SUMMARY=" + results.entries.joinToString("; ") { (p, l) -> "${p.name}=$l" })
    }
}

/**
 * Slowly orbits a copy of this camera around its target by [radians] (positive = clockwise viewed
 * from above). Used by the frame-timing test to keep the renderer producing fresh frames without
 * introducing scene-loading pauses between iterations.
 */
private fun CameraParams.orbit(radians: Float): CameraParams {
    val dx = position.x - target.x
    val dz = position.z - target.z
    val cos = kotlin.math.cos(radians)
    val sin = kotlin.math.sin(radians)
    return copy(
        position = Vec3(
            target.x + (dx * cos - dz * sin),
            position.y,
            target.z + (dx * sin + dz * cos),
        )
    )
}
