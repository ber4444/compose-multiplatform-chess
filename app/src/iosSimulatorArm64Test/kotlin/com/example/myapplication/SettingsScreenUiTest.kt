package com.example.myapplication

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.example.myapplication.persistence.AppSettings
import com.example.myapplication.persistence.LocalAppSettings
import com.example.myapplication.persistence.MapSettings
import kotlin.test.Test
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class SettingsScreenUiTest {

    @Test
    fun settingsBackButtonInvokesOnBack() = runComposeUiTest {
        val appSettings = AppSettings(MapSettings())
        var backPressed = false

        setContent {
            CompositionLocalProvider(LocalAppSettings provides appSettings) {
                SettingsScreen(onBack = { backPressed = true })
            }
        }
        onNodeWithText("Back").assertIsDisplayed().performClick()
        runOnIdle { assertTrue(backPressed) }
    }
}
