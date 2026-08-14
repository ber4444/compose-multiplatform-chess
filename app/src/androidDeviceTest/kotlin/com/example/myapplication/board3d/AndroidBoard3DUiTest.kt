package com.example.myapplication.board3d

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.example.myapplication.GameScreen
import com.example.myapplication.GameViewModel
import com.example.myapplication.WindowWidthSizeClass
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Tests for the GameScreen 3D wiring.
 *
 * NOTE: most of these use [fakeBoard3DSupport] (a plain Compose
 * [androidx.compose.foundation.layout.Box] surface), NOT the real [androidBoard3DSupport] — they
 * are about the toggle / board_3d tag / selection routing, none of which needs a GPU. Actual
 * rendering of the SceneView board is still verified by adb screenshots and by the desktop/iOS
 * render paths.
 *
 * Until the `isRendering` gate landed, the fake was not a preference but the only option: a live
 * SceneView drove the Compose frame clock every frame, so `waitForIdle()` — and every finder /
 * assertion that calls it internally — never returned. A parked render loop suspends on a snapshot
 * rather than on `withFrameNanos`, so the frame-clock awaiter is gone and the test clock can idle;
 * [dialogRendersAboveSurfaceView] is the one case that needs the real surface and now runs.
 */
@OptIn(ExperimentalTestApi::class)
class AndroidBoard3DUiTest {

    @Test
    fun whiteAndBlackPiecesSelectDifferentGlTfMaterials() {
        val names = listOf("board", "white", "black")

        assertEquals("white", selectPieceMaterialName(names, PieceColor.WHITE))
        assertEquals("black", selectPieceMaterialName(names, PieceColor.BLACK))
    }

    @Test
    fun board3DRendererSmokeTest() = runComposeUiTest {
        val viewModel = GameViewModel()

        setContent {
            GameScreen(
                windowSize = WindowWidthSizeClass.Medium,
                viewModel = viewModel,
                board3D = fakeBoard3DSupport()
            )
        }

        // Toggle 3D on: the board surface (board_3d) replaces the 2D board.
        viewModel.setShow3D(true)
        waitForIdle()
        onNodeWithTag("board_3d").assertExists()

        // A move advancing the FEN must not crash the 3D path, nor must toggling back off.
        viewModel.playerMove(12, Pair(4, 4))
        waitForIdle()
        viewModel.setShow3D(false)
        waitForIdle()
        onNodeWithTag("chess_board").assertExists()
    }

    @Test
    fun selectionHighlightDoesNotCrash() = runComposeUiTest {
        val viewModel = GameViewModel()

        setContent {
            GameScreen(
                windowSize = WindowWidthSizeClass.Medium,
                viewModel = viewModel,
                board3D = fakeBoard3DSupport()
            )
        }

        viewModel.setShow3D(true)
        waitForIdle()
        onNodeWithTag("board_3d").assertExists()

        // Selecting a square routes setSelectedSquare to the renderer; must not crash.
        viewModel.updateSelected(Pair(6, 4)) // e2
        waitForIdle()
        onNodeWithTag("board_3d").assertExists()
    }

    /**
     * Verifies a Compose dialog (promotion) layers above the 3D board surface — the reason §5 uses
     * [SurfaceType.Surface] rather than a z-ordered-on-top SurfaceView.
     *
     * This must use the REAL [androidBoard3DSupport] — the occlusion guarantee is a property of the
     * actual SurfaceView, so the fake cannot stand in. It was `@Ignore`d because a live SceneView
     * never let the Compose test clock go idle: its render loop awaited `withFrameNanos`
     * unconditionally, which is a permanent frame-clock awaiter, so `waitForIdle()` never returned.
     * `AndroidBoard3DSurface` now passes `isRendering`, and a parked loop suspends on a snapshot
     * instead of the frame clock — which is exactly the awaiter going away. The test is enabled to
     * hold that: if the gate ever regresses to "always rendering", this stops idling again and goes
     * red rather than quietly costing a device its GPU.
     *
     * That it *executes* on CI was verified rather than assumed, because nothing in the emulator
     * log names individual tests and a green leg looks identical whether a test passed or never
     * ran. A temporary unconditional `fail()` appended after the assertions below (commit
     * `2b008d3`, reverted in `b199474`) turned the Android job red with
     * "MUTATION PROBE: dialogRendersAboveSurfaceView executed to completion" at
     * `AndroidBoard3DUiTest.kt:136`, and the run reported `23 tests, 0 skipped, 1 failed` — so the
     * body runs to the end, `waitForIdle()` returns, and the dialog is found and clicked.
     *
     * Structurally a Compose [androidx.compose.ui.window.Dialog] renders in a separate window above
     * the activity content, and SurfaceType.Surface is NOT z-ordered on top, so it cannot occlude
     * the dialog. Re-verify if SurfaceType ever changes.
     */
    @Test
    fun dialogRendersAboveSurfaceView() = runComposeUiTest {
        val viewModel = GameViewModel(
            gameState = com.example.myapplication.GameUiState(
                pendingPromotion = com.example.myapplication.PendingPromotion(
                    pieceIndex = 0,
                    from = Pair(1, 0),
                    to = Pair(0, 0)
                )
            )
        )

        setContent {
            GameScreen(
                windowSize = WindowWidthSizeClass.Medium,
                viewModel = viewModel,
                board3D = androidBoard3DSupport()
            )
        }

        viewModel.setShow3D(true)
        waitForIdle()

        onNodeWithTag("promotion_choice_QUEEN").assertIsDisplayed().performClick()
    }
}
