package com.example.myapplication.board3d

import androidx.compose.ui.graphics.ImageBitmap
import org.junit.Assume
import org.junit.Test
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Desktop Filament renderer FPS benchmark. Measures end-to-end frame latency: call updatePosition,
 * shared scene animation driver updates the native instance pool, Filament renders headlessly, and
 * readPixels returns RGBA to Compose. Gated by `-Dchess3d.bench=true` so it doesn't run in normal CI.
 */
class DesktopFilamentFpsBenchmark {

    private val startFen = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
    private val afterE4 = "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq e3 0 1"
    private val afterD4 = "rnbqkbnr/pppppppp/8/8/3P4/8/PPP1PPPP/RNBQKBNR b KQkq d3 0 1"

    @Test
    fun benchmarkFps() {
        Assume.assumeTrue(System.getProperty("chess3d.bench") == "true")
        val glb = listOf(
            File("src/commonMain/composeResources/files/models/chess.glb"),
            File("app/src/commonMain/composeResources/files/models/chess.glb"),
        ).firstOrNull { it.exists() }?.readBytes() ?: error("chess.glb not found")
        val ibl = listOf(
            File("src/commonMain/composeResources/files/env/papermill_ibl.ktx"),
            File("app/src/commonMain/composeResources/files/env/papermill_ibl.ktx"),
        ).firstOrNull { it.exists() }?.readBytes() ?: error("papermill_ibl.ktx not found")
        val skybox = listOf(
            File("src/commonMain/composeResources/files/env/papermill_skybox_blurred.ktx"),
            File("app/src/commonMain/composeResources/files/env/papermill_skybox_blurred.ktx"),
        ).firstOrNull { it.exists() }?.readBytes() ?: error("papermill_skybox_blurred.ktx not found")

        val size = 1024
        val fens = listOf(startFen, afterE4, afterD4, startFen)
        println("[bench] Desktop Filament headless readback")

        val renderer = DesktopFilamentChessRenderer(glb, ibl, skybox)
        val frames = LinkedBlockingQueue<ImageBitmap>()
        val surface = ImageBitmapChess3DSurface(size, size) { bmp ->
            if (frames.size < 100) frames.offer(bmp)
        }

        try {
            renderer.attach(surface)
            frames.poll(30, TimeUnit.SECONDS) ?: error("warmup frame timed out")

            val warmupFrames = 5
            val benchFrames = 60
            for (i in 1..warmupFrames) {
                renderer.updatePosition(fens[i % fens.size])
                frames.poll(10, TimeUnit.SECONDS)
            }
            val t0 = System.nanoTime()
            for (i in 1..benchFrames) {
                renderer.updatePosition(fens[i % fens.size])
                frames.poll(10, TimeUnit.SECONDS) ?: error("frame $i timed out")
            }
            val elapsedMs = (System.nanoTime() - t0) / 1_000_000
            val avgFrameMs = elapsedMs.toDouble() / benchFrames
            val fps = 1000.0 / avgFrameMs
            println("[bench] DesktopFilamentChessRenderer: ${benchFrames} frames in ${elapsedMs}ms")
            println("[bench]   avg frame time: ${"%.1f".format(avgFrameMs)} ms")
            println("[bench]   FPS: ${"%.1f".format(fps)}")
        } finally {
            renderer.detach()
            renderer.dispose()
        }
    }
}
