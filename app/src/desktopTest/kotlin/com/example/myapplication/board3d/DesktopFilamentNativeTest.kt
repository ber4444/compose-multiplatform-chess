package com.example.myapplication.board3d

import kotlin.test.Test
import kotlin.test.assertTrue

class DesktopFilamentNativeTest {
    @Test
    fun `native library path error names fetch script`() {
        val result = DesktopFilamentNative.tryLoadForTest(emptyList())

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("tools/fetch_filament_desktop.sh") == true)
    }
}
