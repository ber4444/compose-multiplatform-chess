package com.example.myapplication

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.waitUntilExactlyOneExists
import com.example.myapplication.persistence.AppSettings
import com.example.myapplication.persistence.GameHistoryRepository
import com.example.myapplication.persistence.SavedGame
import com.russhwolf.settings.Settings

/**
 * Functional UI scenarios for cross-game habits (B6/RAG-5), compiled into both the Android device
 * tests and the iOS simulator tests (`src/mobileUiTest` is a shared source dir of both).
 *
 * They drive the real [AppRoot] over a real [GameHistoryRepository] persisted in platform
 * [Settings], so a pass covers what a unit test of `HabitAggregator` cannot: the Habits button is
 * reachable from the game screen, the saved `playerSide`/`hintUsed` fields survive a relaunch, the
 * screen recomposes when a game is saved, and the habit cards actually render. That last one is
 * not a formality — the screen used to nest a `verticalScroll` inside `SubScreenScaffold`'s own,
 * which throws at measure time, so it crashed whenever it had something to show.
 */

/** Habits from games saved in an earlier session render on the Habits screen, and Back returns. */
@OptIn(ExperimentalTestApi::class)
internal fun ComposeUiTest.showHabitsFromPreviousSession(
    settings: Settings,
    navigateBack: ComposeUiTest.() -> Unit,
) {
    // An earlier session saved these; reading them through a *new* repository is the relaunch.
    GameHistoryRepository(settings).apply { HabitFixtures.recurringHabits().forEach(::add) }
    val relaunched = GameHistoryRepository(settings)
    val appSettings = AppSettings(settings).apply { setBoard3DEnabled(false) }
    val viewModel = GameViewModel()
    try {
        setContent { AppRoot(viewModel = viewModel, settings = appSettings, gameHistory = relaunched) }

        onNodeWithTag("open_habits_button").assertIsDisplayed().performClick()
        waitUntilExactlyOneExists(hasTestTag("habit_card_0"), timeoutMillis = 5_000)

        // Tactical: the Black-side game counts only because playerSide survived the round trip.
        onNodeWithText("You've hung a piece in 2 of your last 3 games.").assertIsDisplayed()
        onNodeWithText("In your most recent game, 2...Nc6 — Nf6 kept the position level.")
            .assertIsDisplayed()
        // The practice position. It is listed twice (the blunder and a hinted move share it).
        onAllNodesWithText(HabitFixtures.BLACK_BLUNDER_FEN_BEFORE).onFirst().assertIsDisplayed()

        // Behavioral: only reachable if MoveRecord.hintUsed was persisted.
        onNodeWithTag("habit_card_1").performScrollTo().assertIsDisplayed()
        onNodeWithText("You relied on hints in 2 of your last 3 games.").assertIsDisplayed()
        onNodeWithText("BEHAVIORAL HABIT").assertIsDisplayed()

        navigateBack()
        waitUntilExactlyOneExists(hasTestTag("open_habits_button"), timeoutMillis = 5_000)
        onNodeWithText("You've hung a piece in 2 of your last 3 games.").assertDoesNotExist()
    } finally {
        viewModel.close()
    }
}

/** Too few games shows the empty state; saving one more game updates the open screen in place. */
@OptIn(ExperimentalTestApi::class)
internal fun ComposeUiTest.habitAppearsWhenAnotherGameIsSaved(settings: Settings) {
    val history = GameHistoryRepository(settings)
    val (first, second, third) = HabitFixtures.recurringHabits()
    history.add(first)
    history.add(second)
    val appSettings = AppSettings(settings).apply { setBoard3DEnabled(false) }
    val viewModel = GameViewModel()
    try {
        setContent { AppRoot(viewModel = viewModel, settings = appSettings, gameHistory = history) }

        onNodeWithTag("open_habits_button").performClick()
        onNodeWithTag("habits_empty").assertIsDisplayed()
        onNodeWithTag("habit_card_0").assertDoesNotExist()

        runOnIdle { history.add(third) }
        waitUntilExactlyOneExists(hasText("You've hung a piece in 2 of your last 3 games."), timeoutMillis = 5_000)
        onNodeWithTag("habits_empty").assertDoesNotExist()
    } finally {
        viewModel.close()
    }
}

internal object HabitFixtures {
    /** Position before Black's blunder in the Black-side game (after 1.e4 e5 2.Nf3). */
    const val BLACK_BLUNDER_FEN_BEFORE = "rnbqkbnr/pppp1ppp/8/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R b KQkq - 1 2"

    /**
     * Three games, oldest first (the repository lists newest first):
     * - a White-side game with a hung piece and two hinted moves,
     * - a clean White-side game,
     * - a Black-side game with a hung piece on Black's 2nd move and two hinted moves.
     *
     * So the hung-piece habit is "2 of 3" only when the Black game's player moves are read as
     * Black's, and the hint habit is "2 of 3" only when `hintUsed` is read back.
     */
    fun recurringHabits(): List<SavedGame> = listOf(
        game(
            id = "1", playerSide = "WHITE",
            MoveRecord("e2e4", "e4", AFTER_E4, hintUsed = true),
            MoveRecord("e7e5", "e5", AFTER_E5),
            MoveRecord("g1f3", "Nf3", AFTER_NF3, hintUsed = true),
            MoveRecord("b8c6", "Nc6", AFTER_NC6),
            MoveRecord("f3e5", "Nxe5", AFTER_NXE5, assessment = blunder(bestMoveSan = "Bb5")),
        ),
        game(
            id = "2", playerSide = "WHITE",
            MoveRecord("e2e4", "e4", AFTER_E4, assessment = best()),
            MoveRecord("e7e5", "e5", AFTER_E5),
        ),
        game(
            id = "3", playerSide = "BLACK",
            MoveRecord("e2e4", "e4", AFTER_E4),
            MoveRecord("e7e5", "e5", AFTER_E5, hintUsed = true),
            MoveRecord("g1f3", "Nf3", AFTER_NF3),
            MoveRecord("b8c6", "Nc6", AFTER_NC6, hintUsed = true, assessment = blunder(bestMoveSan = "Nf6")),
        ),
    )

    private const val AFTER_E4 = "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq e3 0 1"
    private const val AFTER_E5 = "rnbqkbnr/pppp1ppp/8/4p3/4P3/8/PPPP1PPP/RNBQKBNR w KQkq e6 0 2"
    private const val AFTER_NF3 = BLACK_BLUNDER_FEN_BEFORE
    private const val AFTER_NC6 = "r1bqkbnr/pppp1ppp/2n5/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R w KQkq - 2 3"
    private const val AFTER_NXE5 = "r1bqkbnr/pppp1ppp/2n5/4N3/4P3/8/PPPP1PPP/RNBQKB1R b KQkq - 0 3"

    private fun blunder(bestMoveSan: String) = MoveAssessment(
        cpBefore = 30, cpPlayed = -300, cpBest = 30, cpLoss = 330,
        moveClass = MoveClass.BLUNDER, motifs = listOf(MotifDetector.HANGS_PIECE), bestMoveSan = bestMoveSan,
    )

    private fun best() = MoveAssessment(
        cpBefore = 0, cpPlayed = 30, cpBest = 30, cpLoss = 0, moveClass = MoveClass.BEST, motifs = emptyList(),
    )

    private fun game(id: String, playerSide: String, vararg records: MoveRecord) = SavedGame(
        id = id,
        savedAtEpochMillis = id.toLong(),
        result = "*",
        white = if (playerSide == "WHITE") "Player" else "Stockfish",
        black = if (playerSide == "BLACK") "Player" else "Stockfish",
        moveCount = records.size,
        pgn = "",
        moveRecords = records.toList(),
        playerSide = playerSide,
    )
}
