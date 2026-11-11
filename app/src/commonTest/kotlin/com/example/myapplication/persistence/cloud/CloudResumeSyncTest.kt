package com.example.myapplication.persistence.cloud

import com.example.myapplication.GameSnapshot
import com.example.myapplication.Set
import com.example.myapplication.persistence.MapSettings
import com.example.saveapi.CloudResumeGame
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class CloudResumeSyncTest {
    @Test
    fun signingInOffersCloudGameWithoutReplacingLocalAndPreservesTheOtherCopy() = runTest {
        val cloud = CloudResumeGame("remote", Set.BLACK, GameSnapshot("cloud"))
        val transport = TestCloudTransport().apply { remote = RemoteResume(cloud, 5) }
        val sync = sync(transport)
        sync.seedLocalSnapshot(GameSnapshot("device"), Set.WHITE)
        sync.signIn("alice", "password")
        runCurrent()

        val conflict = assertNotNull(sync.state.value.resumeConflict)
        assertEquals("device", conflict.local?.snapshot?.fen)
        assertEquals(cloud, conflict.remote.game)
        assertEquals(cloud, transport.remote.game)

        var restored: CloudResumeGame? = null
        sync.resolveResume(useCloud = true) { restored = it; true }
        runCurrent()
        assertEquals(Set.BLACK, restored?.playerSide)
        assertEquals("device", sync.state.value.recovery?.snapshot?.fen)
        assertEquals(5L, transport.remote.revision)
        assertNull(sync.state.value.resumeConflict)
        sync.close()
    }

    @Test
    fun retriesLostAcknowledgementWithoutAdvancingTwice() = runTest {
        val transport = TestCloudTransport()
        val sync = sync(transport)
        sync.seedLocalSnapshot(null, Set.WHITE)
        sync.signIn("alice", "password")
        runCurrent()
        transport.loseResumeAcknowledgement = true
        sync.recordSnapshot(GameSnapshot("new move"), Set.BLACK)
        runCurrent()
        assertEquals(1L, transport.remote.revision)
        assertEquals(1, sync.state.value.pendingResume)

        sync.refresh()
        runCurrent()
        assertEquals(1L, transport.remote.revision)
        assertEquals(0, sync.state.value.pendingResume)
        sync.close()
    }

    @Test
    fun staleOfflineResetCannotClearAnUpdatedRemoteGame() = runTest {
        val transport = TestCloudTransport()
        val sync = sync(transport)
        sync.seedLocalSnapshot(GameSnapshot("start"), Set.WHITE)
        sync.signIn("alice", "password")
        runCurrent()
        sync.resolveResume(false) { true }
        runCurrent()
        val previous = assertNotNull(transport.remote.game)
        transport.offline = true
        sync.recordSnapshot(null, Set.WHITE)
        runCurrent()

        transport.remote = RemoteResume(previous.copy(snapshot = GameSnapshot("advanced")), 2)
        transport.offline = false
        sync.refresh()
        runCurrent()
        assertEquals("advanced", transport.remote.game?.snapshot?.fen)
        assertNotNull(sync.state.value.resumeConflict)
        sync.close()
    }

    @Test
    fun restoredGameCannotUploadIntoAnotherAccountAfterRestart() = runTest {
        val store = MapSettings()
        val remote = CloudResumeGame("alice-game", Set.BLACK, GameSnapshot("private position"))
        val transport = TestCloudTransport().apply { this.remote = RemoteResume(remote, 3) }
        val alice = sync(transport, store)
        alice.signIn("alice", "password")
        runCurrent()
        alice.resolveResume(true) { true }
        runCurrent()
        alice.close()

        transport.remote = RemoteResume(null, 0)
        val bob = sync(transport, store)
        bob.seedLocalSnapshot(remote.snapshot, remote.playerSide)
        bob.signIn("bob", "password")
        runCurrent()
        bob.recordSnapshot(GameSnapshot("private position after another move"), Set.BLACK)
        runCurrent()
        assertEquals(RemoteResume(null, 0), transport.remote)
        bob.close()
    }

    @Test
    fun failedLocalRestoreKeepsConflictAndCloudRevision() = runTest {
        val remote = CloudResumeGame("remote", Set.BLACK, GameSnapshot("remote"))
        val transport = TestCloudTransport().apply { this.remote = RemoteResume(remote, 5) }
        val sync = sync(transport)
        sync.seedLocalSnapshot(GameSnapshot("local"), Set.WHITE)
        sync.signIn("alice", "password")
        runCurrent()
        sync.resolveResume(true) { false }
        runCurrent()
        assertNotNull(sync.state.value.resumeConflict)
        assertEquals(RemoteResume(remote, 5), transport.remote)
        sync.close()
    }

    @Test
    fun signingInWithResumeDisabledKeepsItsQueuedChanges() = runTest {
        val store = MapSettings()
        val journal = CloudSaveJournal(store)
        val game = CloudResumeGame("queued", Set.WHITE, GameSnapshot("queued"))
        val queued = ResumeWrite("operation", game)
        journal.write("alice", CloudJournal(resumeWrites = listOf(queued)))
        val sync = CloudSaveSync(
            TestCloudIdentity, TestCloudTransport(), journal, true, false, StandardTestDispatcher(testScheduler),
        )
        sync.signIn("alice", "password")
        runCurrent()
        assertEquals(listOf(queued), journal.read("alice").resumeWrites)
        sync.close()
    }

    private fun TestScope.sync(transport: TestCloudTransport, store: MapSettings = MapSettings()) =
        CloudSaveSync(TestCloudIdentity, transport, CloudSaveJournal(store), false, true, StandardTestDispatcher(testScheduler))
}
