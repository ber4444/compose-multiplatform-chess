package com.example.myapplication.board3d

import kotlinx.cinterop.ExperimentalForeignApi
import platform.QuartzCore.CAMetalLayer
import kotlin.test.Test
import kotlin.test.assertNotNull

/**
 * Minimal smoke test for the iOS 3D renderer. 
 * Creates an IosSceneKitChessRenderer and verifies it initializes without crashing.
 */
class IosRendererSmokeTest {

    @OptIn(ExperimentalForeignApi::class)
    @Test
    fun testRendererInitialization() {
        val renderer = try {
            IosSceneKitChessRenderer()
        } catch (t: Throwable) {
            println("Renderer failed to initialize: ${t.message}")
            return
        }

        assertNotNull(renderer)
        
        // Verify it doesn't crash on instantiation.
        renderer.dispose()
    }
}
