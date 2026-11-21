package com.example.myapplication

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import kotlin.test.Test

/**
 * The web build hides the paid assistant, Chat (`AppRoot(aiAssistantsEnabled = false)` → a null
 * callback), keeps the free Rules Q&A (wasm answers from the bundled rulebook, so `AppRoot` still
 * passes its callback), and shows the mobile-app links under the board. Pins that configuration,
 * plus the default every other platform relies on: the buttons are there when the callbacks are.
 */
@OptIn(ExperimentalTestApi::class)
class AiAssistantEntryPointsUiTest {

    @Test
    fun chatAndRulesAreShownByDefault() = runComposeUiTest {
        val viewModel = GameViewModel()
        try {
            setContent { ChessApp(viewModel = viewModel) }

            onNodeWithTag("open_chat_button").assertExists()
            onNodeWithTag("open_rules_button").assertExists()
            onNodeWithTag("mobile_app_links").assertDoesNotExist()
        } finally {
            viewModel.close()
        }
    }

    @Test
    fun webConfigurationHidesChatKeepsRulesAndShowsTheStoreLinks() = runComposeUiTest {
        val viewModel = GameViewModel()
        try {
            setContent {
                ChessApp(
                    viewModel = viewModel,
                    onOpenRules = {},
                    onOpenChat = null,
                    belowBoard = { MobileAppLinks() },
                )
            }

            onNodeWithTag("open_chat_button").assertDoesNotExist()
            onNodeWithTag("open_rules_button").assertExists()
            onNodeWithTag("open_settings_button").assertExists()
            onNodeWithTag("mobile_app_links").assertIsDisplayed()
        } finally {
            viewModel.close()
        }
    }

    @Test
    fun nullCallbacksHideChatAndRulesAndTheSlotShowsTheStoreLinks() = runComposeUiTest {
        val viewModel = GameViewModel()
        try {
            setContent {
                ChessApp(
                    viewModel = viewModel,
                    onOpenRules = null,
                    onOpenChat = null,
                    belowBoard = { MobileAppLinks() },
                )
            }

            onNodeWithTag("open_chat_button").assertDoesNotExist()
            onNodeWithTag("open_rules_button").assertDoesNotExist()
            // Settings and History are not AI surfaces and must survive the web configuration.
            onNodeWithTag("open_settings_button").assertExists()
            onNodeWithTag("open_history_button").assertExists()
            onNodeWithTag("mobile_app_links").assertIsDisplayed()
        } finally {
            viewModel.close()
        }
    }
}
