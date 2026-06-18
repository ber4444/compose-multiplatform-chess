package com.example.myapplication.board3d

import androidx.compose.ui.geometry.Rect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OverlayCssRectTest {

    @Test
    fun testOverlayCssRect_dpr1() {
        val rect = Rect(left = 10f, top = 20f, right = 110f, bottom = 120f)
        val css = overlayCssRect(rect, 1.0)
        
        assertEquals(10.0, css.left)
        assertEquals(20.0, css.top)
        assertEquals(100.0, css.width)
        assertEquals(100.0, css.height)
    }

    @Test
    fun testOverlayCssRect_dpr2KeepsCssCoordinates() {
        val rect = Rect(left = 10f, top = 20f, right = 110f, bottom = 120f)
        val css = overlayCssRect(rect, 2.0)
        
        assertEquals(10.0, css.left)
        assertEquals(20.0, css.top)
        assertEquals(100.0, css.width)
        assertEquals(100.0, css.height)
    }

    @Test
    fun testOverlayPhysicalSize_scalesByDpr() {
        val css = CssRect(left = 10.0, top = 20.0, width = 100.0, height = 120.0)
        val physical = overlayPhysicalSize(css, 2.0)

        assertEquals(Pair(200, 240), physical)
    }

    @Test
    fun testOverlayCssRect_zeroSize() {
        val rect = Rect(left = 0f, top = 0f, right = 0f, bottom = 0f)
        val css = overlayCssRect(rect, 1.5)
        
        assertEquals(0.0, css.left)
        assertEquals(0.0, css.top)
        assertEquals(0.0, css.width)
        assertEquals(0.0, css.height)
    }

    @Test
    fun testOverlayCanvasIsInsertedBehindComposeContent() {
        val stacking = overlayCanvasStacking()

        assertTrue(stacking.insertAsFirstBodyChild)
        assertEquals("none", stacking.pointerEvents)
    }
}
