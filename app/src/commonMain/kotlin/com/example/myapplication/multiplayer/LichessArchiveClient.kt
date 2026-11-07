package com.example.myapplication.multiplayer

import io.ktor.client.HttpClient
import io.ktor.http.ContentType
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.decodeFromJsonElement

internal data class LichessArchiveEntry(
    val id: String,
    val createdAt: Long,
    val white: LichessPlayer,
    val black: LichessPlayer,
    val status: String,
    val winner: String?,
    val moveCount: Int,
)

internal class LichessArchiveClient(
    httpClient: HttpClient,
    gate: LichessRequestGate = LichessRequestGate(),
) {
    private val http = LichessHttp(httpClient, LICHESS_BASE_URL, gate)

    fun recentFinished(username: String): Flow<LichessArchiveEntry> {
        require(lichessUsername.matches(username))
        return http.stream("/api/games/user/$username?max=50&ongoing=false&finished=true") { payload ->
            val id = payload.string("id")?.takeIf(lichessGameId::matches) ?: return@stream null
            val status = payload.string("status")?.takeIf { it in terminalStatuses && it !in abortedStatuses }
                ?: return@stream null
            val players = payload.obj("players") ?: return@stream null
            fun player(side: String): LichessPlayer {
                val player = players.obj(side) ?: return LichessPlayer()
                return player.obj("user")?.let { lichessJson.decodeFromJsonElement<LichessPlayer>(it) }
                    ?: LichessPlayer(name = player.string("name"), aiLevel = player.string("aiLevel")?.toIntOrNull())
            }
            LichessArchiveEntry(
                id, payload.string("createdAt")?.toLongOrNull() ?: 0L, player("white"), player("black"), status,
                payload.string("winner"), payload.string("moves").orEmpty().split(' ').count(String::isNotBlank),
            )
        }
    }

    suspend fun exportPgn(gameId: String): String {
        require(lichessGameId.matches(gameId))
        return http.text("/game/export/$gameId?evals=false", type = ContentType("application", "x-chess-pgn"))
    }
}
