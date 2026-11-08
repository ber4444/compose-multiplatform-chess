package com.example.myapplication

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import com.example.myapplication.persistence.createSettings
import kotlin.test.Test
import kotlin.uuid.Uuid

@OptIn(ExperimentalTestApi::class)
class OnlineAndCloudSimulatorTest {
    @Test
    fun recoveredOnlineMatchWaitsForServerMoveWithoutAssistance() = runComposeUiTest {
        playRecoveredOnlineMatch()
    }

    @Test
    fun cloudHistoryImportsOnlySoloGamesAndDisappearsOnSignOut() {
        val settings = createSettings("cloud-ui-test-${Uuid.random()}")
        try {
            runComposeUiTest { importOnlySoloGamesAndHideHistoryOnSignOut(settings) }
        } finally {
            settings.clear()
        }
    }
}
