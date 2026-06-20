package com.example.myapplication.board3d

import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.coroutines.runBlocking
import org.junit.Assume
import org.junit.Test
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Desktop Vulkan renderer FPS benchmark. Measures end-to-end frame latency: call updatePosition →
 * renderer rebuilds geometry → GPU submit + fence + readback → frame callback. Reports average
 * frame time + FPS at the default SSAA=2 (2048² render target) and at SSAA=1 (1024², for MSAA-only
 * comparison). Gated by `-Dchess3d.bench=true` so it doesn't run in normal CI.
 */
class VulkanFpsBenchmark {

    private val startFen = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
    private val afterE4  = "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq e3 0 1"
    private val afterD4  = "rnbqkbnr/pppppppp/8/8/3P4/8/PPP1PPPP/RNBQKBNR b KQkq d3 0 1"

    @Test
    fun benchmarkFps() {
        Assume.assumeTrue(System.getProperty("chess3d.bench") == "true")
        val glb = listOf(
            File("src/commonMain/composeResources/files/models/chess.glb"),
            File("app/src/commonMain/composeResources/files/models/chess.glb"),
        ).firstOrNull { it.exists() }?.readBytes() ?: error("chess.glb not found")

        val size = 1024
        val fens = listOf(startFen, afterE4, afterD4, startFen)
        println("[bench] CHESS_DESKTOP_SSAA=${System.getenv("CHESS_DESKTOP_SSAA") ?: "2 (default)"}")

        val renderer = VulkanChessRenderer(glb)
        val frames = LinkedBlockingQueue<ImageBitmap>()
        val surface = ImageBitmapChess3DSurface(size, size) { bmp ->
            if (frames.size < 100) frames.offer(bmp)
        }

        try {
            renderer.attach(surface)
            // Warmup: render + drain the first frame.
            frames.poll(30, TimeUnit.SECONDS) ?: error("warmup frame timed out")

            // Benchmark: alternate between FENs to force a geometry rebuild + full render each frame.
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
            println("[bench] VulkanChessRenderer: ${benchFrames} frames in ${elapsedMs}ms")
            println("[bench]   avg frame time: ${"%.1f".format(avgFrameMs)} ms")
            println("[bench]   FPS: ${"%.1f".format(fps)}")
        } finally {
            renderer.detach()
            renderer.dispose()
        }
    }
}
