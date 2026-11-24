package com.example.myapplication

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
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

    // A Pixel 7a in landscape, in px: the camera hole sits on one long edge at mid-height.
    private val camera = Rect(left = 0f, top = 455f, right = 118f, bottom = 600f)
    private val margin = 16f

    @Test
    fun railButtonsAwayFromTheCutoutKeepHuggingTheEdge() {
        val above = railChildClearOfCutouts(0f, 200f, top = 84f, bottom = 189f, alignEnd = false, listOf(camera), margin)
        assertEquals(0f, above, "a button in rows the camera doesn't reach stays at the edge")
        val endRail = railChildClearOfCutouts(2180f, 200f, top = 455f, bottom = 560f, alignEnd = true, listOf(camera), margin)
        assertEquals(2180f, endRail, "the other edge's rail is not affected")
        assertEquals(0f, railChildClearOfCutouts(0f, 200f, 455f, 560f, false, emptyList(), margin), "no cutout, no step")
    }

    @Test
    fun railButtonsMeetingTheCutoutStepJustPastIt() {
        val start = railChildClearOfCutouts(0f, 200f, top = 462f, bottom = 567f, alignEnd = false, listOf(camera), margin)
        assertEquals(camera.right + margin, start)
        // The same hole on the right-hand edge (the other landscape), against the end rail.
        val mirrored = Rect(left = 2282f, top = 480f, right = 2400f, bottom = 625f)
        val end = railChildClearOfCutouts(2194f, 200f, top = 462f, bottom = 567f, alignEnd = true, listOf(mirrored), margin)
        assertEquals(mirrored.left - margin - 200f, end)
        // Rows that come within the margin of the hole count as meeting it.
        assertEquals(camera.right + margin, railChildClearOfCutouts(0f, 200f, 340f, 445f, false, listOf(camera), margin))
    }
}
