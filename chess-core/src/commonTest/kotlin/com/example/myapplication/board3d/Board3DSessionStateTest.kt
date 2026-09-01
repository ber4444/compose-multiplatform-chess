package com.example.myapplication.board3d

import kotlin.test.Test
import kotlin.test.assertEquals

class Board3DSessionStateTest {
    @Test
    fun `renderer recreation reuses one canonical camera snapshot`() {
        val session = Board3DSessionState()
        val firstInitialCamera = session.camera

        session.onResize(1.25f)
        session.onDrag(0.2f, -0.1f)
        session.onZoom(0.8f)
        val cameraBeforeRecreation = session.camera

        assertEquals(cameraBeforeRecreation, session.cameraForRenderer())
        assertEquals(cameraBeforeRecreation, session.cameraForRenderer())
        assertEquals(firstInitialCamera.position.length() * 0.8f, cameraBeforeRecreation.position.length(), 0.001f)
    }

    @Test
    fun `black session looks from black's end and still orbits freely`() {
        // +z is rank 1 (White's end), so Black's camera has to sit at -z. Nothing else changes:
        // the yaw is only a seed, and a drag moves it exactly as it does for White.
        val white = Board3DSessionState()
        val black = Board3DSessionState(initialYawDegrees = OrbitCameraController.BLACK_YAW_DEG)

        kotlin.test.assertTrue(white.camera.position.z > 0f, "white camera should be at +z")
        kotlin.test.assertTrue(black.camera.position.z < 0f, "black camera should be at -z")
        assertEquals(white.camera.position.y, black.camera.position.y, 0.001f)
        assertEquals(white.camera.position.length(), black.camera.position.length(), 0.001f)

        black.onDrag(0.25f, 0f)
        kotlin.test.assertTrue(black.camera.position.x != 0f, "black camera should orbit like white's")
    }

    @Test
    fun `repeated vertical drags never invert camera up`() {
        val session = Board3DSessionState()

        repeat(100) { session.onDrag(0f, 1f) }

        assertEquals(1f, session.camera.up.y)
        kotlin.test.assertTrue(session.camera.position.y > 0f)
    }
}
