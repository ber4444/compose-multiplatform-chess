package com.example.myapplication

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.example.myapplication.explorer.ExplorerPosition
import com.example.myapplication.explorer.HumanMove
import com.example.myapplication.explorer.HumanMoveExplorer
import com.example.myapplication.explorer.HumanMovePredictor
import com.example.myapplication.explorer.HumanMoveSource
import com.example.myapplication.explorer.HumanMoveStats
import com.example.myapplication.explorer.LichessOpeningExplorer
import com.example.myapplication.explorer.RatingBand
import com.example.myapplication.explorer.forReview
import com.example.myapplication.persistence.GameHistoryRepository
import com.example.myapplication.persistence.SavedGame
import com.example.myapplication.ui.theme.MyApplicationTheme
import com.russhwolf.settings.Settings

/**
 * History → game detail → "What players at this level play". Lichess answers the opening ply; the
 * next position has no explorer games, so Maia answers and the panel says why; changing the band
 * re-asks both sources at the new rating.
 */
@OptIn(ExperimentalTestApi::class)
internal fun ComposeUiTest.exploreHumanMovesFromHistory(settings: Settings) {
    val history = GameHistoryRepository(settings).apply { add(REVIEWED_GAME) }
    val lichess = ScriptedLichess()
    val explorer = HumanMoveExplorer(lichess, ScriptedMaia(), lichessToken = { "test-token" })
    setContent {
        MyApplicationTheme {
            GameHistoryScreen(
                gameHistory = history,
                onBack = {},
                humanMoves = explorer.forReview(assistanceBlocked = false),
                humanMovesBand = RatingBand.R1400,
            )
        }
    }

    onNodeWithTag("history_view_${REVIEWED_GAME.id}").performClick()
    onNodeWithTag("human_moves_panel").performScrollTo().assertIsDisplayed()
    onNodeWithTag("human_moves_prev").assertIsNotEnabled()
    onNodeWithTag("human_moves_position").assertTextContains("1. played e4")
    awaitSource()
    onNodeWithTag("human_moves_source").assertTextContains("Lichess games, 1400–1599", substring = true)
    onNodeWithText("e4 (played)").assertExists()
    onNodeWithTag("human_moves_note").assertDoesNotExist()

    onNodeWithTag("human_moves_next").performClick()
    onNodeWithTag("human_moves_position").assertTextContains("1… played e5")
    awaitSource()
    onNodeWithTag("human_moves_source").assertTextContains("Maia-3 prediction for players rated about 1500")
    onNodeWithTag("human_moves_note").assertTextContains("Too few Lichess games at 1400–1599 reach this position.")
    onNodeWithTag("human_moves_played_absent").assertExists()

    onNodeWithTag("human_moves_band_R1800").performScrollTo().performClick()
    awaitSource()
    onNodeWithTag("human_moves_source").performScrollTo()
        .assertTextContains("Maia-3 prediction for players rated about 1900")
    runOnIdle { check(lichess.bands.last() == RatingBand.R1800) { "Lichess was not re-asked at the new band" } }
}

/** No source can answer, or a Lichess game is live: the detail shows no human-moves panel at all. */
@OptIn(ExperimentalTestApi::class)
internal fun ComposeUiTest.hideHumanMovesWithoutASourceOrDuringOnlinePlay(settings: Settings) {
    val history = GameHistoryRepository(settings).apply { add(REVIEWED_GAME) }
    val signedOut = HumanMoveExplorer(ScriptedLichess(), maia = null, lichessToken = { null })
    val withMaia = HumanMoveExplorer(lichess = null, maia = ScriptedMaia(), lichessToken = { null })
    var assistanceBlocked by mutableStateOf(true)
    var explorer by mutableStateOf(withMaia)
    setContent {
        MyApplicationTheme {
            GameHistoryScreen(
                gameHistory = history,
                onBack = {},
                humanMoves = explorer.forReview(assistanceBlocked),
            )
        }
    }
    onNodeWithTag("history_view_${REVIEWED_GAME.id}").performClick()
    onNodeWithTag("history_detail_pgn").assertExists()
    onNodeWithTag("human_moves_panel").assertDoesNotExist()
    runOnIdle {
        assistanceBlocked = false
        explorer = signedOut
    }
    onNodeWithTag("human_moves_panel").assertDoesNotExist()
}

@OptIn(ExperimentalTestApi::class)
private fun ComposeUiTest.awaitSource() = waitUntil(timeoutMillis = 5_000) {
    onAllNodesWithTag("human_moves_source").fetchSemanticsNodes().isNotEmpty()
}

private class ScriptedLichess : LichessOpeningExplorer {
    val bands = mutableListOf<RatingBand>()

    override suspend fun lookup(fen: String, band: RatingBand, token: String): HumanMoveStats {
        bands += band
        val opening = fen.startsWith("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w")
        return HumanMoveStats(
            HumanMoveSource.LICHESS, band,
            if (opening) listOf(HumanMove("e2e4", "e4", 0.48, 4800), HumanMove("d2d4", "d4", 0.35, 3500)) else emptyList(),
            totalGames = if (opening) 10_000 else 0,
        )
    }
}

private class ScriptedMaia : HumanMovePredictor {
    override suspend fun predict(position: ExplorerPosition, band: RatingBand) =
        HumanMoveStats(HumanMoveSource.MAIA, band, listOf(HumanMove("c7c5", "c5", 0.41), HumanMove("e7e6", "e6", 0.2)))

    override fun close() = Unit
}

private val REVIEWED_GAME = SavedGame(
    id = "reviewed", savedAtEpochMillis = 1, result = "1-0", white = "You", black = "Computer", moveCount = 3,
    pgn = "1. e4 e5 2. Nf3 1-0",
    moveRecords = listOf(
        MoveRecord("e2e4", "e4", "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq - 0 1"),
        MoveRecord("e7e5", "e5", "rnbqkbnr/pppp1ppp/8/4p3/4P3/8/PPPP1PPP/RNBQKBNR w KQkq - 0 2"),
        MoveRecord("g1f3", "Nf3", "rnbqkbnr/pppp1ppp/8/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R b KQkq - 1 2"),
    ),
)
