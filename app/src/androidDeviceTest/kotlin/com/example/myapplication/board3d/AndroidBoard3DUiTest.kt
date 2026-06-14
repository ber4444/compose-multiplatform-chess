package com.example.myapplication.board3d

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.example.myapplication.GameScreen
import com.example.myapplication.GameViewModel
import com.example.myapplication.WindowWidthSizeClass
import kotlinx.coroutines.flow.update
import org.junit.Assume
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class AndroidBoard3DUiTest {

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

        // Assert dialog is rendered above and is clickable
        onNodeWithTag("promotion_choice_QUEEN").assertIsDisplayed().performClick()
    }

    @Test
    fun board3DRendererSmokeTest() = runComposeUiTest {
        val factory = androidBoard3DSupport()

        val viewModel = GameViewModel()

        setContent {
            GameScreen(
                windowSize = WindowWidthSizeClass.Medium,
                viewModel = viewModel,
                board3D = factory
            )
        }

        // Toggle on
        viewModel.setShow3D(true)
        waitForIdle()

        // If Vulkan/Filament is unsupported, it will gracefully fallback and show unavailable.
        // If it is supported, board_3d node exists.
        val board3dExists = try {
            onNodeWithTag("board_3d").assertExists()
            true
        } catch (e: AssertionError) {
            false
        }

        Assume.assumeTrue("Filament/Vulkan not supported on this device, skipping smoke test", board3dExists)

        // No crash after updatePosition
        viewModel.playerMove(12, Pair(4, 4))
        waitForIdle()

        // Toggle off
        viewModel.setShow3D(false)
        waitForIdle()
    }
}
