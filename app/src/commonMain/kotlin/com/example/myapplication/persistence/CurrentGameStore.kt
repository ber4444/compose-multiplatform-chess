package com.example.myapplication.persistence

import com.example.myapplication.GameSnapshot
import com.example.myapplication.GameSnapshotMapper
import com.example.myapplication.GameSnapshotSink
import com.example.myapplication.GameUiState
import com.example.myapplication.WinState
import com.russhwolf.settings.Settings
import kotlin.uuid.Uuid
import kotlinx.serialization.json.Json

/**
 * Persists the in-progress [GameSnapshot] to the russhwolf [Settings] backend, so the app can resume
 * the current game after process death / relaunch (Phase 2: autosave + resume-later).
 *
 * The versioned key accepts both the original JSON and a pointer to chunks for snapshots larger
 * than desktop Preferences can store in one value. A pointer is published only after all its
 * chunks are written. Corrupt or incomplete snapshots are ignored on startup.
 */
class CurrentGameStore(
    private val settings: Settings,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    fun save(snapshot: GameSnapshot) {
        val encoded = json.encodeToString(GameSnapshot.serializer(), snapshot)
        val previous = settings.getStringOrNull(KEY)
        if (encoded.length <= CHUNK_SIZE) {
            settings.putString(KEY, encoded)
        } else {
            val generation = Uuid.random().toHexString()
            val chunks = encoded.chunked(CHUNK_SIZE)
            require(chunks.size <= MAX_CHUNKS) { "Snapshot exceeds local storage limit" }
            val pointer = "$CHUNK_POINTER$generation"
            try {
                chunks.forEachIndexed { index, chunk -> settings.putString(chunkKey(generation, index), chunk) }
                settings.putInt(countKey(generation), chunks.size)
                settings.putString(KEY, pointer)
            } catch (failure: Exception) {
                if (settings.getStringOrNull(KEY) != pointer) removeChunks(generation, chunks.size)
                throw failure
            }
        }
        removePreviousChunks(previous)
    }

    fun load(): GameSnapshot? {
        repeat(2) {
            val value = settings.getStringOrNull(KEY) ?: return null
            val loaded = runCatching {
                val generation = generationOf(value)
                val encoded = if (generation == null) value else buildString {
                    val count = settings.getInt(countKey(generation), 0)
                    require(count in 1..MAX_CHUNKS)
                    repeat(count) { index -> append(checkNotNull(settings.getStringOrNull(chunkKey(generation, index)))) }
                }
                json.decodeFromString(GameSnapshot.serializer(), encoded)
            }.getOrNull()
            if (loaded != null || value == settings.getStringOrNull(KEY)) return loaded
        }
        return null
    }

    fun clear() {
        val previous = settings.getStringOrNull(KEY)
        settings.remove(KEY)
        removePreviousChunks(previous)
    }

    private fun removePreviousChunks(value: String?) {
        val generation = value?.let(::generationOf) ?: return
        removeChunks(generation, settings.getInt(countKey(generation), 0).coerceIn(0, MAX_CHUNKS))
    }

    private fun removeChunks(generation: String, count: Int) {
        runCatching {
            repeat(count) { settings.remove(chunkKey(generation, it)) }
            settings.remove(countKey(generation))
        }
    }

    private fun generationOf(value: String): String? =
        value.takeIf { it.startsWith(CHUNK_POINTER) }?.removePrefix(CHUNK_POINTER)?.takeIf(generationPattern::matches)

    private fun chunkKey(generation: String, index: Int) = "${KEY}_${generation}_$index"
    private fun countKey(generation: String) = "${KEY}_${generation}_count"

    companion object {
        const val KEY = "current_game.v1"
        private const val CHUNK_POINTER = "chunks:"
        private const val CHUNK_SIZE = 2_000
        private const val MAX_CHUNKS = 512
        private val generationPattern = Regex("[a-f0-9]{32}")
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
