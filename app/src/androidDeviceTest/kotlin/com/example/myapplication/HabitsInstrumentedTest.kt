package com.example.myapplication

import android.content.Context
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.russhwolf.settings.Settings
import com.russhwolf.settings.SharedPreferencesSettings
import org.junit.Test
import org.junit.runner.RunWith

/** Android half of [HabitsUiScenarios][showHabitsFromPreviousSession]; Back is the system gesture. */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class HabitsInstrumentedTest {
    @Test
    fun habitsFromPreviousSessionRenderAndSystemBackReturnsToGame() = withSettings { settings ->
        runComposeUiTest { showHabitsFromPreviousSession(settings, navigateBack = { Espresso.pressBack() }) }
    }

    @Test
    fun habitAppearsWhenAnotherGameIsSaved() = withSettings { settings ->
        runComposeUiTest { habitAppearsWhenAnotherGameIsSaved(settings) }
    }

    private fun withSettings(block: (Settings) -> Unit) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "habits-ui-test-${System.nanoTime()}"
        try {
            block(SharedPreferencesSettings(context.getSharedPreferences(name, Context.MODE_PRIVATE)))
        } finally {
            context.deleteSharedPreferences(name)
        }
    }
}
