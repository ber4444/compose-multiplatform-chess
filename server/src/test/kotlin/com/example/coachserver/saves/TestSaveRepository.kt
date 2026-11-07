package com.example.coachserver.saves

import com.example.saveapi.CloudHistoryPage
import com.example.saveapi.CloudResumeGame
import com.example.saveapi.CloudSavedGame

internal class TestSaveRepository : CloudSaveRepository {
    private val owners = mutableMapOf<String, Owner>()

    private class Owner {
        val history = mutableMapOf<String, CloudSavedGame>()
        val tombstones = mutableSetOf<String>()
        var resume = ResumeRecord()
        var deleting = false
    }

    private fun owner(uid: String): Owner = owners.getOrPut(uid, ::Owner)

    override suspend fun isDeleting(uid: String): Boolean = synchronized(owners) { owner(uid).deleting }

    override suspend fun history(uid: String, after: CloudHistoryCursor?): CloudHistoryPage = synchronized(owners) {
        val items = owner(uid).history.values.map { it.metadata() }
            .sortedWith(compareByDescending<com.example.saveapi.CloudHistoryItem> { it.savedAtEpochMillis }.thenByDescending { it.id })
            .filter { after == null || it.savedAtEpochMillis < after.savedAtEpochMillis ||
                it.savedAtEpochMillis == after.savedAtEpochMillis && it.id < after.id }
        historyPage(items)
    }

    override suspend fun historyGame(uid: String, id: String): CloudSavedGame? = synchronized(owners) {
        owner(uid).history[id]
    }

    override suspend fun saveHistory(uid: String, game: CloudSavedGame): HistoryWrite = synchronized(owners) {
        val owner = owner(uid)
        if (owner.deleting) return@synchronized HistoryWrite.AccountDeleting
        val result = decideHistoryWrite(owner.history[game.id], game.id in owner.tombstones, game)
        if (result == HistoryWrite.Saved) owner.history.putIfAbsent(game.id, game)
        result
    }

    override suspend fun deleteHistory(uid: String, id: String): HistoryWrite = synchronized(owners) {
        val owner = owner(uid)
        if (owner.deleting) return@synchronized HistoryWrite.AccountDeleting
        owner.history.remove(id)
        owner.tombstones.add(id)
        HistoryWrite.Saved
    }

    override suspend fun resume(uid: String): StoredResume = synchronized(owners) { owner(uid).resume.slot }

    override suspend fun writeResume(
        uid: String,
        expectedRevision: Long,
        operationId: String,
        game: CloudResumeGame?,
    ): ResumeWrite = synchronized(owners) {
        val owner = owner(uid)
        if (owner.deleting) return@synchronized ResumeWrite.AccountDeleting
        val (result, updated) = owner.resume.write(expectedRevision, operationId, game)
        if (updated != null) owner.resume = updated
        result
    }

    override suspend fun deleteAccountData(uid: String) = synchronized(owners) {
        val owner = owner(uid)
        owner.deleting = true
        owner.history.clear()
        owner.tombstones.clear()
        owner.resume = ResumeRecord()
    }
}
