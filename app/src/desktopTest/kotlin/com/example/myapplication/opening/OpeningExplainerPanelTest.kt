package com.example.myapplication.opening

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.example.coachapi.OpeningExplainResponse
import com.example.myapplication.GameScreen
import com.example.myapplication.GameUiState
import com.example.myapplication.GameViewModel
import com.example.myapplication.LocalOpeningExplainerStateHolder
import com.example.myapplication.WinState
import com.example.myapplication.WindowWidthSizeClass
import com.example.ondeviceai.AiRoute
import com.example.ondeviceai.AiRoutePolicyDecider
import com.example.ondeviceai.OpeningExplainer
import com.example.ondeviceai.OpeningExplainerResult
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class OpeningExplainerPanelTest {
    @Test
    fun `ready panel shows explanation and source titles`() = runComposeUiTest {
        setContent {
            OpeningExplainerPanel(
                state = OpeningExplainerUiState.Ready(
                    text = "A central opening.",
                    sourceTitles = listOf("King's Pawn Game", "Development"),
                    route = AiRoute.Cloud,
                ),
                onRetry = {},
            )
        }

        onNodeWithTag("opening_explainer_text").assertTextContains(
            value = "A central opening",
            substring = true,
        )
        onNodeWithTag("opening_explainer_sources").assertTextContains(
            value = "King's Pawn Game",
            substring = true,
        )
    }

    @Test
    fun `game over retries a failed opening explanation`() = runComposeUiTest {
        val failureMessage = "The opening explanation took too long to load."
        var attempt = 0
        val holder = OpeningExplainerStateHolder(OpeningExplainer {
            if (++attempt == 1) {
                OpeningExplainerResult.Fallback(
                    OpeningExplainResponse(failureMessage, emptyList(), "offline-fallback"),
                    AiRoutePolicyDecider.FallbackReason.Timeout,
                )
            } else {
                OpeningExplainerResult.Success(
                    OpeningExplainResponse("A central opening.", emptyList(), "template-v1"),
                    AiRoute.Cloud,
                )
            }
        })
        val viewModel = GameViewModel(GameUiState(winState = WinState.WHITE))
        try {
            setContent {
                CompositionLocalProvider(LocalOpeningExplainerStateHolder provides holder) {
                    GameScreen(windowSize = WindowWidthSizeClass.Medium, viewModel = viewModel)
                }
            }

            onNodeWithText(failureMessage).assertExists()
            onNodeWithText("Retry").performClick()
            onNodeWithTag("opening_explainer_text").assertTextContains("A central opening.")
            onNodeWithText("Retry").assertDoesNotExist()
        } finally {
            viewModel.close()
            holder.close()
        }
    }
}
