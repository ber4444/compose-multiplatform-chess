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
        val renderer = VulkanChessRenderer(bytes)
        
        val fakeSurface = FakeChess3DSurface()
        
        // Update before attach
        renderer.updatePosition("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1")
        
        renderer.attach(fakeSurface)
        renderer.detach()
        renderer.dispose()
        
        // No crash should happen
        assertEquals(true, true)
    }
}
