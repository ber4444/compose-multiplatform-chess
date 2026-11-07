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
        sync.resolveResume(useCloud = true) { restored = it }
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

    private fun TestScope.sync(transport: TestCloudTransport) =
        CloudSaveSync(TestCloudIdentity, transport, CloudSaveJournal(MapSettings()), false, true, StandardTestDispatcher(testScheduler))
}
