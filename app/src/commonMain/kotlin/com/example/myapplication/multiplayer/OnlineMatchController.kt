package com.example.myapplication.multiplayer

import com.example.myapplication.GameUiState
import com.example.myapplication.PromotionType
import com.example.myapplication.Set as Side
import kotlin.time.TimeMark
import kotlin.time.TimeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable

@Serializable
internal data class OnlineMatchReference(val gameId: String, val accountId: String)

internal data class OnlineMatchState(
    val profile: LichessProfile? = null,
    val game: OnlineGame? = null,
    val ongoing: List<LichessOngoingGame> = emptyList(),
    val incoming: LichessChallenge? = null,
    val outgoing: Boolean = false,
    val busy: Boolean = false,
    val error: String? = null,
    val reference: OnlineMatchReference? = null,
    val connectingGame: Boolean = false,
) {
    val assistanceBlocked: Boolean get() = reference != null || connectingGame || outgoing ||
        game?.state?.isTerminal == false
}

internal data class OnlineGame(
    val id: String,
    val playerSide: Side,
    val board: GameUiState,
    val state: LichessGameState,
    val white: LichessPlayer,
    val black: LichessPlayer,
    val initialFen: String,
    val createdAt: Long,
    val initialClockMillis: Long,
    val clockIncrementMillis: Long,
    val connected: Boolean = true,
    val movePending: Boolean = false,
    private val clockBaseline: TimeMark = TimeSource.Monotonic.markNow(),
) {
    val canMove: Boolean get() = connected && !movePending && !state.isTerminal && board.turn == playerSide
    val lastMove: String? get() = board.moveHistory.lastOrNull()?.uci

    fun clockMillis(side: Side): Long {
        val baseline = if (side == Side.WHITE) state.whiteTimeMillis else state.blackTimeMillis
        val ticking = state.status == "started" && board.moveHistory.size >= 2 && board.turn == side
        return (baseline - if (ticking) clockBaseline.elapsedNow().inWholeMilliseconds else 0).coerceAtLeast(0L)
    }
}

internal class OnlineMatchController(
    private val scope: CoroutineScope,
    private val auth: LichessAuthorization,
    private val board: (LichessSession) -> LichessBoardApi,
    savedMatch: OnlineMatchReference? = null,
    private val persistMatch: (OnlineMatchReference?) -> Unit = {},
    private val onAssistanceBlocked: (Boolean) -> Unit = {},
) {
    private val _state = MutableStateFlow(OnlineMatchState(reference = savedMatch))
    val state: StateFlow<OnlineMatchState> = _state
    private val eventsConnected = MutableStateFlow(false)
    private var session: LichessSession? = null
    private var api: LichessBoardApi? = null
    private var authJob: Job? = null
    private var eventsJob: Job? = null
    private var challengeJob: Job? = null
    private var challengeId: String? = null
    private var canceledChallengeId: String? = null
    private var challengeGeneration = 0
    private var gameJob: Job? = null
    private var moveJob: Job? = null
    private var actionJob: Job? = null
    private var activeGameId: String? = null
    private var pendingPly: Int? = null
    private var loadingOngoing = false

    fun signIn() {
        if (authJob?.isActive == true || session != null) return
        auth.prepareBrowser()
        update { copy(busy = true, error = null) }
        authJob = scope.launch {
            try {
                val connected = auth.authorize()
                val reference = state.value.reference
                if (reference != null && reference.accountId != connected.profile.id) {
                    update { copy(error = "Use the Lichess account that started the unfinished match.") }
                    return@launch
                }
                session = connected
                val client = board(connected)
                api = client
                loadingOngoing = true
                update { copy(profile = connected.profile) }
                startAccountEvents(client)
                if (reference != null) beginGame(reference.gameId)
                refreshOngoing(client)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                update { copy(error = failure.onlineMessage("Could not sign in to Lichess.")) }
            } finally {
                update { copy(busy = false) }
            }
        }
    }

    fun disconnect() {
        if (state.value.assistanceBlocked) return
        val connected = session ?: return
        close()
        update { OnlineMatchState() }
        scope.launch {
            try {
                auth.revoke(connected)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // The local session is already disconnected.
            }
        }
    }

    fun challenge(username: String) {
        val client = api ?: return
        val opponent = username.trim()
        if (!lichessUsername.matches(opponent)) {
            update { copy(error = "Enter a valid Lichess username.") }
            return
        }
        if (state.value.assistanceBlocked || state.value.busy || loadingOngoing) return
        challengeId = null
        val generation = ++challengeGeneration
        update { copy(outgoing = true, error = null) }
        challengeJob = scope.launch {
            try {
                check(withTimeoutOrNull(15_000) { eventsConnected.first { it } } == true)
                withTimeoutOrNull(120_000) {
                    client.challenge(opponent).collect { event ->
                        if (generation != challengeGeneration) return@collect
                        if (event.id != null) challengeId = event.id
                        when (event.done) {
                            "accepted" -> challengeId?.let(::beginGame)
                            "declined" -> update { copy(error = "Challenge declined.") }
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                update { copy(error = failure.onlineMessage("Could not send the Lichess challenge.")) }
            } finally {
                if (generation == challengeGeneration) update { copy(outgoing = false) }
            }
        }
    }

    fun cancelChallenge() {
        canceledChallengeId = challengeId
        challengeGeneration++
        challengeJob?.cancel()
        update { copy(outgoing = false) }
    }

    fun answerChallenge(id: String, accept: Boolean) {
        val client = api ?: return
        if (state.value.incoming?.id != id || actionJob?.isActive == true || state.value.assistanceBlocked) return
        update { copy(incoming = null, connectingGame = accept, error = null) }
        actionJob = scope.launch {
            try {
                client.answerChallenge(id, accept)
                if (accept) beginGame(id)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (activeGameId == null) update { copy(connectingGame = false) }
                update { copy(error = failure.onlineMessage("Could not answer the Lichess challenge.")) }
            }
        }
    }

    fun resume(gameId: String) {
        if (state.value.ongoing.any { it.gameId == gameId && it.supported } || state.value.reference?.gameId == gameId) {
            beginGame(gameId)
        }
    }

    fun submitMove(from: Pair<Int, Int>, to: Pair<Int, Int>, promotion: PromotionType? = null) {
        val client = api ?: return
        val game = state.value.game?.takeIf { it.canMove } ?: return
        val uci = LichessMoveCodec.outgoing(game.board, from, to, promotion) ?: return
        val ply = game.board.moveHistory.size
        pendingPly = ply
        update { copy(game = game.copy(movePending = true), error = null) }
        moveJob = scope.launch {
            try {
                client.submitMove(game.id, uci)
                delay(4_000)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                update { copy(error = failure.onlineMessage("Checking whether Lichess received your move.")) }
            }
            if (pendingPly == ply && activeGameId == game.id) reconnect()
        }
    }

    fun resign() = gameAction(LichessBoardApi::resign)
    fun abort() = gameAction(LichessBoardApi::abort)
    fun offerDraw(accept: Boolean) = gameAction { id -> offerDraw(id, accept) }

    fun closeMatch() {
        if (state.value.assistanceBlocked) return
        gameJob?.cancel()
        activeGameId = null
        update { copy(game = null, error = null) }
    }

    fun reconnect() {
        val id = activeGameId ?: state.value.reference?.gameId ?: return
        if (api == null) return
        gameJob?.cancel()
        activeGameId = null
        beginGame(id)
    }

    fun close() {
        listOf(authJob, eventsJob, challengeJob, gameJob, moveJob, actionJob).forEach { it?.cancel() }
        api = null
        session = null
        activeGameId = null
        eventsConnected.value = false
        challengeId = null
        canceledChallengeId = null
        challengeGeneration++
    }

    private fun gameAction(action: suspend LichessBoardApi.(String) -> Unit) {
        val client = api ?: return
        val game = state.value.game?.takeIf { it.connected && !it.state.isTerminal } ?: return
        if (actionJob?.isActive == true) return
        actionJob = scope.launch {
            try {
                client.action(game.id)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                update { copy(error = failure.onlineMessage("Could not send the game action.")) }
            }
        }
    }

    private fun startAccountEvents(client: LichessBoardApi) {
        eventsJob?.cancel()
        eventsJob = scope.launch {
            var backoff = 1_000L
            while (api === client) {
                try {
                    client.accountEvents().collect { event ->
                        if (event == LichessAccountEvent.Connected) backoff = 1_000L
                        receiveAccountEvent(event)
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    if (failure is LichessException && failure.status in setOf(401, 403)) {
                        loseAuthorization()
                        return@launch
                    }
                } finally {
                    eventsConnected.value = false
                }
                delay(backoff)
                backoff = (backoff * 2).coerceAtMost(30_000L)
            }
        }
    }

    private fun receiveAccountEvent(event: LichessAccountEvent) {
        when (event) {
            LichessAccountEvent.Connected -> eventsConnected.value = true
            is LichessAccountEvent.GameStarted -> {
                val game = event.game
                if (!game.supported) return
                update { copy(ongoing = (ongoing + game).distinctBy { it.gameId }) }
                if (state.value.outgoing || state.value.connectingGame ||
                    state.value.reference?.gameId == game.gameId || canceledChallengeId == game.gameId
                ) {
                    if (canceledChallengeId == game.gameId) canceledChallengeId = null
                    beginGame(game.gameId)
                }
            }
            is LichessAccountEvent.GameFinished -> {
                update { copy(ongoing = ongoing.filterNot { it.gameId == event.gameId }) }
            }
            is LichessAccountEvent.ChallengeReceived -> {
                val challenge = event.challenge
                if (challenge.supported && challenge.challenger?.id != session?.profile?.id) {
                    update { copy(incoming = challenge) }
                }
            }
            is LichessAccountEvent.ChallengeClosed ->
                update { copy(incoming = incoming?.takeUnless { it.id == event.id }) }
        }
    }

    private suspend fun refreshOngoing(client: LichessBoardApi) {
        try {
            val ongoing = client.ongoingGames().filter { it.supported }
            if (api === client) update { copy(ongoing = (ongoing + this.ongoing).distinctBy { it.gameId }) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            if (api === client) update { copy(error = failure.onlineMessage("Could not check ongoing Lichess games.")) }
        } finally {
            loadingOngoing = false
        }
    }

    private fun beginGame(id: String) {
        val client = api ?: return
        val accountId = session?.profile?.id ?: return
        if (!lichessGameId.matches(id) || activeGameId == id && gameJob?.isActive == true) return
        if (activeGameId != null && activeGameId != id && state.value.assistanceBlocked) return
        val reference = OnlineMatchReference(id, accountId)
        if (state.value.reference != reference) persistMatch(reference)
        activeGameId = id
        gameJob?.cancel()
        update {
            copy(
                reference = reference, connectingGame = true, outgoing = false, error = null,
                game = game?.takeIf { it.id == id }?.copy(connected = false),
            )
        }
        gameJob = scope.launch {
            var backoff = 1_000L
            while (activeGameId == id && api === client) {
                try {
                    client.gameEvents(id).collect { event ->
                        when (event) {
                            is LichessGameEvent.Full -> {
                                backoff = 1_000L
                                if (!receiveFull(id, event.game)) throw UnsupportedMatch()
                            }
                            is LichessGameEvent.Changed -> state.value.game?.takeIf { it.id == id && it.connected }?.let {
                                receivePosition(it, event.state, full = false)
                            }
                        }
                    }
                    if (state.value.game?.state?.isTerminal == true) return@launch
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: UnsupportedMatch) {
                    activeGameId = null
                    persistMatch(null)
                    update { copy(reference = null, connectingGame = false, game = null, error = "Open this match on Lichess.") }
                    return@launch
                } catch (failure: Exception) {
                    if (failure is LichessException && failure.status in setOf(401, 403)) {
                        loseAuthorization()
                        return@launch
                    }
                    update { copy(error = failure.onlineMessage("Reconnecting to the Lichess match.")) }
                }
                update { copy(game = game?.copy(connected = false)) }
                delay(backoff)
                backoff = (backoff * 2).coerceAtMost(30_000L)
            }
        }
    }

    private fun receiveFull(id: String, full: LichessGameFull): Boolean {
        val user = session?.profile?.id ?: return false
        val side = when (user) {
            full.white.id -> Side.WHITE
            full.black.id -> Side.BLACK
            else -> return false
        }
        if (full.id != id || !full.supported) return false
        val previous = state.value.game?.takeIf { it.id == id }
        val game = previous ?: OnlineGame(
            id, side, GameUiState(), full.state.copy(moves = ""), full.white, full.black,
            full.initialFen, full.createdAt, checkNotNull(full.clock).initial, full.clock.increment,
        )
        receivePosition(game, full.state, full = true)
        return true
    }

    private fun receivePosition(game: OnlineGame, position: LichessGameState, full: Boolean) {
        val moves = position.moves.uciMoves()
        val rebuilt = LichessMoveCodec.replay(game.initialFen, moves, game.board, game.state.moves.uciMoves())
        if (full || pendingPly?.let { moves.size != it } == true || position.isTerminal) pendingPly = null
        val reference = if (position.isTerminal) null else OnlineMatchReference(game.id, checkNotNull(session).profile.id)
        if (reference != state.value.reference) persistMatch(reference)
        update {
            copy(
                game = game.copy(
                    board = rebuilt, state = position, connected = true, movePending = pendingPly != null,
                    clockBaseline = TimeSource.Monotonic.markNow(),
                ),
                reference = reference, connectingGame = false, outgoing = false, error = null,
            )
        }
    }

    private fun loseAuthorization() {
        close()
        update {
            copy(profile = null, ongoing = emptyList(), incoming = null, outgoing = false, busy = false,
                game = game?.copy(connected = false), error = "Sign in again to reconnect to Lichess.")
        }
    }

    private inline fun update(transform: OnlineMatchState.() -> OnlineMatchState) {
        val old = _state.value
        val new = old.transform()
        if (old.assistanceBlocked != new.assistanceBlocked) onAssistanceBlocked(new.assistanceBlocked)
        _state.value = new
    }

    private class UnsupportedMatch : Exception()
}

private fun String.uciMoves(): List<String> = split(' ').filter(String::isNotBlank)

private fun Exception.onlineMessage(fallback: String): String = (this as? LichessException)?.message ?: fallback
