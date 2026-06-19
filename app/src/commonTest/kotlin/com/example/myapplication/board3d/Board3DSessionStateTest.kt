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
    fun `repeated vertical drags never invert camera up`() {
        val session = Board3DSessionState()

        repeat(100) { session.onDrag(0f, 1f) }

        assertEquals(1f, session.camera.up.y)
        kotlin.test.assertTrue(session.camera.position.y > 0f)
    }
}
