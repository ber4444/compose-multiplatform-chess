package com.example.myapplication.multiplayer

import com.example.myapplication.Set as Side
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class OnlineMatchControllerTest {
    @Test
    fun immediateAcceptAndLostMoveAcknowledgmentAreAuthoritative() = runTest {
        val board = FakeBoard()
        val references = mutableListOf<OnlineMatchReference?>()
        val controller = OnlineMatchController(backgroundScope, FakeAuth, { board }, persistMatch = { references += it })
        controller.signIn()
        runCurrent()
        controller.challenge("Black")
        assertTrue(controller.state.value.assistanceBlocked)
        runCurrent()
        assertEquals(OnlineMatchReference(ID, "white"), references.last())
        board.games.send(LichessGameEvent.Full(game("started")))
        runCurrent()
        assertEquals(Side.WHITE, controller.state.value.game?.playerSide)
        assertEquals(OnlineMatchReference(ID, "white"), references.last())

        controller.submitMove(6 to 4, 4 to 4)
        controller.submitMove(6 to 3, 4 to 3)
        runCurrent()
        assertEquals(listOf("e2e4"), board.moves)
        assertTrue(controller.state.value.assistanceBlocked)
        board.games.send(LichessGameEvent.Full(game("started", "e2e4")))
        runCurrent()
        assertEquals(listOf("e4"), controller.state.value.game?.board?.moveHistory?.map { it.san })
        assertEquals(1, board.moves.size)

        board.games.send(LichessGameEvent.Changed(position("outoftime", "e2e4", "white")))
        runCurrent()
        val game = assertNotNull(controller.state.value.game)
        assertEquals(Side.WHITE, game.state.winnerSide)
        assertTrue(game.state.hasResult)
        assertFalse(controller.state.value.assistanceBlocked)
        assertEquals(null, references.last())
        controller.close()
    }

    @Test
    fun acceptedChallengeStillOpensWhenCancelRacesWithGameStart() = runTest {
        val board = FakeBoard(immediateAccept = false)
        val controller = OnlineMatchController(backgroundScope, FakeAuth, { board })
        controller.signIn()
        runCurrent()
        controller.challenge("Black")
        runCurrent()
        controller.cancelChallenge()
        board.events.send(LichessAccountEvent.GameStarted(
            LichessOngoingGame(ID, "white", LichessPlayer(id = "black"), LichessVariant("standard"), "rapid"),
        ))
        runCurrent()
        assertTrue(controller.state.value.assistanceBlocked)
        board.games.send(LichessGameEvent.Full(game("started")))
        runCurrent()
        assertEquals(ID, controller.state.value.game?.id)
        controller.close()
    }

    @Test
    fun persistedMatchKeepsGateThroughWrongAccountAndTakebackReplays() = runTest {
        val board = FakeBoard()
        val controller = OnlineMatchController(
            backgroundScope, FakeAuth, { board }, savedMatch = OnlineMatchReference(ID, "white"),
        )
        assertTrue(controller.state.value.assistanceBlocked)
        controller.signIn()
        runCurrent()
        board.games.send(LichessGameEvent.Full(game("started", "e2e4 e7e5")))
        runCurrent()
        assertEquals(2, controller.state.value.game?.board?.moveHistory?.size)
        board.games.send(LichessGameEvent.Changed(position("started", "d2d4")))
        runCurrent()
        assertEquals(listOf("d4"), controller.state.value.game?.board?.moveHistory?.map { it.san })
        board.games.send(LichessGameEvent.Changed(position("draw", "d2d4")))
        runCurrent()
        assertTrue(controller.state.value.game?.state?.hasResult == true)
        controller.close()
    }

    private class FakeBoard(private val immediateAccept: Boolean = true) : LichessBoardApi {
        val games = Channel<LichessGameEvent>(Channel.UNLIMITED)
        val events = Channel<LichessAccountEvent>(Channel.UNLIMITED)
        val moves = mutableListOf<String>()

        override fun accountEvents(): Flow<LichessAccountEvent> = flow {
            emit(LichessAccountEvent.Connected)
            for (event in events) emit(event)
        }
        override fun gameEvents(gameId: String): Flow<LichessGameEvent> = games.receiveAsFlow()
        override suspend fun ongoingGames() = emptyList<LichessOngoingGame>()
        override fun challenge(username: String): Flow<ChallengeEvent> = flow {
            if (!immediateAccept) {
                emit(ChallengeEvent(id = ID))
                kotlinx.coroutines.awaitCancellation()
            }
            events.send(LichessAccountEvent.GameStarted(
                LichessOngoingGame(ID, "white", LichessPlayer(id = "black"), LichessVariant("standard"), "rapid"),
            ))
            emit(ChallengeEvent(id = ID, done = "accepted"))
        }
        override suspend fun answerChallenge(id: String, accept: Boolean) = Unit
        override suspend fun submitMove(gameId: String, uci: String) {
            moves += uci
            throw LichessException("Lost acknowledgement.")
        }
        override suspend fun resign(gameId: String) = Unit
        override suspend fun abort(gameId: String) = Unit
        override suspend fun offerDraw(gameId: String, accept: Boolean) = Unit
    }

    private object FakeAuth : LichessAuthorization {
        override suspend fun authorize() = testSession
        override suspend fun revoke(session: LichessSession) = Unit
    }

    private companion object {
        const val ID = "game0001"

        fun position(status: String, moves: String = "", winner: String? = null) =
            LichessGameState(moves, 900_000, 900_000, 10_000, 10_000, status, winner)

        fun game(status: String, moves: String = "") = LichessGameFull(
            ID, LichessVariant("standard"), "rapid", false, 0,
            LichessPlayer(id = "white"), LichessPlayer(id = "black"), "startpos",
            LichessClock(900_000, 10_000), position(status, moves),
        )
    }
}
