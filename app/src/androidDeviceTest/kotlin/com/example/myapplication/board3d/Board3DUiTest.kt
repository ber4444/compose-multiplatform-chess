package com.example.myapplication.board3d

import androidx.compose.foundation.layout.Box
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithTag
import com.example.myapplication.GameScreen
import com.example.myapplication.GameViewModel
import com.example.myapplication.WindowWidthSizeClass
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class Board3DUiTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun test3DToggleAndFallback() {
        val fakeRenderer = FakeChess3DRenderer()
        val board3DSupport = Board3DSupport(
            rendererFactory = { fakeRenderer },
            surfaceContent = { renderer, modifier -> 
                androidx.compose.runtime.DisposableEffect(renderer) {
                    val fakeSurface = FakeChess3DSurface()
                    renderer.attach(fakeSurface)
                    onDispose {
                        renderer.detach()
                    }
                }
                Box(modifier) 
            }
        )

        val viewModel = GameViewModel()

        rule.setContent {
            GameScreen(
                windowSize = WindowWidthSizeClass.Medium,
                viewModel = viewModel,
                board3D = board3DSupport
            )
        }

        // Initially 2D board is shown, 3D is not
        assertTrue(rule.onAllNodesWithTag("chess_board").fetchSemanticsNodes().isNotEmpty())
        assertTrue(rule.onAllNodesWithTag("board_3d").fetchSemanticsNodes().isEmpty())

        // Toggle 3D on
        viewModel.setShow3D(true)
        rule.waitForIdle()

        // 3D board should be shown, 2D board should NOT be shown
        assertTrue(rule.onAllNodesWithTag("board_3d").fetchSemanticsNodes().isNotEmpty())
        assertTrue(rule.onAllNodesWithTag("chess_board").fetchSemanticsNodes().isEmpty())

        // Verify renderer was attached
        assertEquals(1, fakeRenderer.events.count { it == "attach" })
        
        // Toggle 3D off
        viewModel.setShow3D(false)
        rule.waitForIdle()

        // 2D back, 3D gone
        assertTrue(rule.onAllNodesWithTag("chess_board").fetchSemanticsNodes().isNotEmpty())
        assertTrue(rule.onAllNodesWithTag("board_3d").fetchSemanticsNodes().isEmpty())
        
        // Verify renderer was detached and disposed
        assertEquals(1, fakeRenderer.events.count { it == "detach" })
        assertEquals(1, fakeRenderer.events.count { it == "dispose" })
    }

    @Test
    fun test3DFallbackWhenFactoryReturnsNull() {
        val board3DSupport = Board3DSupport(
            rendererFactory = { null }, // init fails
            surfaceContent = { _, modifier -> Box(modifier) }
        )

        val viewModel = GameViewModel()

        rule.setContent {
            GameScreen(
                windowSize = WindowWidthSizeClass.Medium,
                viewModel = viewModel,
                board3D = board3DSupport
            )
        }

        // Toggle 3D on
        viewModel.setShow3D(true)
        rule.waitForIdle()

        // 2D board is still shown
        assertTrue(rule.onAllNodesWithTag("chess_board").fetchSemanticsNodes().isNotEmpty())
        assertTrue(rule.onAllNodesWithTag("board_3d").fetchSemanticsNodes().isEmpty())

        // Unavailable message is shown
        assertTrue(rule.onAllNodesWithTag("board_3d_unavailable").fetchSemanticsNodes().isNotEmpty())
    }
}
