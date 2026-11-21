package com.example.myapplication

import android.content.Context
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.russhwolf.settings.Settings
import com.russhwolf.settings.SharedPreferencesSettings
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class HumanMovesInstrumentedTest {
    @Test
    fun historyDetailExploresLichessThenFallsBackToMaia() = withSettings { exploreHumanMovesFromHistory(it) }

    @Test
    fun historyDetailHidesHumanMovesWithoutASourceOrDuringOnlinePlay() =
        withSettings { hideHumanMovesWithoutASourceOrDuringOnlinePlay(it) }

    @Test
    fun settingsSwapsDifficultyForMaiaRatingsWhenMaiaIsTheOpponent() = withSettings { chooseMaiaAsTheOpponent(it) }

    @Test
    fun settingsHidesTheOpponentChoiceWithoutMaia() = withSettings { hideOpponentChoiceWithoutMaia(it) }

    private fun withSettings(block: ComposeUiTest.(Settings) -> Unit) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "human-moves-ui-test-${System.nanoTime()}"
        val settings = SharedPreferencesSettings(context.getSharedPreferences(name, Context.MODE_PRIVATE))
        try {
            runComposeUiTest { block(settings) }
        } finally {
            context.deleteSharedPreferences(name)
        }
    }
}
