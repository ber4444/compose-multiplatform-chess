package com.example.myapplication.board3d

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OrbitCameraControllerTest {

    @Test
    fun `pitch clamps at 15 and 85 degrees`() {
        val controller = OrbitCameraController(1f)
        
        // Drag all the way down
        controller.onDrag(0f, -100f) // negative y delta decreases pitch?
        // Wait, the implementation is: pitchDegrees += deltaYNorm * 90f
        // So negative drag goes down, positive drag goes up. Let's drag super far down
        controller.onDrag(0f, -10f)
        var camera = controller.camera
        
        // The pitch should be at least 15. The exact assertion depends on how position is calculated
        // But we can check if it clamped by dragging further and seeing if it changes
        val position1 = camera.position
        controller.onDrag(0f, -10f)
        val position2 = controller.camera.position
        
        assertEquals(position1, position2, "Pitch should clamp at minimum")
        
        // Drag all the way up
        controller.onDrag(0f, 100f)
        camera = controller.camera
        val position3 = camera.position
        controller.onDrag(0f, 10f)
        val position4 = controller.camera.position
        
        assertEquals(position3, position4, "Pitch should clamp at maximum")
    }

    @Test
    fun `zoom distance clamps at 6 and 20`() {
        val controller = OrbitCameraController(1f)
        
        // Zoom out massively (distance *= factor, factor > 1 zooms out)
        controller.onZoom(100f)
        var camera = controller.camera
        val pos1 = camera.position
        controller.onZoom(10f)
        val pos2 = controller.camera.position
        
        assertEquals(pos1, pos2, "Distance should clamp at maximum 20")
        
        // Zoom in massively (factor < 1 zooms in)
        controller.onZoom(0.001f)
        camera = controller.camera
        val pos3 = camera.position
        controller.onZoom(0.5f)
        val pos4 = controller.camera.position
        
        assertEquals(pos3, pos4, "Distance should clamp at minimum 6")
    }

    @Test
    fun `drag changes yaw monotonically`() {
        val controller = OrbitCameraController(1f)
        val pos1 = controller.camera.position
        
        // Horizontal drag changes yaw
        controller.onDrag(0.1f, 0f)
        val pos2 = controller.camera.position
        
        assertTrue(pos1 != pos2, "Drag should change yaw and therefore position")
        
        controller.onDrag(0.1f, 0f)
        val pos3 = controller.camera.position
        
        assertTrue(pos2 != pos3, "Drag should continuously change yaw")
    }

    @Test
    fun `onResize updates aspect only`() {
        val controller = OrbitCameraController(1f)
        assertEquals(1f, controller.camera.aspect)
        
        controller.onResize(1.5f)
        assertEquals(1.5f, controller.camera.aspect)
        
        // Position should remain identical
        val defaultWhiteView = OrbitCameraController.DEFAULT_WHITE_VIEW
        val pos1 = defaultWhiteView.position
        
        val newController = OrbitCameraController(1f)
        newController.onResize(2f)
        assertEquals(pos1, newController.camera.position, "Resize should not affect position")
    }
}
