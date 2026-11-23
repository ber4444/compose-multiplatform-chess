package com.example.myapplication

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The window-shape rule behind the landscape layout. `LandscapeLayoutUiTest` covers what it draws. */
class SideControlsLayoutTest {

    @Test
    fun landscapeWindowsPutTheButtonsInSideRails() {
        assertTrue(usesSideControls(width = 914.dp, height = 411.dp), "phone held sideways")
        assertTrue(usesSideControls(width = 1024.dp, height = 768.dp), "tablet in landscape / wide window")
    }

    @Test
    fun portraitAndSquareWindowsKeepTheStackedLayout() {
        assertFalse(usesSideControls(width = 411.dp, height = 914.dp), "phone in portrait")
        assertFalse(usesSideControls(width = 800.dp, height = 900.dp), "the desktop build's default window")
        assertFalse(usesSideControls(width = 600.dp, height = 600.dp), "square window")
    }
}
