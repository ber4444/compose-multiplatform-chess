package com.example.myapplication

import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals

class ThreeDControlStyleTest {
    @Test
    fun `3d controls use transparent surfaces with dark content and gray accents`() {
        assertEquals(Color.Transparent, THREE_D_CONTROL_CONTAINER_COLOR)
        assertEquals(Color.Black, THREE_D_CONTROL_CONTENT_COLOR)
        assertEquals(Color.LightGray.copy(alpha = 0.70f), THREE_D_CONTROL_ACCENT_COLOR)
        assertEquals(Color.Black.copy(alpha = 0.38f), THREE_D_CONTROL_DISABLED_CONTENT_COLOR)
        assertEquals(Color.LightGray.copy(alpha = 0.38f), THREE_D_CONTROL_DISABLED_ACCENT_COLOR)
    }
}
