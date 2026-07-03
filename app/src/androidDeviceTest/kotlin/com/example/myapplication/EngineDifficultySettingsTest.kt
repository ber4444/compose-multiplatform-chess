package com.example.myapplication

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.myapplication.persistence.AppSettings
import com.example.myapplication.persistence.LocalAppSettings
import com.russhwolf.settings.SharedPreferencesSettings
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Phase 4 instrumented test: changing the engine difficulty in Settings persists the value. Uses the
 * real Android `SharedPreferences` backend; behavioural play strength is not asserted (too flaky).
 */
@RunWith(AndroidJUnit4::class)
class EngineDifficultySettingsTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun tappingADifficultyOptionPersistsIt() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val prefs = context.getSharedPreferences("chess_diff_test_${System.nanoTime()}", android.content.Context.MODE_PRIVATE)
        val appSettings = AppSettings(SharedPreferencesSettings(prefs))

        composeTestRule.setContent {
            CompositionLocalProvider(LocalAppSettings provides appSettings) {
                SettingsScreen(onBack = {}, board3D = null)
            }
        }
        composeTestRule.waitForIdle()

        // Default is MEDIUM; tap HARD and assert it persisted.
        assertEquals(EngineDifficulty.MEDIUM, appSettings.engineDifficulty.value)
        composeTestRule.onNodeWithTag("settings_difficulty_HARD").performClick()
        composeTestRule.waitForIdle()

        assertEquals(EngineDifficulty.HARD, appSettings.engineDifficulty.value)
        // A fresh AppSettings over the same backing reads the persisted HARD value.
        val reloaded = AppSettings(SharedPreferencesSettings(prefs))
        assertEquals(EngineDifficulty.HARD, reloaded.engineDifficulty.value)
    }
}
