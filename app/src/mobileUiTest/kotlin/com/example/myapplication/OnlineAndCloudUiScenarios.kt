package com.example.myapplication

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import com.example.myapplication.multiplayer.LichessAccountEvent
import com.example.myapplication.multiplayer.LichessAuthorization
import com.example.myapplication.multiplayer.LichessBoardApi
import com.example.myapplication.multiplayer.LichessClock
import com.example.myapplication.multiplayer.LichessGameEvent
import com.example.myapplication.multiplayer.LichessGameFull
import com.example.myapplication.multiplayer.LichessGameState
import com.example.myapplication.multiplayer.LichessOngoingGame
import com.example.myapplication.multiplayer.LichessPlayer
import com.example.myapplication.multiplayer.LichessProfile
import com.example.myapplication.multiplayer.LichessSession
import com.example.myapplication.multiplayer.LichessVariant
import com.example.myapplication.multiplayer.OnlineMatchController
import com.example.myapplication.multiplayer.OnlineMatchReference
import com.example.myapplication.multiplayer.OnlineScreen
import com.example.myapplication.persistence.SavedGame
import com.example.myapplication.persistence.cloud.CloudIdentity
import com.example.myapplication.persistence.cloud.CloudSaveHistory
import com.example.myapplication.persistence.cloud.CloudSaveJournal
import com.example.myapplication.persistence.cloud.CloudSaveSettings
import com.example.myapplication.persistence.cloud.CloudSaveSync
import com.example.myapplication.persistence.cloud.CloudSaveTransport
import com.example.myapplication.persistence.cloud.FirebaseSession
import com.example.myapplication.persistence.cloud.HistoryWrite
import com.example.myapplication.persistence.cloud.ResumeWrite
import com.example.myapplication.ui.theme.MyApplicationTheme
import com.example.saveapi.CloudAccount
import com.example.saveapi.CloudHistoryItem
import com.example.saveapi.CloudHistoryPage
import com.example.saveapi.CloudSavedGame
import com.russhwolf.settings.Settings
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.receiveAsFlow

@OptIn(ExperimentalTestApi::class)
internal fun ComposeUiTest.playRecoveredOnlineMatch() {
    val server = MatchServer()
    setContent {
        val scope = rememberCoroutineScope()
        val controller = remember(scope) {
            OnlineMatchController(
                scope, MatchIdentity, { server }, savedMatch = OnlineMatchReference(MATCH_ID, "alice"),
            )
        }
        val state by controller.state.collectAsState()
        MyApplicationTheme {
            OnlineScreen(state, controller, board3D = null, use3D = false, history = null, sharer = null, onBack = {})
        }
    }

    onNodeWithText("Sign in to reconnect to your match.").assertIsDisplayed()
    onNodeWithText("Return to solo").assertDoesNotExist()
    onNodeWithTag("lichess_sign_in").performClick()
    onNodeWithText("Your move").performScrollTo().assertIsDisplayed()
    onNodeWithText("Return to solo").assertDoesNotExist()
    onNodeWithTag("hint_button").assertDoesNotExist()
    onNodeWithTag("open_chat_button").assertDoesNotExist()
    onNodeWithTag("open_rules_button").assertDoesNotExist()

    onNodeWithTag("board_square_WhitePiece_6_4").performScrollTo().performClick()
    onNodeWithTag("board_square_PossibleMove_4_4").performClick()
    onNodeWithText("Waiting for Lichess…").assertExists()
    onNodeWithTag("board_square_WhitePiece_4_4").assertDoesNotExist()

    runOnIdle { server.confirmMove() }
    onNodeWithText("Opponent’s move").assertExists()
    onNodeWithTag("board_square_WhitePiece_4_4").assertExists()
}

@OptIn(ExperimentalTestApi::class)
internal fun ComposeUiTest.importOnlySoloGamesAndHideHistoryOnSignOut(settings: Settings) {
    val sync = CloudSaveSync(CloudIdentityStub, SoloHistoryServer(), CloudSaveJournal(settings), true, false)
    val solo = SavedGame("solo", 1, "1-0", "Solo player", "Computer", 1, "1. e4 1-0")
    val online = solo.copy(id = "online", white = "Online player", black = "Opponent", lichessGameId = MATCH_ID)
    try {
        setContent {
            MyApplicationTheme {
                SubScreenScaffold("Cloud saves", onBack = {}) {
                    CloudSaveSettings(sync, listOf(solo, online), onRestore = { error("Resume is disabled") })
                    CloudSaveHistory(sync, onOpen = {})
                }
            }
        }

        onNodeWithText("Sign in", substring = false).assertIsNotEnabled()
        onNodeWithText("Cloud solo games").assertDoesNotExist()
        onNodeWithText("Email").performTextInput("alice@example.test")
        onNodeWithText("Password").performTextInput("test-password")
        onNodeWithText("Sign in").performScrollTo().performClick()
        onNodeWithText("Signed in as alice@example.test").assertExists()

        onNodeWithText("Import local solo history").performScrollTo().performClick()
        onNodeWithText("Solo player vs Computer · 1-0").performScrollTo().assertIsDisplayed()
        onNodeWithText("Online player vs Opponent · 1-0", substring = true).assertDoesNotExist()

        onNodeWithText("Sign out").performScrollTo().performClick()
        onNodeWithText("Sign in", substring = false).assertIsNotEnabled()
        onNodeWithText("Cloud solo games").assertDoesNotExist()
        onNodeWithText("Solo player vs Computer · 1-0").assertDoesNotExist()
    } finally {
        sync.close()
    }
}

private const val MATCH_ID = "game0001"

private object MatchIdentity : LichessAuthorization {
    override suspend fun authorize() = LichessSession(LichessProfile("alice", "Alice"), "test-token")
    override suspend fun revoke(session: LichessSession) = error("Unexpected revoke")
}

private class MatchServer : LichessBoardApi {
    private val white = LichessPlayer(id = "alice", name = "Alice")
    private val black = LichessPlayer(id = "bob", name = "Bob")
    private val updates = Channel<LichessGameEvent>(Channel.UNLIMITED)
    private val submitted = Channel<String>(Channel.UNLIMITED)

    override fun accountEvents() = flow<LichessAccountEvent> {
        emit(LichessAccountEvent.Connected)
        awaitCancellation()
    }

    override suspend fun ongoingGames() = emptyList<LichessOngoingGame>()

    override fun gameEvents(gameId: String) = flow<LichessGameEvent> {
        check(gameId == MATCH_ID)
        emit(LichessGameEvent.Full(LichessGameFull(
            MATCH_ID, LichessVariant("standard"), "rapid", false, 0, white, black, "startpos",
            LichessClock(900_000, 10_000), position(""),
        )))
        updates.receiveAsFlow().collect { emit(it) }
    }

    override suspend fun submitMove(gameId: String, uci: String) {
        check(gameId == MATCH_ID)
        submitted.send(uci)
    }

    fun confirmMove() {
        check(submitted.tryReceive().getOrNull() == "e2e4")
        check(updates.trySend(LichessGameEvent.Changed(position("e2e4"))).isSuccess)
    }

    override fun challenge(username: String) = error("Unexpected challenge")
    override suspend fun answerChallenge(id: String, accept: Boolean) = error("Unexpected challenge answer")
    override suspend fun resign(gameId: String) = error("Unexpected resignation")
    override suspend fun abort(gameId: String) = error("Unexpected abort")
    override suspend fun offerDraw(gameId: String, accept: Boolean) = error("Unexpected draw offer")

    private fun position(moves: String) = LichessGameState(moves, 900_000, 900_000, 10_000, 10_000, "started")
}

private object CloudIdentityStub : CloudIdentity {
    override suspend fun signIn(email: String, password: String): FirebaseSession {
        check(email == "alice@example.test" && password == "test-password")
        return FirebaseSession("alice", "test-id-token", "test-refresh-token", Long.MAX_VALUE, true)
    }

    override suspend fun refreshIfNeeded(session: FirebaseSession) = session
    override suspend fun register(email: String, password: String) = error("Unexpected registration")
    override suspend fun resetPassword(email: String) = error("Unexpected reset")
    override suspend fun sendVerification(session: FirebaseSession) = error("Unexpected verification")
}

private class SoloHistoryServer : CloudSaveTransport {
    private val games = mutableMapOf<String, CloudSavedGame>()

    override suspend fun account(token: String) = CloudAccount("alice")
    override suspend fun history(token: String, cursor: String?) = CloudHistoryPage(games.values.map {
        CloudHistoryItem(it.id, it.savedAtEpochMillis, it.result, it.white, it.black, it.moveCount)
    })

    override suspend fun writeHistory(token: String, write: HistoryWrite) {
        games[write.id] = checkNotNull(write.game)
    }

    override suspend fun game(token: String, id: String) = error("Unexpected history open")
    override suspend fun current(token: String) = error("Resume is disabled")
    override suspend fun writeResume(token: String, write: ResumeWrite) = error("Resume is disabled")
    override suspend fun deleteAccount(token: String) = error("Unexpected account deletion")
}
