package com.example.myapplication

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import com.example.myapplication.persistence.createSettings
import com.russhwolf.settings.Settings
import kotlin.test.Test
import kotlin.uuid.Uuid

@OptIn(ExperimentalTestApi::class)
class HumanMovesSimulatorTest {
    @Test
    fun historyDetailExploresLichessThenFallsBackToMaia() = withSettings { exploreHumanMovesFromHistory(it) }

    @Test
    fun historyDetailHidesHumanMovesWithoutASourceOrDuringOnlinePlay() =
        withSettings { hideHumanMovesWithoutASourceOrDuringOnlinePlay(it) }

    @Test
    fun settingsDefaultsToMaiaUntilStockfishIsChosen() = withSettings { maiaIsTheDefaultOpponentUntilStockfishIsChosen(it) }

    @Test
    fun settingsHidesTheOpponentChoiceWithoutMaia() = withSettings { hideOpponentChoiceWithoutMaia(it) }

    private fun withSettings(block: ComposeUiTest.(Settings) -> Unit) {
        val settings = createSettings("human-moves-ui-test-${Uuid.random()}")
        try {
            runComposeUiTest { block(settings) }
        } finally {
            settings.clear()
        }
    }
}
