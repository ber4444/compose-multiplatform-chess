package com.example.myapplication.multiplayer

import io.ktor.client.HttpClient
import io.ktor.http.HttpMethod
import io.ktor.http.Parameters
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.decodeFromJsonElement

internal interface LichessBoardApi {
    fun accountEvents(): Flow<LichessAccountEvent>
    fun gameEvents(gameId: String): Flow<LichessGameEvent>
    suspend fun ongoingGames(): List<LichessOngoingGame>
    fun challenge(username: String): Flow<ChallengeEvent>
    suspend fun answerChallenge(id: String, accept: Boolean)
    suspend fun submitMove(gameId: String, uci: String)
    suspend fun resign(gameId: String)
    suspend fun abort(gameId: String)
    suspend fun offerDraw(gameId: String, accept: Boolean)
}

internal class LichessBoardClient(
    client: HttpClient,
    session: LichessSession,
    baseUrl: String = LICHESS_BASE_URL,
    gate: LichessRequestGate = LichessRequestGate(),
) : LichessBoardApi {
    private val http = LichessHttp(client, baseUrl, gate, session.token)

    override fun accountEvents(): Flow<LichessAccountEvent> = http.stream(
        "/api/stream/event",
        connected = LichessAccountEvent.Connected,
    ) { event ->
        when (event.string("type")) {
            "gameStart" -> event.obj("game")?.let {
                LichessAccountEvent.GameStarted(lichessJson.decodeFromJsonElement<LichessOngoingGame>(it))
            }
            "gameFinish" -> event.obj("game")?.string("gameId")?.let(LichessAccountEvent::GameFinished)
            "challenge" -> event.obj("challenge")?.let {
                LichessAccountEvent.ChallengeReceived(lichessJson.decodeFromJsonElement<LichessChallenge>(it))
            }
            "challengeCanceled", "challengeDeclined" -> event.obj("challenge")?.string("id")
                ?.let(LichessAccountEvent::ChallengeClosed)
            else -> null
        }
    }

    override fun gameEvents(gameId: String): Flow<LichessGameEvent> {
        require(lichessGameId.matches(gameId))
        return http.stream("/api/board/game/stream/$gameId") { event ->
            when (event.string("type")) {
                "gameFull" -> LichessGameEvent.Full(lichessJson.decodeFromJsonElement<LichessGameFull>(event))
                "gameState" -> LichessGameEvent.Changed(lichessJson.decodeFromJsonElement<LichessGameState>(event))
                else -> null
            }
        }
    }

    override suspend fun ongoingGames(): List<LichessOngoingGame> =
        lichessJson.decodeFromString<OngoingResponse>(http.text("/api/account/playing")).nowPlaying

    override fun challenge(username: String): Flow<ChallengeEvent> {
        require(lichessUsername.matches(username))
        return http.stream(
            "/api/challenge/$username",
            method = HttpMethod.Post,
            form = Parameters.build {
                append("rated", "false")
                append("variant", "standard")
                append("clock.limit", "900")
                append("clock.increment", "10")
                append("keepAliveStream", "true")
            },
            idleTimeoutMillis = null,
        ) { payload ->
            val id = payload.obj("challenge")?.string("id") ?: payload.string("id")
            val done = payload.string("done")
            if (id == null && done == null) null else ChallengeEvent(id, done)
        }
    }

    override suspend fun answerChallenge(id: String, accept: Boolean) {
        require(lichessGameId.matches(id))
        val answer = if (accept) "accept" else "decline"
        http.text("/api/challenge/$id/$answer", HttpMethod.Post)
    }

    override suspend fun submitMove(gameId: String, uci: String) {
        require(uci.matches(Regex("[a-h][1-8][a-h][1-8][qrbn]?")))
        action(gameId, "move/$uci")
    }

    override suspend fun resign(gameId: String) = action(gameId, "resign")
    override suspend fun abort(gameId: String) = action(gameId, "abort")

    override suspend fun offerDraw(gameId: String, accept: Boolean) {
        val answer = if (accept) "yes" else "no"
        action(gameId, "draw/$answer")
    }

    private suspend fun action(gameId: String, action: String) {
        require(lichessGameId.matches(gameId))
        http.text("/api/board/game/$gameId/$action", HttpMethod.Post)
    }

    @Serializable
    private data class OngoingResponse(val nowPlaying: List<LichessOngoingGame>)
}
