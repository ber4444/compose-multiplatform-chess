package com.example.myapplication.explorer

import com.example.myapplication.multiplayer.LichessException
import com.example.myapplication.multiplayer.LichessRequestGate
import com.example.myapplication.multiplayer.lichessJson
import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.accept
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable

/**
 * The Lichess opening explorer's `/lichess` database: aggregated rated Lichess games, filtered by
 * rating group and speed.
 *
 * **It requires an OAuth token** (verified 2026-11-21: an anonymous request returns 401; the
 * published spec declares `security: OAuth2: []`, i.e. any scope, so #170's `board:play` token is
 * enough). `/masters` is still anonymous, but it is 2200+ OTB games — the opposite of "players at
 * your level".
 */
interface LichessOpeningExplorer {
    /** Throws [LichessException] for an HTTP error and anything else for a transport failure. */
    suspend fun lookup(fen: String, band: RatingBand, token: String): HumanMoveStats
}

class KtorLichessOpeningExplorer(
    private val client: HttpClient,
    private val baseUrl: String = LICHESS_EXPLORER_URL,
    private val top: Int = DEFAULT_TOP_MOVES,
) : LichessOpeningExplorer {
    // The explorer is its own host with its own limits, so it gets its own 429 back-off rather
    // than silencing the Board API for a minute. The mutex is Lichess's "one request at a time".
    private val gate = LichessRequestGate()
    private val mutex = Mutex()

    override suspend fun lookup(fen: String, band: RatingBand, token: String): HumanMoveStats = mutex.withLock {
        gate.check()
        val response = client.get(baseUrl.trimEnd('/') + "/lichess") {
            parameter("variant", "standard")
            parameter("fen", fen)
            parameter("ratings", band.floor.toString())
            parameter("speeds", SPEEDS)
            parameter("moves", top.toString())
            parameter("topGames", "0")
            parameter("recentGames", "0")
            accept(ContentType.Application.Json)
            bearerAuth(token)
            header(HttpHeaders.UserAgent, LICHESS_USER_AGENT)
            timeout { requestTimeoutMillis = 10_000L }
        }
        val status = response.status.value
        if (status == 429) gate.blocked()
        if (status !in 200..299) throw LichessException("Lichess explorer returned $status.", status)
        val body = lichessJson.decodeFromString<ExplorerResponse>(response.bodyAsText())
        val total = body.white + body.draws + body.black
        HumanMoveStats(
            source = HumanMoveSource.LICHESS,
            band = band,
            moves = body.moves.map { move ->
                val games = move.white + move.draws + move.black
                HumanMove(move.uci, move.san, if (total == 0L) 0.0 else games.toDouble() / total, games)
            },
            totalGames = total,
        )
    }

    @Serializable
    private class ExplorerResponse(
        val white: Long = 0,
        val draws: Long = 0,
        val black: Long = 0,
        val moves: List<ExplorerMove> = emptyList(),
    )

    @Serializable
    private class ExplorerMove(
        val uci: String,
        val san: String,
        val white: Long = 0,
        val draws: Long = 0,
        val black: Long = 0,
    )

    private companion object {
        // Bullet is excluded: at 1+0 the move a player makes is mostly the one they can make fast.
        const val SPEEDS = "blitz,rapid,classical"
    }
}

internal const val LICHESS_EXPLORER_URL = "https://explorer.lichess.org"

// Lichess asks every API client to identify itself; requests without one "may be rejected".
internal const val LICHESS_USER_AGENT = "io.github.ber4444.chess (+https://github.com/ber4444/compose-multiplatform-chess)"
