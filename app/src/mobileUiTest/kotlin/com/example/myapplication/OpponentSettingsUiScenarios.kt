package com.example.myapplication

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.example.myapplication.persistence.AppSettings
import com.example.myapplication.persistence.LocalAppSettings
import com.example.myapplication.persistence.Opponent
import com.example.myapplication.ui.theme.MyApplicationTheme
import com.russhwolf.settings.Settings

/**
 * Settings → Opponent. Maia-3 is the default with nothing persisted; the difficulty list stays and
 * names the rating each level plays like. Picking Stockfish persists and drops those labels.
 */
@OptIn(ExperimentalTestApi::class)
internal fun ComposeUiTest.maiaIsTheDefaultOpponentUntilStockfishIsChosen(settings: Settings) {
    val appSettings = AppSettings(settings)
    setContent {
        MyApplicationTheme {
            CompositionLocalProvider(LocalAppSettings provides appSettings) {
                SettingsScreen(onBack = {}, maiaAvailable = true)
            }
        }
    }

    onNodeWithTag("settings_opponent_maia").assertIsSelected()
    onNodeWithTag("settings_maia_note").assertExists()
    onNodeWithTag("settings_difficulty_MEDIUM").assertIsSelected()
    onNodeWithText("Medium (plays like 1400–1599)").assertExists()
    onNodeWithTag("settings_difficulty_HARD").performScrollTo().performClick()
    runOnIdle { check(appSettings.engineDifficulty.value == EngineDifficulty.HARD) { "difficulty not applied" } }

    onNodeWithTag("settings_opponent_stockfish").performScrollTo().performClick()
    onNodeWithTag("settings_opponent_stockfish").assertIsSelected()
    onNodeWithTag("settings_maia_note").assertDoesNotExist()
    onNodeWithText("Hard").assertExists()
    onNodeWithText("Hard (plays like 1800–1999)").assertDoesNotExist()
    runOnIdle {
        check(AppSettings(settings).opponent.value == Opponent.STOCKFISH) { "Stockfish choice not persisted" }
    }
}

/** A build without Maia shows no Opponent choice and plain level names, even with Maia as the default. */
@OptIn(ExperimentalTestApi::class)
internal fun ComposeUiTest.hideOpponentChoiceWithoutMaia(settings: Settings) {
    val appSettings = AppSettings(settings)
    setContent {
        MyApplicationTheme {
            CompositionLocalProvider(LocalAppSettings provides appSettings) {
                SettingsScreen(onBack = {}, maiaAvailable = false)
            }
        }
    }
    onNodeWithTag("settings_opponent_maia").assertDoesNotExist()
    onNodeWithTag("settings_maia_note").assertDoesNotExist()
    onNodeWithText("Medium").assertExists()
}
