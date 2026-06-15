package com.example.myapplication.board3d

import androidx.compose.ui.geometry.Rect
import kotlin.test.Test
import kotlin.test.assertEquals

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
    fun testOverlayCssRect_dpr2() {
        val rect = Rect(left = 10f, top = 20f, right = 110f, bottom = 120f)
        val css = overlayCssRect(rect, 2.0)
        
        assertEquals(5.0, css.left)
        assertEquals(10.0, css.top)
        assertEquals(50.0, css.width)
        assertEquals(50.0, css.height)
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
}
