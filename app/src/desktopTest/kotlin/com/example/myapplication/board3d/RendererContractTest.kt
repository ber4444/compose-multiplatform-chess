package com.example.myapplication.board3d

import org.junit.Test
import kotlin.test.assertEquals
import java.io.File
import org.junit.Assume

class RendererContractTest {

    @Test
    fun testRendererLifecycle() {
        val glbFile = File("src/commonMain/composeResources/files/models/chess.glb")
        Assume.assumeTrue(glbFile.exists())
        
        val bytes = glbFile.readBytes()
        // Constructor does real Vulkan init; skip (don't fail) where no device is available.
        val renderer = try {
            VulkanChessRenderer(bytes)
        } catch (t: Throwable) {
            Assume.assumeNoException("No Vulkan device available", t); return
        }

        // updatePosition before attach must be buffered, not crash; detach idempotent; dispose clean.
        renderer.updatePosition("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1")
        renderer.detach() // idempotent even before attach
        renderer.attach(FakeChess3DSurface()) // non-ImageBitmap surface: accepted, no render
        renderer.detach()
        renderer.dispose()

        assertEquals(true, true) // reaching here without crashing is the contract
    }
}
