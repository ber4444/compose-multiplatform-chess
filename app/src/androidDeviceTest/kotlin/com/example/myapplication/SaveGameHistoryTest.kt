package com.example.myapplication

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.myapplication.persistence.GameActions
import com.example.myapplication.persistence.GameHistoryRepository
import com.example.myapplication.share.PgnSharer
import com.russhwolf.settings.SharedPreferencesSettings
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Phase 3 instrumented tests for the Save-game → History flow on the real Android
 * `SharedPreferences` backend. `PgnSharer` is a no-op fake so the Share button is present/clickable
 * without launching a real chooser.
 *
 * Split into two tests because a Compose `createComposeRule` binds one `setContent` to its host
 * Activity for the test's lifetime — calling `setContent` twice ("Activity has already set content")
 * is illegal. Each test renders exactly one screen.
 *
 * Back navigation is driven through the host Activity's `OnBackPressedDispatcher` (hence the
 * [createAndroidComposeRule] over a [ComponentActivity]) rather than by injecting `KEYCODE_BACK`
 * through the system input pipeline. An injected key goes to whichever window holds focus, and the
 * freshly launched test Activity does not always hold it yet on a loaded CI emulator — the key was
 * then swallowed, the detail view stayed up, and `history_view_<id>` asserted "not displayed"
 * (observed on three unchanged nightly runs). The dispatcher is what Compose's `BackHandler`
 * registers with, so this exercises the same path minus the race.
 */
@RunWith(AndroidJUnit4::class)
class SaveGameHistoryTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private val fakeSharer = PgnSharer { _, _ -> /* no-op for tests */ }

    private fun newRepo(): GameHistoryRepository {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val prefs = context.getSharedPreferences("chess_history_test_${System.nanoTime()}", android.content.Context.MODE_PRIVATE)
        return GameHistoryRepository(SharedPreferencesSettings(prefs))
    }

    @Test
    fun tappingSaveGameButtonAddsTheFinishedGameToTheRepository() {
        val repo = newRepo()
        val viewModel = GameViewModel(GameUiState(winState = WinState.WHITE))

        composeTestRule.setContent {
            GameScreen(
                windowSize = WindowWidthSizeClass.Medium,
                viewModel = viewModel,
                gameHistory = repo,
                pgnSharer = fakeSharer,
            )
        }

        // The game-over popup is showing. Tap "Save game".
        composeTestRule.onNodeWithTag("save_game_button").performClick()
        composeTestRule.waitForIdle()

        // One saved game with the right result.
        assertEquals(1, repo.games.value.size)
        assertEquals("1-0", repo.games.value.first().result)
    }

    @Test
    fun savedGameAppearsInHistoryAndCanBeDeleted() {
        val repo = newRepo()
        // Seed the repo directly (no GameScreen) so this test has its own single setContent.
        val finished = GameUiState(winState = WinState.WHITE)
        val saved = GameActions.toSavedGame(finished, engineAttached = true, savedAtEpochMillis = 1_700_000_000_000L)
        repo.add(saved)

        composeTestRule.setContent {
            GameHistoryScreen(gameHistory = repo, pgnSharer = fakeSharer, onBack = {})
        }
        composeTestRule.waitForIdle()

        // The saved row is present.
        composeTestRule.onNodeWithText("Result: 1-0", substring = true).assertIsDisplayed()
        composeTestRule.onNodeWithText("Back").assertDoesNotExist()

        composeTestRule.onNodeWithTag("history_view_${saved.id}").performClick()
        composeTestRule.onNodeWithTag("history_detail_pgn").assertIsDisplayed()
        composeTestRule.onNodeWithText("Back").assertDoesNotExist()
        composeTestRule.runOnUiThread { composeTestRule.activity.onBackPressedDispatcher.onBackPressed() }
        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            composeTestRule.onAllNodesWithTag("history_view_${saved.id}").fetchSemanticsNodes().size == 1
        }
        composeTestRule.onNodeWithTag("history_view_${saved.id}").assertIsDisplayed()

        // Delete it.
        composeTestRule.onNodeWithTag("history_delete_${saved.id}").performClick()
        composeTestRule.waitForIdle()
        assertEquals(0, repo.games.value.size)

        // Empty state now shows.
        composeTestRule.onNodeWithTag("history_empty").assertIsDisplayed()
    }
}
