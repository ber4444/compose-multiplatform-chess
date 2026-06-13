package com.example.myapplication.board3d

import org.junit.Assume
import org.junit.Test
import java.io.File

class DesktopRendererSmokeTest {

    @Test
    fun smokeTestVulkanRenderer() {
        Assume.assumeTrue(System.getProperty("chess3d.smoke") == "true")
        
        val glbFile = File("src/commonMain/composeResources/files/models/chess.glb")
        Assume.assumeTrue(glbFile.exists())

        val bytes = glbFile.readBytes()
        val renderer = VulkanChessRenderer(bytes)
        
        val surface = ImageBitmapChess3DSurface(256, 256) { bmp ->
            // In a real test, we'd verify the pixels aren't empty
        }
        
        renderer.attach(surface)
        renderer.updatePosition("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1")
        
        renderer.detach()
        renderer.dispose()
    }
}
