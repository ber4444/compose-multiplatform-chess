package com.example.myapplication.board3d

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.runComposeUiTest
import com.example.myapplication.GameScreen
import com.example.myapplication.GameViewModel
import com.example.myapplication.WindowWidthSizeClass
import com.example.myapplication.persistence.AppSettings
import com.example.myapplication.persistence.LocalAppSettings
import com.example.myapplication.persistence.MapSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Wraps `GameScreen` content with a `LocalAppSettings` so the screen's read of
 * `AppSettings.board3DEnabled` resolves (GameScreen observes the persisted 3D setting to drive its
 * mount/teardown choreography). Returns the [AppSettings] so tests can drive the toggle via the
 * real setting path (`setBoard3DEnabled`), which is what fires the entry/teardown `LaunchedEffect`.
 */
@OptIn(ExperimentalTestApi::class)
private fun androidx.compose.ui.test.ComposeUiTest.wrapGame(
    viewModel: GameViewModel,
    support: Board3DSupport,
): AppSettings {
    val appSettings = AppSettings(MapSettings())
    setContent {
        CompositionLocalProvider(LocalAppSettings provides appSettings) {
            GameScreen(WindowWidthSizeClass.Medium, viewModel, support)
        }
    }
    return appSettings
}

@OptIn(ExperimentalTestApi::class)
class Board3DUiTest {

    private fun fakeSupport(renderer: FakeChess3DRenderer) = Board3DSupport(
        rendererFactory = { renderer },
        surfaceContent = { r, modifier ->
            androidx.compose.runtime.DisposableEffect(r) {
                r.attach(FakeChess3DSurface())
                onDispose { r.detach() }
            }
            Box(modifier)
        },
    )

    @Test
    fun teardownLoaderIsVisibleBeforeRendererDisposal() = runComposeUiTest {
        val fakeRenderer = FakeChess3DRenderer()
        val viewModel = GameViewModel()
        mainClock.autoAdvance = false

        val appSettings = wrapGame(viewModel, fakeSupport(fakeRenderer))
        mainClock.advanceTimeByFrame()
        mainClock.advanceTimeByFrame()

        // Toggle 3D off via the persisted setting (the on-screen Switch moved to SettingsScreen;
        // GameScreen's LaunchedEffect reacts to board3DEnabled and runs the teardown frames). The
        // effect relaunch needs a frame, then its first statement sets isTearingDown3D=true.
        appSettings.setBoard3DEnabled(false)
        mainClock.advanceTimeByFrame()
        mainClock.advanceTimeByFrame()

        assertTrue(onAllNodesWithTag("board_3d_tearing_down").fetchSemanticsNodes().isNotEmpty())
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
        val viewModel = GameViewModel()

        val appSettings = wrapGame(viewModel, fakeSupport(fakeRenderer))
        waitForIdle()

        // Initially 3D board is shown, 2D is not
        assertTrue(onAllNodesWithTag("board_3d").fetchSemanticsNodes().isNotEmpty())
        assertTrue(onAllNodesWithTag("chess_board").fetchSemanticsNodes().isEmpty())

        // Verify renderer was attached
        assertEquals(1, fakeRenderer.events.count { it == "attach" })

        // Toggle 3D off (via the setting → GameScreen's reactive bridge)
        appSettings.setBoard3DEnabled(false)
        waitForIdle()

        // 2D board should be shown, 3D board should NOT be shown
        assertTrue(onAllNodesWithTag("chess_board").fetchSemanticsNodes().isNotEmpty())
        assertTrue(onAllNodesWithTag("board_3d").fetchSemanticsNodes().isEmpty())

        // Verify renderer was detached and disposed
        assertEquals(1, fakeRenderer.events.count { it == "detach" })
        assertEquals(1, fakeRenderer.events.count { it == "dispose" })

        // Toggle 3D on again
        appSettings.setBoard3DEnabled(true)
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
        val appSettings = wrapGame(viewModel, board3DSupport)
        waitForIdle()

        // Toggle 3D on (the setting starts true; force a flip-flop so the enter effect re-fires
        // against the failing factory).
        appSettings.setBoard3DEnabled(false)
        waitForIdle()
        appSettings.setBoard3DEnabled(true)
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
        val viewModel = GameViewModel()

        wrapGame(viewModel, fakeSupport(fakeRenderer))
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

