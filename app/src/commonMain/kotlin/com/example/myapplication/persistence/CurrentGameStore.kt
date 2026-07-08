package com.example.myapplication.persistence

import com.example.myapplication.GameSnapshot
import com.example.myapplication.GameSnapshotMapper
import com.example.myapplication.GameSnapshotSink
import com.example.myapplication.GameUiState
import com.example.myapplication.WinState
import com.russhwolf.settings.Settings
import kotlinx.serialization.json.Json

/**
 * Persists the in-progress [GameSnapshot] to the russhwolf [Settings] backend, so the app can resume
 * the current game after process death / relaunch (Phase 2: autosave + resume-later).
 *
 * The snapshot is stored as one JSON blob under a **versioned** key (`current_game.v1`): a future
 * schema change can bump the key and old data simply won't load. [load] is tolerant — corrupt or
 * unreadable JSON returns null instead of throwing, so a bad value can never crash launch. The
 * [Json] instance uses `ignoreUnknownKeys` so newly-added optional fields don't break older blobs.
 */
class CurrentGameStore(
    private val settings: Settings,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    fun save(snapshot: GameSnapshot) {
        settings.putString(KEY, json.encodeToString(GameSnapshot.serializer(), snapshot))
    }

    fun load(): GameSnapshot? =
        settings.getStringOrNull(KEY)?.let {
            runCatching { json.decodeFromString(GameSnapshot.serializer(), it) }.getOrNull()
        }

    fun clear() = settings.remove(KEY)

    companion object {
        const val KEY = "current_game.v1"
    }
}

/**
 * Bridges the core's platform-neutral [GameSnapshotSink] to the russhwolf-backed
 * [CurrentGameStore]. Entry points wrap their `CurrentGameStore` in this and pass it to
 * `GameViewModel(snapshotSink = ...)` so the VM can autosave without knowing the storage backend.
 */
fun CurrentGameStore.asSnapshotSink(): GameSnapshotSink =
    object : GameSnapshotSink {
        override fun save(snapshot: GameSnapshot) = this@asSnapshotSink.save(snapshot)
        override fun clear() = this@asSnapshotSink.clear()
    }

/**
 * Decides whether the autosaved game (if any) should be restored. Per the plan: a game that already
 * ended (`winState != NONE`) is **not** restored — start fresh (and clear the stale snapshot) so the
 * user lands on a clean board, not a frozen game-over screen.
 *
 * Returns the [GameUiState] to construct the VM with (restored or default), and a flag indicating
 * whether the caller should also clear the snapshot (only true on the "finished game, start fresh"
 * path). Entry points use this to seed `GameViewModel(gameState = ..., currentGameStore = ...)`.
 */
object CurrentGameStoreSupport {

    data class InitialState(val state: GameUiState, val shouldClear: Boolean)

    fun loadInitialState(store: CurrentGameStore): InitialState {
        val snapshot = store.load() ?: return InitialState(GameUiState(), shouldClear = false)
        if (snapshot.winState != WinState.NONE) {
            // The saved game is already over — don't restore into a frozen game-over screen.
            return InitialState(GameUiState(), shouldClear = true)
        }
        return InitialState(GameSnapshotMapper.toState(snapshot), shouldClear = false)
    }
}
