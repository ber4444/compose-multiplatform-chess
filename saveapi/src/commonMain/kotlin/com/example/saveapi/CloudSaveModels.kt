package com.example.saveapi

import com.example.myapplication.GameSnapshot
import com.example.myapplication.MoveRecord
import com.example.myapplication.Set
import kotlinx.serialization.Serializable

@Serializable
data class CloudAccount(val uid: String)

@Serializable
data class CloudSavedGame(
    val id: String,
    val savedAtEpochMillis: Long,
    val result: String,
    val white: String,
    val black: String,
    val moveCount: Int,
    val pgn: String,
    val moveRecords: List<MoveRecord> = emptyList(),
    val schemaVersion: Int = 1,
)

@Serializable
data class CloudHistoryItem(
    val id: String,
    val savedAtEpochMillis: Long,
    val result: String,
    val white: String,
    val black: String,
    val moveCount: Int,
)

@Serializable
data class CloudHistoryPage(val items: List<CloudHistoryItem>, val nextCursor: String? = null)

@Serializable
data class CloudResumeGame(
    val gameId: String,
    val playerSide: Set,
    val snapshot: GameSnapshot,
    val schemaVersion: Int = 1,
)

/** The version of the slot is carried by the HTTP ETag even when there is no game. */
@Serializable
data class CloudResumeSlot(val game: CloudResumeGame? = null)

@Serializable
data class CloudSaveError(val error: String)
