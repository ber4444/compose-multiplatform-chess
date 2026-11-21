package com.example.myapplication

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.example.myapplication.persistence.createSettings
import com.russhwolf.settings.Settings
import kotlin.test.Test
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/** iOS half of [HabitsUiScenarios][showHabitsFromPreviousSession]; Back is the top-bar button. */
@OptIn(ExperimentalTestApi::class, ExperimentalUuidApi::class)
class HabitsSimulatorTest {
    @Test
    fun habitsFromPreviousSessionRenderAndBackReturnsToGame() = withSettings { settings ->
        runComposeUiTest {
            showHabitsFromPreviousSession(settings, navigateBack = { onNodeWithText("Back").performClick() })
        }
    }

    @Test
    fun habitAppearsWhenAnotherGameIsSaved() = withSettings { settings ->
        runComposeUiTest { habitAppearsWhenAnotherGameIsSaved(settings) }
    }

    private fun withSettings(block: (Settings) -> Unit) {
        val settings = createSettings("habits-ui-test-${Uuid.random()}")
        try {
            block(settings)
        } finally {
            settings.clear()
        }
    }
}
