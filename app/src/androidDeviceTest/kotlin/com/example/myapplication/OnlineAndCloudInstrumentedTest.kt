package com.example.myapplication

import android.content.Context
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.russhwolf.settings.SharedPreferencesSettings
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class OnlineAndCloudInstrumentedTest {
    @Test
    fun recoveredOnlineMatchWaitsForServerMoveWithoutAssistance() = runComposeUiTest {
        playRecoveredOnlineMatch()
    }

    @Test
    fun cloudHistoryImportsOnlySoloGamesAndDisappearsOnSignOut() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "cloud-ui-test-${System.nanoTime()}"
        val settings = SharedPreferencesSettings(context.getSharedPreferences(name, Context.MODE_PRIVATE))
        try {
            runComposeUiTest { importOnlySoloGamesAndHideHistoryOnSignOut(settings) }
        } finally {
            context.deleteSharedPreferences(name)
        }
    }
}
