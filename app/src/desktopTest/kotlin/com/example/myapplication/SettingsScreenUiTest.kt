package com.example.myapplication

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.runComposeUiTest
import com.example.myapplication.persistence.AppSettings
import com.example.myapplication.persistence.LocalAppSettings
import com.example.myapplication.persistence.MapSettings
import kotlin.test.Test
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class SettingsScreenUiTest {

    @Test
    fun settingsScreenDoesNotShowAVisibleBackButton() = runComposeUiTest {
        val appSettings = AppSettings(MapSettings())

        setContent {
            CompositionLocalProvider(LocalAppSettings provides appSettings) {
                SettingsScreen(onBack = {})
            }
        }
        waitForIdle()

        assertTrue(
            onAllNodesWithText("Back").fetchSemanticsNodes().isEmpty(),
            "Settings is dismissed by system back/swipe, so it should not render its own Back button"
        )
    }
}
