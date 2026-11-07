package com.example.coachserver.saves

import com.example.saveapi.CloudHistoryItem
import com.example.saveapi.CloudResumeGame
import com.example.saveapi.CloudSavedGame
import com.google.api.core.ApiFuture
import com.google.cloud.firestore.DocumentSnapshot
import com.google.cloud.firestore.Firestore
import com.google.cloud.firestore.Query
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import java.util.concurrent.ExecutionException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal class FirestoreCloudSaveRepository(private val db: Firestore) : CloudSaveRepository {
    private fun history(uid: String) = db.collection("users").document(uid).collection("history")
    private fun slot(uid: String) = db.collection("users").document(uid).collection("state").document("current")

    // A deletion fence persists after identity deletion so an already-verified request cannot
    // recreate the data. Its path contains a digest and the only field is the deletion marker.
    private fun deletionFence(uid: String) = db.collection("saveAccountTombstones").document(sha256(uid))

    override suspend fun isDeleting(uid: String): Boolean = deletionFence(uid).get().await().exists()

    override suspend fun history(uid: String, after: CloudHistoryCursor?): com.example.saveapi.CloudHistoryPage {
        var query = history(uid).whereEqualTo("deleted", false)
            .orderBy("savedAtEpochMillis", Query.Direction.DESCENDING)
            .orderBy("id", Query.Direction.DESCENDING)
            .select("id", "savedAtEpochMillis", "result", "white", "black", "moveCount")
            .limit(HISTORY_PAGE_SIZE + 1)
        if (after != null) query = query.startAfter(after.savedAtEpochMillis, after.id)
        return historyPage(query.get().await().documents.map(DocumentSnapshot::historyItem))
    }

    override suspend fun historyGame(uid: String, id: String): CloudSavedGame? =
        history(uid).document(id).get().await().historyGame()

    override suspend fun saveHistory(uid: String, game: CloudSavedGame): HistoryWrite = db.runTransaction { tx ->
        if (tx.get(deletionFence(uid)).get().exists()) return@runTransaction HistoryWrite.AccountDeleting
        val reference = history(uid).document(game.id)
        val stored = tx.get(reference).get()
        val outcome = decideHistoryWrite(stored.historyGame(), stored.getBoolean("deleted") == true, game)
        if (outcome == HistoryWrite.Saved && !stored.exists()) {
            tx.set(reference, game.fields())
        }
        outcome
    }.await()

    override suspend fun deleteHistory(uid: String, id: String): HistoryWrite = db.runTransaction { tx ->
        if (tx.get(deletionFence(uid)).get().exists()) return@runTransaction HistoryWrite.AccountDeleting
        val reference = history(uid).document(id)
        if (tx.get(reference).get().getBoolean("deleted") != true) {
            tx.set(reference, mapOf("deleted" to true))
        }
        HistoryWrite.Saved
    }.await()

    override suspend fun resume(uid: String): StoredResume = slot(uid).get().await().resumeRecord().slot

    override suspend fun writeResume(
        uid: String,
        expectedRevision: Long,
        operationId: String,
        game: CloudResumeGame?,
    ): ResumeWrite = db.runTransaction { tx ->
        if (tx.get(deletionFence(uid)).get().exists()) return@runTransaction ResumeWrite.AccountDeleting
        val reference = slot(uid)
        val stored = tx.get(reference).get().resumeRecord()
        val (outcome, updated) = stored.write(expectedRevision, operationId, game)
        if (updated != null) tx.set(reference, updated.fields())
        outcome
    }.await()

    override suspend fun deleteAccountData(uid: String) {
        db.runTransaction { tx ->
            val fence = deletionFence(uid)
            if (!tx.get(fence).get().exists()) tx.set(fence, mapOf("deleting" to true))
        }.await()
        // Once the fence commits, writers either observe it or their transactions retry. Each
        // batch can be repeated after a failure, including deletion of the slot and its receipts.
        while (true) {
            val page = history(uid).limit(200).get().await().documents
            if (page.isEmpty()) break
            val batch = db.batch()
            page.forEach { batch.delete(it.reference) }
            batch.commit().await()
        }
        slot(uid).delete().await()
    }
}

private fun CloudSavedGame.fields(): Map<String, Any> = mapOf(
    "deleted" to false,
    "id" to id,
    "savedAtEpochMillis" to savedAtEpochMillis,
    "result" to result,
    "white" to white,
    "black" to black,
    "moveCount" to moveCount,
    "payload" to Json.encodeToString(this),
)

private fun DocumentSnapshot.historyItem() = CloudHistoryItem(
    id = id,
    savedAtEpochMillis = requireNotNull(getLong("savedAtEpochMillis")),
    result = requireNotNull(getString("result")),
    white = requireNotNull(getString("white")),
    black = requireNotNull(getString("black")),
    moveCount = requireNotNull(getLong("moveCount")).toInt(),
)

private fun DocumentSnapshot.historyGame(): CloudSavedGame? =
    getString("payload")?.let { Json.decodeFromString<CloudSavedGame>(it) }

private fun ResumeRecord.fields(): Map<String, Any> = buildMap {
    put("revision", slot.revision)
    slot.game?.let { put("payload", Json.encodeToString(it)) }
    put("receipts", receipts.map { mapOf("id" to it.id, "requestHash" to it.requestHash, "revision" to it.revision) })
}

private fun DocumentSnapshot.resumeRecord(): ResumeRecord {
    if (!exists()) return ResumeRecord()
    val revision = requireNotNull(getLong("revision"))
    val game = getString("payload")?.let { Json.decodeFromString<CloudResumeGame>(it) }
    val receipts = (get("receipts") as? List<*>).orEmpty().map { value ->
        val receipt = value as Map<*, *>
        ResumeReceipt(
            receipt["id"] as String,
            receipt["requestHash"] as String,
            (receipt["revision"] as Number).toLong(),
        )
    }
    return ResumeRecord(StoredResume(revision, game), receipts)
}

private suspend fun <T> ApiFuture<T>.await(): T = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel(true) }
    addListener({
        try {
            continuation.resume(get())
        } catch (error: ExecutionException) {
            continuation.resumeWithException(error.cause ?: error)
        } catch (error: Exception) {
            continuation.resumeWithException(error)
        }
    }, MoreExecutors.directExecutor())
}
