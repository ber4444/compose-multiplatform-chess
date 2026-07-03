package com.example.myapplication.board3d

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.graphics.toPixelMap
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.junit.Assume
import org.junit.Test
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.assertTrue

/**
 * Cross-platform parity capture for the desktop Filament backend. Renders each canonical
 * [VisualBaselineScene] headlessly through the production [DesktopFilamentChessRenderer] and writes
 * one PNG per scene under `app/build/baseline/desktop/`, following the shared
 * `scene-<id>-desktop.png` naming so the output diffs cleanly against the Android gold reference in
 * `docs/assets/baselines/android/` and the other platforms' captures (see
 * [VisualBaselineScenes] and `docs/plans/graphics-quality.md`).
 *
 * This is the desktop arm of the same parity work PR #60 established for Android/iOS/web; it
 * replaces the removed wgpu4k-era dump now that desktop renders with Filament.
 *
 * Gated by `-Dchess3d.smoke=true` (it needs a real GPU backend, exactly like
 * [DesktopRendererSmokeTest]) and skips gracefully if the backend is unavailable, so it never runs
 * in the GPU-less CI `:app:check`.
 *
 * Run: `./gradlew :app:desktopTest --tests "*VisualBaselineDumpTest*" -Dchess3d.smoke=true`
 * Output: `app/build/baseline/desktop/scene-<id>-desktop.png` (look for `BASELINE_DESKTOP_DIR=`).
 */
class VisualBaselineDumpTest {

    @Test
    fun renderAllBaselineScenes() {
        Assume.assumeTrue(System.getProperty("chess3d.smoke") == "true")
        val glb = File("src/commonMain/composeResources/files/models/chess.glb")
        val ibl = File("src/commonMain/composeResources/files/env/papermill_ibl.ktx")
        val skybox = File("src/commonMain/composeResources/files/env/papermill_skybox_blurred.ktx")
        Assume.assumeTrue("chess.glb present", glb.exists())
        Assume.assumeTrue("papermill_ibl.ktx present", ibl.exists())
        Assume.assumeTrue("papermill_skybox_blurred.ktx present", skybox.exists())

        val renderer = try {
            DesktopFilamentChessRenderer(
                glb = glb.readBytes(),
                ibl = ibl.readBytes(),
                skybox = skybox.readBytes(),
            )
        } catch (t: Throwable) {
            Assume.assumeNoException("Desktop Filament backend unavailable", t); return
        }

        val outDir = File("build/baseline/desktop").apply { mkdirs() }
        val frames = LinkedBlockingQueue<ImageBitmap>()

        // The desktop renderer is on-demand: each camera/position change pushes one frame. Per scene
        // we apply (camera, size, fen) then take the last frame after the queue goes quiet, so we
        // snapshot the fully-applied scene rather than an intermediate frame.
        fun settledFrame(id: String): ImageBitmap {
            var last = frames.poll(30, TimeUnit.SECONDS) ?: error("no frame produced for scene $id")
            while (true) last = frames.poll(500, TimeUnit.MILLISECONDS) ?: break
            return last
        }

        try {
            val first = VisualBaselineScenes.ALL.first()
            renderer.attach(ImageBitmapChess3DSurface(first.widthPx, first.heightPx) { frames.offer(it) })

            for (scene in VisualBaselineScenes.ALL) {
                renderer.onUserInteraction(Board3DInput.SetCamera(scene.camera))
                renderer.onUserInteraction(Board3DInput.Resize(scene.widthPx, scene.heightPx))
                renderer.updatePosition(scene.fen)

                val frame = settledFrame(scene.id)
                assertRealRender(frame, scene.id)

                val png = Image.makeFromBitmap(frame.asSkiaBitmap())
                    .encodeToData(EncodedImageFormat.PNG) ?: error("PNG encode failed for ${scene.id}")
                val out = File(outDir, "${VisualBaselineScenes.baseName(scene, "desktop")}.png")
                out.writeBytes(png.bytes)
                println("BASELINE_DESKTOP_${scene.id}=${out.absolutePath} bytes=${png.bytes.size}")
            }
            println("BASELINE_DESKTOP_DIR=${outDir.absolutePath}")
        } finally {
            renderer.detach()
            renderer.dispose()
        }
    }

    /** A real render fills much of the frame with many distinct colours (not a blank background). */
    private fun assertRealRender(image: ImageBitmap, id: String) {
        val n = minOf(image.width, image.height)
        val pixels = image.toPixelMap()
        val colors = HashSet<Int>()
        var nonBackground = 0
        for (y in 0 until n step 4) for (x in 0 until n step 4) {
            val c = pixels[x, y]
            val r = (c.red * 255).toInt(); val g = (c.green * 255).toInt(); val b = (c.blue * 255).toInt()
            colors.add((r shl 16) or (g shl 8) or b)
            if (kotlin.math.abs(r - 26) > 12 || kotlin.math.abs(g - 28) > 12 || kotlin.math.abs(b - 33) > 12) nonBackground++
        }
        assertTrue(colors.size > 8, "scene $id: expected a real render with many colours, got ${colors.size}")
        assertTrue(nonBackground > 100, "scene $id: expected board geometry to fill the frame, got $nonBackground")
    }
}
