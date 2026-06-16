package com.example.myapplication.board3d

import org.junit.Assume
import org.junit.Test
import java.io.File
import kotlin.test.assertFailsWith

/**
 * Headless lifecycle contract for the shipped desktop renderer ([DesktopWgpuChessRenderer]).
 * Construction only parses the glTF (no GPU/device), so this runs without a Vulkan/Metal device:
 *  - updatePosition before attach is buffered (no device yet), not a crash;
 *  - detach is idempotent even before attach;
 *  - attach rejects a non-ImageBitmap surface up front (the renderer only renders into an
 *    ImageBitmap sink) rather than silently no-op'ing;
 *  - dispose tears the renderer down cleanly.
 * The full attach -> frame -> detach render path (which needs a GPU) is covered by Wgpu4kFrameDumpTest.
 */
class RendererContractTest {

    @Test
    fun testRendererLifecycle() {
        val glb = listOf(
            File("src/commonMain/composeResources/files/models/chess.glb"),
            File("app/src/commonMain/composeResources/files/models/chess.glb"),
        ).firstOrNull { it.exists() }
        Assume.assumeTrue("chess.glb present", glb != null)

        val renderer = DesktopWgpuChessRenderer(glb!!.readBytes())
        try {
            // updatePosition before attach must be buffered (no device yet), not crash.
            renderer.updatePosition("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1")
            // detach is idempotent even before attach.
            renderer.detach()
            // Only an ImageBitmapChess3DSurface is accepted; any other surface is rejected up front.
            assertFailsWith<IllegalArgumentException> { renderer.attach(FakeChess3DSurface()) }
        } finally {
            renderer.dispose()
        }
    }
}
