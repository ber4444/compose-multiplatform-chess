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

/**
 * Tests for the GameScreen 3D wiring.
 *
 * NOTE: these use [fakeBoard3DSupport] (a plain Compose [androidx.compose.foundation.layout.Box]
 * surface), NOT the real [androidBoard3DSupport]. The real surface hosts a live SceneView whose
 * render loop drives the Compose frame clock every frame, so `waitForIdle()` — and every finder /
 * assertion that calls it internally — never returns (`ComposeNotIdleException`, a documented
 * SceneView/Compose-test limitation). The fake keeps the toggle / board_3d tag / selection routing
 * testable; actual GPU rendering of the SceneView board is verified manually (adb screenshots) and
 * by the desktop/iOS render paths.
 */
@OptIn(ExperimentalTestApi::class)
class AndroidBoard3DUiTest {

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
     * @Ignore: this must use the REAL [androidBoard3DSupport] (the occlusion guarantee is a property
     * of the actual SurfaceView), but a live SceneView never lets the Compose test clock go idle, so
     * `runComposeUiTest` hangs (see class KDoc). Structurally a Compose [androidx.compose.ui.window.Dialog]
     * renders in a separate window above the activity content, and SurfaceType.Surface is NOT
     * z-ordered on top, so it cannot occlude the dialog. Re-verify manually if SurfaceType ever
     * changes; left here as executable documentation.
     */
    @org.junit.Ignore("Live SceneView never idles -> runComposeUiTest hangs; dialog-above-Surface verified manually (see KDoc).")
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
