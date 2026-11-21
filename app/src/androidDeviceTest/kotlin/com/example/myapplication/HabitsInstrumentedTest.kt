package com.example.myapplication

import android.content.Context
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.runAndroidComposeUiTest
import androidx.compose.ui.test.runComposeUiTest
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.russhwolf.settings.Settings
import com.russhwolf.settings.SharedPreferencesSettings
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Android half of [HabitsUiScenarios][showHabitsFromPreviousSession].
 *
 * Back goes through the host Activity's `OnBackPressedDispatcher`, which is what Compose's
 * `BackHandler` registers with, rather than `Espresso.pressBack()`. Espresso waits for the window to
 * hold focus, and a freshly launched test Activity on a loaded CI emulator sometimes never gets it
 * within the 10 s limit (`RootViewWithoutFocusException`); `SaveGameHistoryTest` hit the same race
 * with an injected `KEYCODE_BACK`.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class HabitsInstrumentedTest {
    @Test
    fun habitsFromPreviousSessionRenderAndSystemBackReturnsToGame() = withSettings { settings ->
        runAndroidComposeUiTest<ComponentActivity> {
            val host = checkNotNull(activity) { "runAndroidComposeUiTest launched no Activity" }
            showHabitsFromPreviousSession(
                settings,
                navigateBack = { runOnUiThread { host.onBackPressedDispatcher.onBackPressed() } },
            )
        }
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
