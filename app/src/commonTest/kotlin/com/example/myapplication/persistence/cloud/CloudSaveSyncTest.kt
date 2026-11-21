package com.example.myapplication.persistence.cloud

import com.example.myapplication.persistence.MapSettings
import com.example.myapplication.persistence.SavedGame
import com.example.saveapi.CloudAccount
import com.example.saveapi.CloudHistoryItem
import com.example.saveapi.CloudHistoryPage
import com.example.saveapi.CloudSavedGame
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class CloudSaveSyncTest {
    @Test
    fun pendingHistoryIsRetriedAfterRelaunchOnlyForItsOwner() = runTest {
        val settings = MapSettings()
        val transport = TestCloudTransport()
        val first = sync(settings, transport)
        first.signIn("alice", "password")
        runCurrent()
        transport.offline = true
        assertTrue(first.saveFinished(localGame()))
        runCurrent()
        assertEquals(1, first.state.value.pendingHistory)
        first.close()

        val second = sync(settings, transport)
        transport.offline = false
        second.signIn("bob", "password")
        runCurrent()
        assertTrue(transport.historyByOwner.isEmpty())
        assertTrue(second.state.value.history.isEmpty())

        second.signOut()
        second.signIn("alice", "password")
        runCurrent()
        assertEquals(1, transport.historyByOwner["alice"]?.size)
        assertEquals(0, second.state.value.pendingHistory)
        second.close()
    }

    @Test
    fun failedRefreshLeavesPreviousListingAndPendingDeleteHidesIt() = runTest {
        val transport = TestCloudTransport()
        val sync = sync(MapSettings(), transport)
        sync.signIn("alice", "password")
        runCurrent()
        assertTrue(sync.saveFinished(localGame()))
        runCurrent()
        val id = sync.state.value.history.single().item.id

        transport.offline = true
        sync.refresh()
        runCurrent()
        assertEquals(id, sync.state.value.history.single().item.id)
        sync.deleteHistory(id)
        runCurrent()
        assertTrue(sync.state.value.history.isEmpty())
        assertEquals(1, sync.state.value.pendingHistory)
        sync.close()
    }

    @Test
    fun lichessRecordsNeverReachSoloSync() = runTest {
        val transport = TestCloudTransport()
        val sync = sync(MapSettings(), transport)
        sync.signIn("alice", "password")
        runCurrent()
        val online = localGame().copy(lichessGameId = "abcdefgh")
        assertFalse(sync.saveFinished(online))
        sync.importLocalGames(listOf(online))
        runCurrent()
        assertTrue(transport.historyByOwner.isEmpty())
        sync.close()
    }

    private fun TestScope.sync(settings: MapSettings, transport: TestCloudTransport) =
        CloudSaveSync(TestCloudIdentity, transport, CloudSaveJournal(settings), true, false, StandardTestDispatcher(testScheduler))

    private fun localGame() = SavedGame("1", 1, "1-0", "White", "Black", 5, "pgn")
}

internal object TestCloudIdentity : CloudIdentity {
    override suspend fun signIn(email: String, password: String) =
        FirebaseSession(email, email, "refresh", Long.MAX_VALUE, true)
    override suspend fun register(email: String, password: String) = Unit
    override suspend fun resetPassword(email: String) = Unit
    override suspend fun sendVerification(session: FirebaseSession) = Unit
    override suspend fun refreshIfNeeded(session: FirebaseSession): FirebaseSession = session
}

internal class TestCloudTransport : CloudSaveTransport {
    val historyByOwner = mutableMapOf<String, MutableMap<String, CloudSavedGame>>()
    var remote = RemoteResume(null, 0)
    var offline = false
    var loseResumeAcknowledgement = false
    val acceptedResumeOperations = mutableMapOf<String, Long>()

    override suspend fun account(token: String) = CloudAccount(token)
    override suspend fun deleteAccount(token: String) { historyByOwner.remove(token) }
    override suspend fun history(token: String, cursor: String?): CloudHistoryPage {
        requireOnline()
        return CloudHistoryPage(historyByOwner[token].orEmpty().values.map {
            CloudHistoryItem(it.id, it.savedAtEpochMillis, it.result, it.white, it.black, it.moveCount)
        })
    }

    override suspend fun game(token: String, id: String): CloudSavedGame =
        checkNotNull(historyByOwner[token]?.get(id))

    override suspend fun writeHistory(token: String, write: HistoryWrite) {
        requireOnline()
        val games = historyByOwner.getOrPut(token) { mutableMapOf() }
        if (write.game == null) games.remove(write.id) else games[write.id] = write.game
    }

    override suspend fun current(token: String): RemoteResume {
        requireOnline()
        return remote
    }

    override suspend fun writeResume(token: String, write: ResumeWrite): Long {
        requireOnline()
        acceptedResumeOperations[write.operationId]?.let { return it }
        if (write.expectedRevision != remote.revision) throw CloudSaveException(412, "stale_revision")
        remote = RemoteResume(write.game, remote.revision + 1)
        acceptedResumeOperations[write.operationId] = remote.revision
        if (loseResumeAcknowledgement) {
            loseResumeAcknowledgement = false
            throw CloudSaveException(503)
        }
        return remote.revision
    }

    private fun requireOnline() {
        if (offline) throw CloudSaveException(503)
    }
}
