package com.example.myapplication.persistence.cloud

import com.example.saveapi.CloudHistoryItem
import com.example.saveapi.CloudResumeGame
import com.example.saveapi.CloudSavedGame
import com.russhwolf.settings.Settings
import io.ktor.util.sha1
import kotlin.uuid.Uuid
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
internal data class HistoryWrite(val id: String, val game: CloudSavedGame?)

@Serializable
internal data class ResumeWrite(
    val operationId: String,
    val game: CloudResumeGame?,
    val expectedRevision: Long? = null,
)

@Serializable
internal data class CloudJournal(
    val history: List<HistoryWrite> = emptyList(),
    val historyCache: List<CloudHistoryItem> = emptyList(),
    val details: List<CloudSavedGame> = emptyList(),
    val importedIds: Set<String> = emptySet(),
    val resumeWrites: List<ResumeWrite> = emptyList(),
    val resumeRevision: Long? = null,
    val localResume: CloudResumeGame? = null,
    val recovery: CloudResumeGame? = null,
)

internal fun List<ResumeWrite>.enqueue(write: ResumeWrite): List<ResumeWrite> {
    val last = lastOrNull()
    val sameGame = write.game != null && write.game.gameId == last?.game?.gameId
    return if (sameGame && last?.expectedRevision == null) dropLast(1) + write else this + write
}

/**
 * Commits one owner-scoped journal generation at a time. Desktop Preferences caps individual
 * values, so the new generation is written in chunks before its pointer is changed.
 */
internal class CloudSaveJournal(private val settings: Settings) {
    private val json = Json { ignoreUnknownKeys = true }

    fun read(uid: String): CloudJournal {
        val key = key(uid)
        val generation = settings.getStringOrNull(key) ?: return CloudJournal()
        val prefix = "${key}_${generation}_"
        val count = settings.getInt("${prefix}n", 0)
        check(count in 1..MAX_CHUNKS) { "Invalid cloud save journal" }
        val encoded = buildString {
            repeat(count) { append(checkNotNull(settings.getStringOrNull("$prefix$it"))) }
        }
        return json.decodeFromString(encoded)
    }

    fun write(uid: String, journal: CloudJournal) {
        val key = key(uid)
        val generation = Uuid.random().toHexString().take(16)
        val prefix = "${key}_${generation}_"
        val chunks = json.encodeToString(journal).chunked(CHUNK_CHARS)
        check(chunks.size <= MAX_CHUNKS) { "Cloud save journal is full" }
        // Remove orphaned generations before writing to avoid exhausting local storage on retries.
        val previous = settings.getStringOrNull(key)
        cleanup { it.startsWith("${key}_") && !it.startsWith("${key}_${previous}_") }
        chunks.forEachIndexed { index, chunk -> settings.putString("$prefix$index", chunk) }
        settings.putInt("${prefix}n", chunks.size)
        settings.putString(key, generation)
        cleanup { it.startsWith("${key}_") && !it.startsWith(prefix) }
    }

    fun clear(uid: String) {
        val key = key(uid)
        settings.remove(key)
        cleanup { it.startsWith("${key}_") }
    }

    private fun cleanup(match: (String) -> Boolean) {
        runCatching { settings.keys.filter(match).forEach(settings::remove) }
    }

    private fun key(uid: String): String = "cs1_${sha1(uid.encodeToByteArray()).toHexString()}"

    private companion object {
        const val CHUNK_CHARS = 2_000
        const val MAX_CHUNKS = 480
    }
}
