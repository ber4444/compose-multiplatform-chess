package com.example.myapplication.opening

import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
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
                    isFallback = false,
                ),
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
}
