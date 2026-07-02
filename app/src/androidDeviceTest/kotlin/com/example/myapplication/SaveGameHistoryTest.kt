package com.example.myapplication

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.myapplication.persistence.GameHistoryRepository
import com.example.myapplication.share.PgnSharer
import com.russhwolf.settings.SharedPreferencesSettings
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Phase 3 instrumented test: the end-to-end Save-game → History flow on the real Android
 * `SharedPreferences` backend. Forces a finished game (WinState.WHITE, no engine — deterministic),
 * taps "Save game", navigates to History, asserts one row with the right result, then Delete and
 * asserts empty. `PgnSharer` is a no-op fake so the Share button is present/clickable without
 * launching a real chooser.
 */
@RunWith(AndroidJUnit4::class)
class SaveGameHistoryTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val fakeSharer = PgnSharer { _, _ -> /* no-op for tests */ }

    private fun newRepo(): GameHistoryRepository {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val prefs = context.getSharedPreferences("chess_history_test_${System.nanoTime()}", android.content.Context.MODE_PRIVATE)
        return GameHistoryRepository(SharedPreferencesSettings(prefs))
    }

    @Test
    fun saveGameAppearsInHistoryAndCanBeDeleted() {
        val repo = newRepo()
        val viewModel = GameViewModel(GameUiState(winState = WinState.WHITE))

        // Render the game screen with the game-over popup, the history repo, and the fake sharer.
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

        // Open the History screen directly (bypassing AppRoot nav for test isolation).
        composeTestRule.setContent {
            GameHistoryScreen(gameHistory = repo, pgnSharer = fakeSharer, onBack = {})
        }
        composeTestRule.waitForIdle()

        // The saved row is present.
        composeTestRule.onNodeWithText("Result: 1-0", substring = true).assertIsDisplayed()

        // Delete it.
        composeTestRule.onNodeWithTag("history_delete_${repo.games.value.first().id}").performClick()
        composeTestRule.waitForIdle()
        assertEquals(0, repo.games.value.size)

        // Empty state now shows.
        composeTestRule.onNodeWithTag("history_empty").assertIsDisplayed()
    }
}
