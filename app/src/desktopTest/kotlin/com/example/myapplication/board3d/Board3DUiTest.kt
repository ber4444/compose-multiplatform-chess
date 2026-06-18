package com.example.myapplication.board3d

import androidx.compose.foundation.layout.Box
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.example.myapplication.GameScreen
import com.example.myapplication.GameViewModel
import com.example.myapplication.WindowWidthSizeClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class Board3DUiTest {

    @Test
    fun teardownLoaderIsVisibleBeforeRendererDisposal() = runComposeUiTest {
        val fakeRenderer = FakeChess3DRenderer()
        val support = Board3DSupport(
            rendererFactory = { fakeRenderer },
            surfaceContent = { renderer, modifier ->
                androidx.compose.runtime.DisposableEffect(renderer) {
                    renderer.attach(FakeChess3DSurface())
                    onDispose { renderer.detach() }
                }
                Box(modifier)
            },
        )
        val viewModel = GameViewModel()
        mainClock.autoAdvance = false

        setContent {
            GameScreen(WindowWidthSizeClass.Medium, viewModel, support)
        }
        mainClock.advanceTimeByFrame()
        mainClock.advanceTimeByFrame()

        onNodeWithTag("board_3d_toggle").performClick()
        mainClock.advanceTimeByFrame()

        onNodeWithTag("board_3d_tearing_down").assertExists()
        assertEquals(0, fakeRenderer.events.count { it == "dispose" })

        mainClock.advanceTimeByFrame()
        mainClock.advanceTimeByFrame()
        mainClock.autoAdvance = true
        waitForIdle()
        assertEquals(1, fakeRenderer.events.count { it == "dispose" })
    }

    @Test
    fun test3DToggleAndFallback() = runComposeUiTest {
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

        setContent {
            GameScreen(
                windowSize = WindowWidthSizeClass.Medium,
                viewModel = viewModel,
                board3D = board3DSupport
            )
        }

        // Initially 3D board is shown, 2D is not
        assertTrue(onAllNodesWithTag("board_3d").fetchSemanticsNodes().isNotEmpty())
        assertTrue(onAllNodesWithTag("chess_board").fetchSemanticsNodes().isEmpty())

        // Verify renderer was attached
        assertEquals(1, fakeRenderer.events.count { it == "attach" })

        // Toggle 3D off
        viewModel.setShow3D(false)
        waitForIdle()

        // 2D board should be shown, 3D board should NOT be shown
        assertTrue(onAllNodesWithTag("chess_board").fetchSemanticsNodes().isNotEmpty())
        assertTrue(onAllNodesWithTag("board_3d").fetchSemanticsNodes().isEmpty())

        // Verify renderer was detached and disposed
        assertEquals(1, fakeRenderer.events.count { it == "detach" })
        assertEquals(1, fakeRenderer.events.count { it == "dispose" })

        // Toggle 3D on again
        viewModel.setShow3D(true)
        waitForIdle()

        // 3D back, 2D gone
        assertTrue(onAllNodesWithTag("board_3d").fetchSemanticsNodes().isNotEmpty())
        assertTrue(onAllNodesWithTag("chess_board").fetchSemanticsNodes().isEmpty())
        
        // Verify renderer was attached again
        assertEquals(2, fakeRenderer.events.count { it == "attach" })
    }

    @Test
    fun test3DFallbackWhenFactoryReturnsNull() = runComposeUiTest {
        val board3DSupport = Board3DSupport(
            rendererFactory = { null }, // init fails
            surfaceContent = { _, modifier -> Box(modifier) }
        )

        val viewModel = GameViewModel()

        setContent {
            GameScreen(
                windowSize = WindowWidthSizeClass.Medium,
                viewModel = viewModel,
                board3D = board3DSupport
            )
        }

        // Toggle 3D on
        viewModel.setShow3D(true)
        waitForIdle()

        // 2D board is still shown
        assertTrue(onAllNodesWithTag("chess_board").fetchSemanticsNodes().isNotEmpty())
        assertTrue(onAllNodesWithTag("board_3d").fetchSemanticsNodes().isEmpty())

        // Unavailable message is shown
        assertTrue(onAllNodesWithTag("board_3d_unavailable").fetchSemanticsNodes().isNotEmpty())
    }

    @Test
    fun test3DAnimationDelivery() = runComposeUiTest {
        val fakeRenderer = FakeChess3DRenderer()
        val board3DSupport = Board3DSupport(
            rendererFactory = { fakeRenderer },
            surfaceContent = { renderer, modifier -> 
                androidx.compose.runtime.DisposableEffect(renderer) {
                    val fakeSurface = FakeChess3DSurface()
                    renderer.attach(fakeSurface)
                    onDispose { renderer.detach() }
                }
                Box(modifier) 
            }
        )

        val viewModel = GameViewModel()

        setContent {
            GameScreen(
                windowSize = WindowWidthSizeClass.Medium,
                viewModel = viewModel,
                board3D = board3DSupport
            )
        }

        waitForIdle()
        fakeRenderer.events.clear()

        // Make a move
        val e2 = Pair(6, 4)
        val e4 = Pair(4, 4)
        viewModel.updateSelected(e2)
        waitForIdle()
        
        // Find index of pawn at e2
        val state = viewModel.gameState.value
        val index = state.positionsWhite.indexOf(e2)
        
        viewModel.playerMove(index, e4)
        waitForIdle()

        // Ensure updatePosition with animation was delivered
        val animationEvents = fakeRenderer.events.filter { it.startsWith("updatePosition:") && it.endsWith(":animate") }
        assertTrue(animationEvents.isNotEmpty(), "Expected at least one animated updatePosition event")
    }
}
