package com.example.myapplication.board3d

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.junit.Assume
import java.io.File
import kotlin.test.Test

/**
 * M6 3D spike — visual verification. Renders one frame of the start position from White's default
 * view and writes it to build/wgpu-frame.png so the result can be eyeballed (orientation: White
 * should be at the FRONT/bottom; pieces solid, not inside-out). Also exercises attach → frame →
 * detach/dispose end-to-end (the lifecycle that was causing the flashing).
 */
class Wgpu4kFrameDumpTest {
    @Test
    fun rendersFirstFrameToPng() = runBlocking {
        // DesktopWgpuChessRenderer renders into a CAMetalLayer (Metal), so this only works on macOS.
        // Skip on Linux CI, where desktopTest also runs via :app:check. (M6 3D spike.)
        Assume.assumeTrue("macOS-only (Metal/CAMetalLayer)", System.getProperty("os.name").contains("Mac"))
        val glb = listOf(
            File("src/commonMain/composeResources/files/models/chess.glb"),
            File("app/src/commonMain/composeResources/files/models/chess.glb"),
        ).firstOrNull { it.exists() }?.readBytes() ?: error("chess.glb not found")

        val renderer = DesktopWgpuChessRenderer(glb)
        val firstFrame = CompletableDeferred<ImageBitmap>()
        val surface = ImageBitmapChess3DSurface(720, 720) { bmp ->
            if (!firstFrame.isCompleted) firstFrame.complete(bmp)
        }
        try {
            renderer.attach(surface)
            val bmp = withTimeout(30_000) { firstFrame.await() }
            val png = Image.makeFromBitmap(bmp.asSkiaBitmap())
                .encodeToData(EncodedImageFormat.PNG) ?: error("PNG encode failed")
            val out = File("build/wgpu-frame.png").apply { parentFile.mkdirs() }
            out.writeBytes(png.bytes)
            println("WGPU_FRAME_PNG=${out.absolutePath} bytes=${png.bytes.size}")
        } finally {
            renderer.detach()
            renderer.dispose()
        }
    }
}
