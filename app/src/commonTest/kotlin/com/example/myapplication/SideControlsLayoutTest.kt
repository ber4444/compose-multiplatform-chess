package com.example.myapplication

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
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

    // A Pixel 7a in landscape, in px: rail buttons 48dp tall, 8dp apart, the rail 6dp in from the
    // edge, and the camera hole on one long edge at mid-height (cutout rects as Android reports them,
    // moved into the rail's coordinates).
    private val nav = listOf(Size(152f, 126f), Size(157f, 126f), Size(200f, 126f), Size(184f, 126f)) // Chat Rules Settings History
    private val game = listOf(Size(159f, 126f), Size(152f, 126f), Size(182f, 126f)) // Reset Hint Explain
    private val rail = 357f // 136dp
    private val row = 21f
    private val gap = 21f
    private val margin = 16f
    private val leftCamera = Rect(left = -16f, top = 369f, right = 102f, bottom = 514f) // rotation 90
    private val rightCamera = Rect(left = rail + 16f - 118f, top = 394f, right = rail + 16f, bottom = 539f) // rotation 270

    private fun layout(sizes: List<Size>, alignEnd: Boolean, cutouts: List<Rect>) =
        railPositions(sizes, rail, row, gap, alignEnd, cutouts, margin)

    @Test
    fun withoutACutoutOnItsEdgeARailIsOneColumnFromTheTop() {
        listOf(emptyList(), listOf(rightCamera)).forEach { cutouts ->
            val left = layout(nav, alignEnd = false, cutouts)
            assertEquals(listOf(0f, 147f, 294f, 441f), left.map { it.y }, "stacked from the top ($cutouts)")
            assertTrue(left.all { it.x == 0f }, "against the left edge ($cutouts)")
        }
        val right = layout(game, alignEnd = true, listOf(leftCamera))
        assertEquals(game.map { rail - it.width }, right.map { it.x }, "against the right edge")
        assertEquals(listOf(0f, 147f, 294f), right.map { it.y })
    }

    @Test
    fun theButtonsFromTheFirstOneMeetingTheCutoutWrapIntoATopAlignedInnerColumn() {
        // Camera on the left: Settings (rows 294..420) comes within the margin of the hole, so
        // Settings and History move into a second column, level with Chat and Rules.
        val left = layout(nav, alignEnd = false, listOf(leftCamera))
        val innerX = 157f + gap // widest of Chat/Rules, plus the gap
        assertEquals(
            listOf(Offset(0f, 0f), Offset(0f, 147f), Offset(innerX, 0f), Offset(innerX, 147f)),
            left,
        )
        // Camera on the right: Explain (rows 294..420) meets it, and goes up beside Reset.
        val right = layout(game, alignEnd = true, listOf(rightCamera))
        val innerRight = rail - 159f - gap
        assertEquals(
            listOf(Offset(rail - 159f, 0f), Offset(rail - 152f, 147f), Offset(innerRight - 182f, 0f)),
            right,
        )
    }

    @Test
    fun aCutoutAtTheTopOfTheEdgeMovesTheWholeRailPastIt() {
        // iOS reports its notch as a strip down the whole edge, so the first button meets it.
        val notch = Rect(left = -16f, top = -100f, right = 140f, bottom = 2000f)
        val left = layout(nav, alignEnd = false, listOf(notch))
        assertEquals(listOf(0f, 147f, 294f, 441f), left.map { it.y }, "still one column from the top")
        assertTrue(left.all { it.x == notch.right + margin }, "all clear of the notch: $left")
    }
}
