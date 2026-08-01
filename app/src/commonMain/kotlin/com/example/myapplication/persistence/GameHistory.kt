package com.example.myapplication.persistence

import com.example.myapplication.MoveRecord
import com.russhwolf.settings.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * A finished game saved to the Game History (Phase 3). Built from a [com.example.myapplication.PgnTags]
 * + serialized PGN movetext at game end and listed on the History screen.
 *
 * - [id] is derived from the save timestamp (+ a counter on collision) — `commonMain` has no UUID,
 *   and a monotonic-ish timestamp id is all that's needed to key a LazyColumn.
 * - [pgn] is the full PGN string (tags + movetext + result); that's what "Share" ships and what the
 *   detail view shows verbatim.
 */
@Serializable
data class SavedGame(
    val id: String,
    val savedAtEpochMillis: Long,
    val result: String,        // "1-0" etc.
    val white: String,
    val black: String,
    val moveCount: Int,
    val pgn: String,
    val moveRecords: List<MoveRecord> = emptyList(),
)

/**
 * Persists the list of [SavedGame]s to the russhwolf [Settings] backend as one JSON blob, exposing
 * a [StateFlow] for the History screen. Newest first; capped at [MAX_GAMES] (oldest dropped).
 *
 * Like [CurrentGameStore], the blob lives under a **versioned** key (`game_history.v1`) and [load]
 * is tolerant — corrupt JSON yields an empty list rather than crashing. `ignoreUnknownKeys` keeps
 * older blobs readable after a [SavedGame] field is added.
 */
class GameHistoryRepository(
    private val settings: Settings,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    private val _games = MutableStateFlow(load())
    val games: StateFlow<List<SavedGame>> get() = _games

    /** Prepends [game], persists, and updates the flow. Drops the oldest entry past the cap. */
    fun add(game: SavedGame) {
        val updated = (listOf(game) + _games.value).take(MAX_GAMES)
        persist(updated)
    }

    /** Updates an existing game by id, persists, and updates the flow. */
    fun update(game: SavedGame) {
        val updated = _games.value.map { if (it.id == game.id) game else it }
        persist(updated)
    }

    /** Removes the game with [id] (if present), persists, and updates the flow. */
    fun delete(id: String) {
        val updated = _games.value.filterNot { it.id == id }
        persist(updated)
    }

    private fun persist(games: List<SavedGame>) {
        settings.putString(KEY, json.encodeToString(ListSerializer(SavedGame.serializer()), games))
        _games.value = games
    }

    private fun load(): List<SavedGame> =
        settings.getStringOrNull(KEY)?.let {
            runCatching { json.decodeFromString(ListSerializer(SavedGame.serializer()), it) }
                .getOrNull()
        } ?: emptyList()

    companion object {
        const val KEY = "game_history.v1"
        const val MAX_GAMES = 200
    }
}
