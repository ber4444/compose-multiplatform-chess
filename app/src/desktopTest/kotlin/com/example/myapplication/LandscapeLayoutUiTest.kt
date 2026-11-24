package com.example.myapplication

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.example.myapplication.board3d.Board3DSupport
import com.example.myapplication.board3d.FakeChess3DRenderer
import com.example.myapplication.board3d.FakeChess3DSurface
import com.example.myapplication.movecoach.MoveCoachManager
import com.example.myapplication.persistence.AppSettings
import com.example.myapplication.persistence.LocalAppSettings
import com.example.myapplication.persistence.MapSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Where the game screen puts its controls for a given window shape.
 *
 * Phones run in landscape (the Android manifest and the iOS Info.plist hold them there), so the
 * buttons live in rails at the sides — navigation on the left, game actions on the right — and the
 * coach text runs along the bottom. Portrait keeps the original stack. Positions are compared
 * relative to each other and to the frame, so the checks hold at any test density.
 */
@OptIn(ExperimentalTestApi::class)
class LandscapeLayoutUiTest {

    private val phoneLandscape = DpSize(914.dp, 411.dp)
    private val phonePortrait = DpSize(400.dp, 760.dp)

    private val navButtons = listOf("open_chat_button", "open_rules_button", "open_settings_button", "open_history_button")
    private val gameButtons = listOf("reset_button", "move_coach_explain_toggle")

    @Test
    fun landscape3DPutsTheButtonsInSideRailsAndTheCoachAlongTheBottom() = runComposeUiTest {
        val viewModel = GameViewModel()
        val coach = MoveCoachManager(viewModel)
        try {
            setContent {
                CompositionLocalProvider(LocalAppSettings provides AppSettings(MapSettings())) {
                    Frame(phoneLandscape, coach) {
                        GameScreen(WindowWidthSizeClass.Expanded, viewModel, board3D = fakeBoard3D())
                    }
                }
            }
            waitForIdle()
            onNodeWithTag("board_3d").assertExists()

            coach.explainSquare("e4", "e4", "The pawn on e4 controls d5 and f5.")
            waitForIdle()

            val frame = bounds("frame")
            val startRail = bounds("side_rail_start")
            val endRail = bounds("side_rail_end")
            assertTrue(startRail.right <= frame.left + frame.width / 4, "start rail hugs the left edge: $startRail in $frame")
            assertTrue(endRail.left >= frame.right - frame.width / 4, "end rail hugs the right edge: $endRail in $frame")

            navButtons.forEach { assertInside(it, startRail) }
            gameButtons.forEach { assertInside(it, endRail) }
            assertRailsHugTheTopCorners(frame)

            // The coach strip sits at the bottom, between the rails, and stays short enough to stay
            // under the board's near edge.
            val panel = bounds("move_coach_panel")
            assertTrue(panel.left >= startRail.right && panel.right <= endRail.left, "coach $panel runs between the rails")
            assertTrue(panel.bottom >= frame.bottom - frame.height * 0.05f, "coach $panel is at the bottom of $frame")
            assertTrue(panel.height <= frame.height * 0.2f + 1f, "coach $panel is taller than a fifth of $frame")
        } finally {
            coach.close()
            viewModel.close()
        }
    }

    @Test
    fun landscape2DFlanksTheBoardAndKeepsItFullHeightWhenTheCoachAppears() = runComposeUiTest {
        val viewModel = GameViewModel()
        val coach = MoveCoachManager(viewModel)
        try {
            // No 3D support, so this is the 2D board.
            setContent {
                Frame(phoneLandscape, coach) { GameScreen(WindowWidthSizeClass.Expanded, viewModel) }
            }
            waitForIdle()

            val frame = bounds("frame")
            val board = bounds("chess_board")
            navButtons.forEach { tag ->
                assertTrue(bounds(tag).right <= board.left, "$tag should be left of the board")
            }
            gameButtons.forEach { tag ->
                assertTrue(bounds(tag).left >= board.right, "$tag should be right of the board")
            }
            assertTrue(board.height >= frame.height * 0.85f, "board $board should take the height of $frame")
            assertRailsHugTheTopCorners(frame)

            coach.explainSquare("e4", "e4", "The pawn on e4 controls d5 and f5.")
            waitForIdle()

            // The coach text shares the right-hand margin rather than taking height from the board.
            val panel = bounds("move_coach_panel")
            assertTrue(panel.left >= board.right, "coach $panel should sit right of the board $board")
            assertEquals(board, bounds("chess_board"), "the board must not shrink when the coach appears")
        } finally {
            coach.close()
            viewModel.close()
        }
    }

    @Test
    fun portraitKeepsNavAboveAndGameButtonsBelowTheBoard() = runComposeUiTest {
        val viewModel = GameViewModel()
        try {
            setContent {
                Frame(phonePortrait) { GameScreen(WindowWidthSizeClass.Compact, viewModel) }
            }
            waitForIdle()

            val board = bounds("chess_board")
            assertTrue(bounds("open_settings_button").bottom <= board.top, "nav row sits above the board")
            assertTrue(bounds("reset_button").top >= board.bottom, "game buttons sit below the board")
            assertTrue(onAllNodesWithTag("side_rail_start").fetchSemanticsNodes().isEmpty(), "no rails in portrait")
        } finally {
            viewModel.close()
        }
    }

    /** A window of [size] with the coach published, as `AppRoot` would. */
    @Composable
    private fun Frame(size: DpSize, coach: MoveCoachManager? = null, content: @Composable () -> Unit) {
        CompositionLocalProvider(LocalMoveCoachManager provides coach) {
            Box(Modifier.requiredSize(size).testTag("frame")) { content() }
        }
    }

    /**
     * Both rails start at the top and keep to the side edges: each button within a few dp of its
     * edge, stacked downwards in order. This window has no display cutout, so each rail stays one
     * column; wrapping beside a cutout is `SideControlsLayoutTest` and `LandscapeRailsDeviceTest`.
     */
    private fun ComposeUiTest.assertRailsHugTheTopCorners(frame: Rect) {
        val near = with(density) { 12.dp.toPx() }
        listOf(navButtons, gameButtons).forEach { rail ->
            assertTrue(bounds(rail.first()).top <= frame.top + near, "${rail.first()} starts at the top: ${bounds(rail.first())} in $frame")
            rail.zipWithNext().forEach { (above, below) ->
                assertTrue(bounds(above).bottom <= bounds(below).top, "$above sits above $below")
            }
        }
        navButtons.forEach { tag ->
            assertTrue(bounds(tag).left - frame.left <= near, "$tag hugs the left edge: ${bounds(tag)} in $frame")
        }
        gameButtons.forEach { tag ->
            assertTrue(frame.right - bounds(tag).right <= near, "$tag hugs the right edge: ${bounds(tag)} in $frame")
        }
    }

    private fun ComposeUiTest.bounds(tag: String): Rect = onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot

    private fun ComposeUiTest.assertInside(tag: String, container: Rect) {
        val node = bounds(tag)
        assertTrue(
            node.left >= container.left && node.right <= container.right &&
                node.top >= container.top && node.bottom <= container.bottom,
            "$tag $node should be inside $container",
        )
    }

    private fun fakeBoard3D() = Board3DSupport(
        rendererFactory = { FakeChess3DRenderer() },
        surfaceContent = { renderer, modifier ->
            DisposableEffect(renderer) {
                renderer.attach(FakeChess3DSurface())
                onDispose { renderer.detach() }
            }
            Box(modifier)
        },
    )
}
