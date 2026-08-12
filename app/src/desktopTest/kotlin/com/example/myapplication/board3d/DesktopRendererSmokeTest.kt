package com.example.myapplication.board3d

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.graphics.toPixelMap
import org.junit.Assume
import org.junit.Test
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Exercises the headless desktop Filament renderer end-to-end: renders the
 * start position, a selection highlight, and a position after 1.e4, asserting each read-back frame
 * is a real render (many distinct colours, board fills the view) and saving PNGs to `build/` for
 * eyeballing. Gated by `-Dchess3d.smoke=true`; skips when shared assets or a compatible GPU backend
 * are unavailable.
 */
class DesktopRendererSmokeTest {

    private val startFen = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
    private val afterE4 = "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq e3 0 1"

    @Test
    fun rendersStartSelectionAndMove() {
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

        val size = 1024
        val frames = LinkedBlockingQueue<ImageBitmap>()
        fun nextFrame(): ImageBitmap {
            val f = frames.poll(30, TimeUnit.SECONDS)
            assertNotNull(f, "renderer did not produce a frame")
            return f
        }

        try {
            renderer.attach(ImageBitmapChess3DSurface(size, size) { frames.offer(it) })

            val start = nextFrame().also { save(it, "chess3d-start.png") }
            assertRealRender(start, size)

            renderer.setSelectedSquare(BoardSquare(6, 4)) // e2
            assertRealRender(nextFrame().also { save(it, "chess3d-selected.png") }, size)

            renderer.setSelectedSquare(null)
            nextFrame() // drain the deselect frame
            renderer.updatePosition(afterE4)
            assertRealRender(nextFrame().also { save(it, "chess3d-e4.png") }, size)

            // B16/B19 coach highlights: e4 GOOD and d5 BAD. Saved for eyeballing — the quads should
            // read as a green tile on e4 and a red one on d5, flat on the board. Each tone is its own
            // node in chess.glb (`HighlightGood` / `HighlightBad`), coloured by that node's material
            // `emissiveFactor`; nothing is tinted at runtime. Two distinct tones in one frame is the
            // point of the pair — it also pins that the quads are separate FilamentInstances.
            //
            // Must settle on the LATEST frame, not the next one: updatePosition above plays a move
            // animation that leaves a backlog in the queue, so nextFrame() would hand back a stale
            // pre-highlight frame (and did — it showed the start position).
            renderer.setHighlightedSquares(
                listOf(
                    HighlightedSquare(BoardSquare(4, 4), HighlightTone.GOOD),
                    HighlightedSquare(BoardSquare(3, 3), HighlightTone.BAD),
                )
            )
            assertRealRender(latestFrame(frames).also { save(it, "chess3d-highlight.png") }, size)
        } finally {
            renderer.dispose()
        }
    }

    /** Newest frame once the renderer goes quiet — for state whose effect is only in the last frame. */
    private fun latestFrame(frames: LinkedBlockingQueue<ImageBitmap>): ImageBitmap {
        var last = frames.poll(30, TimeUnit.SECONDS)
        assertNotNull(last, "renderer did not produce a frame")
        while (true) {
            val next = frames.poll(1, TimeUnit.SECONDS) ?: return last!!
            last = next
        }
    }

    private fun assertRealRender(image: ImageBitmap, size: Int) {
        // Frame dimensions match the surface's aspect (1:1 here).
        assertTrue(image.width == image.height, "frame should be square, got ${image.width}x${image.height}")
        assertTrue(image.width >= size, "frame should be at least surface size, got ${image.width}")
        val sampleSize = minOf(image.width, 1024)
        val pixels = image.toPixelMap()
        val colors = HashSet<Int>()
        var nonBackground = 0
        for (y in 0 until sampleSize step 4) for (x in 0 until sampleSize step 4) {
            val c = pixels[x, y]
            val r = (c.red * 255).toInt(); val g = (c.green * 255).toInt(); val b = (c.blue * 255).toInt()
            colors.add((r shl 16) or (g shl 8) or b)
            if (kotlin.math.abs(r - 26) > 12 || kotlin.math.abs(g - 28) > 12 || kotlin.math.abs(b - 33) > 12) nonBackground++
        }
        println("[chess3d] frame=${image.width}x${image.height} distinct colours=${colors.size}, non-background samples=$nonBackground")
        assertTrue(colors.size > 8, "expected a real render with many colours, got ${colors.size}")
        assertTrue(nonBackground > 100, "expected the board to fill much of the frame, got $nonBackground")
    }

    private fun save(image: ImageBitmap, name: String) {
        runCatching {
            // Save the raw frame and a 1024px view for quick eyeballing.
            val raw = image.toAwtImage()
            val rawOut = File("build/raw-$name").also { it.parentFile?.mkdirs() }
            ImageIO.write(raw, "PNG", rawOut)
            val dsW = minOf(raw.width, 1024)
            val dsH = minOf(raw.height, 1024)
            val downscaled = java.awt.image.BufferedImage(dsW, dsH, java.awt.image.BufferedImage.TYPE_INT_ARGB)
            val g = downscaled.createGraphics()
            g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            g.drawImage(raw, 0, 0, dsW, dsH, null); g.dispose()
            val out = File("build/$name").also { it.parentFile?.mkdirs() }
            ImageIO.write(downscaled, "PNG", out)
            println("[chess3d] wrote ${out.absolutePath} (${dsW}x${dsH}) + ${rawOut.absolutePath} (${raw.width}x${raw.height}, raw)")
        }
    }
}
