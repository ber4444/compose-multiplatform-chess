package com.example.myapplication

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.example.myapplication.explorer.RatingBand
import com.example.myapplication.persistence.AppSettings
import com.example.myapplication.persistence.LocalAppSettings
import com.example.myapplication.persistence.Opponent
import com.example.myapplication.ui.theme.MyApplicationTheme
import com.russhwolf.settings.Settings

/**
 * Settings → Opponent. Choosing Maia-3 swaps Stockfish's difficulty list for Maia's rating bands,
 * and both choices persist. Switching back restores the difficulty list.
 */
@OptIn(ExperimentalTestApi::class)
internal fun ComposeUiTest.chooseMaiaAsTheOpponent(settings: Settings) {
    val appSettings = AppSettings(settings)
    setContent {
        MyApplicationTheme {
            CompositionLocalProvider(LocalAppSettings provides appSettings) {
                SettingsScreen(onBack = {}, maiaAvailable = true)
            }
        }
    }

    onNodeWithTag("settings_opponent_stockfish").assertIsSelected()
    onNodeWithTag("settings_difficulty_MEDIUM").assertExists()
    onNodeWithTag("settings_maia_band_R1400").assertDoesNotExist()

    onNodeWithTag("settings_opponent_maia").performClick()
    onNodeWithTag("settings_difficulty_MEDIUM").assertDoesNotExist()
    onNodeWithTag("settings_maia_note").assertExists()
    onNodeWithTag("settings_maia_band_R1400").assertIsSelected()
    onNodeWithTag("settings_maia_band_R1800").performScrollTo().performClick()
    runOnIdle {
        check(appSettings.opponent.value == Opponent.MAIA) { "opponent not persisted" }
        check(AppSettings(settings).maiaBand.value == RatingBand.R1800) { "band not persisted" }
    }

    onNodeWithTag("settings_opponent_stockfish").performScrollTo().performClick()
    onNodeWithTag("settings_difficulty_MEDIUM").performScrollTo().assertExists()
    onNodeWithTag("settings_maia_band_R1800").assertDoesNotExist()
}

/** A build without Maia shows no Opponent choice, even with Maia left in persisted settings. */
@OptIn(ExperimentalTestApi::class)
internal fun ComposeUiTest.hideOpponentChoiceWithoutMaia(settings: Settings) {
    val appSettings = AppSettings(settings).apply { setOpponent(Opponent.MAIA) }
    setContent {
        MyApplicationTheme {
            CompositionLocalProvider(LocalAppSettings provides appSettings) {
                SettingsScreen(onBack = {}, maiaAvailable = false)
            }
        }
    }
    onNodeWithTag("settings_opponent_maia").assertDoesNotExist()
    onNodeWithTag("settings_maia_band_R1400").assertDoesNotExist()
    onNodeWithTag("settings_difficulty_MEDIUM").assertExists()
}
