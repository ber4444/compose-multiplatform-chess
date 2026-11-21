package com.example.coachserver.saves

import com.example.saveapi.CloudHistoryItem
import com.example.saveapi.CloudHistoryPage
import com.example.saveapi.CloudResumeGame
import com.example.saveapi.CloudSavedGame
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import java.util.Base64
import java.util.HexFormat

interface CloudSaveRepository {
    suspend fun isDeleting(uid: String): Boolean
    suspend fun history(uid: String, after: CloudHistoryCursor?): CloudHistoryPage
    suspend fun historyGame(uid: String, id: String): CloudSavedGame?
    suspend fun saveHistory(uid: String, game: CloudSavedGame): HistoryWrite
    suspend fun deleteHistory(uid: String, id: String): HistoryWrite
    suspend fun resume(uid: String): StoredResume
    suspend fun writeResume(uid: String, expectedRevision: Long, operationId: String, game: CloudResumeGame?): ResumeWrite
    suspend fun deleteAccountData(uid: String)
}

enum class HistoryWrite { Saved, Conflict, Deleted, AccountDeleting }

sealed interface ResumeWrite {
    data class Saved(val revision: Long) : ResumeWrite
    data object Conflict : ResumeWrite
    data object Stale : ResumeWrite
    data object AccountDeleting : ResumeWrite
}

data class StoredResume(val revision: Long = 0, val game: CloudResumeGame? = null)

data class CloudHistoryCursor(val savedAtEpochMillis: Long, val id: String) {
    internal fun encode(): String = Base64.getUrlEncoder().withoutPadding()
        .encodeToString("$savedAtEpochMillis\n$id".encodeToByteArray())

    companion object {
        internal fun decode(value: String): CloudHistoryCursor? {
            if (value.length > 200) return null
            val decoded = try {
                Base64.getUrlDecoder().decode(value).decodeToString(throwOnInvalidSequence = true)
            } catch (_: IllegalArgumentException) {
                return null
            } catch (_: java.nio.charset.CharacterCodingException) {
                return null
            }
            val millis = decoded.substringBefore('\n').toLongOrNull()?.takeIf { it >= 0 } ?: return null
            val id = decoded.substringAfter('\n', "").takeIf(::isSaveId) ?: return null
            return CloudHistoryCursor(millis, id)
        }
    }
}

internal const val HISTORY_PAGE_SIZE = 50
private const val MAX_RECEIPTS = 32
private val saveIdPattern = Regex("[A-Za-z0-9_-]{1,128}")

internal fun isSaveId(id: String): Boolean =
    saveIdPattern.matches(id) && !(id.startsWith("__") && id.endsWith("__"))

internal fun CloudSavedGame.metadata() = CloudHistoryItem(id, savedAtEpochMillis, result, white, black, moveCount)

internal fun historyPage(items: List<CloudHistoryItem>): CloudHistoryPage {
    val page = items.take(HISTORY_PAGE_SIZE)
    val nextCursor = if (items.size > page.size) {
        page.last().let { CloudHistoryCursor(it.savedAtEpochMillis, it.id).encode() }
    } else {
        null
    }
    return CloudHistoryPage(page, nextCursor)
}

internal fun decideHistoryWrite(existing: CloudSavedGame?, deleted: Boolean, game: CloudSavedGame): HistoryWrite = when {
    deleted -> HistoryWrite.Deleted
    existing == null || existing == game -> HistoryWrite.Saved
    game.id.startsWith("legacy-") && existing.pgn == game.pgn -> HistoryWrite.Saved
    else -> HistoryWrite.Conflict
}

internal data class ResumeReceipt(val id: String, val requestHash: String, val revision: Long)

internal data class ResumeRecord(
    val slot: StoredResume = StoredResume(),
    val receipts: List<ResumeReceipt> = emptyList(),
) {
    fun write(expectedRevision: Long, operationId: String, game: CloudResumeGame?): Pair<ResumeWrite, ResumeRecord?> {
        val fingerprint = sha256("$expectedRevision\n${Json.encodeToString(game)}")
        val previous = receipts.firstOrNull { it.id == operationId }
        if (previous != null) {
            val result = if (previous.requestHash == fingerprint) ResumeWrite.Saved(previous.revision) else ResumeWrite.Conflict
            return result to null
        }
        if (slot.revision != expectedRevision) return ResumeWrite.Stale to null
        val revision = Math.addExact(slot.revision, 1)
        val updated = ResumeRecord(
            StoredResume(revision, game),
            (receipts + ResumeReceipt(operationId, fingerprint, revision)).takeLast(MAX_RECEIPTS),
        )
        return ResumeWrite.Saved(revision) to updated
    }
}

internal fun sha256(value: String): String =
    HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.encodeToByteArray()))
