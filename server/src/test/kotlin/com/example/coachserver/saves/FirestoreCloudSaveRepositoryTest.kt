package com.example.coachserver.saves

import com.example.myapplication.GameSnapshot
import com.example.myapplication.Set
import com.example.saveapi.CloudResumeGame
import com.example.saveapi.CloudSavedGame
import com.google.cloud.firestore.FirestoreOptions
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable

@EnabledIfEnvironmentVariable(named = "FIRESTORE_EMULATOR_HOST", matches = "(localhost|127\\.0\\.0\\.1):[0-9]+")
class FirestoreCloudSaveRepositoryTest {
    @Test
    fun concurrentWritesRespectRevisionsAndAccountDeletion() = runBlocking {
        val db = FirestoreOptions.newBuilder()
            .setProjectId("demo-chess-saves")
            .setEmulatorHost(checkNotNull(System.getenv("FIRESTORE_EMULATOR_HOST")))
            .build().service
        db.use {
            val saves = FirestoreCloudSaveRepository(it)
            val uid = UUID.randomUUID().toString()
            val snapshot = GameSnapshot("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1")
            val first = CloudResumeGame("match-a", Set.WHITE, snapshot)
            val second = first.copy(gameId = "match-b")
            val firstOperation = UUID.randomUUID().toString()
            val secondOperation = UUID.randomUUID().toString()
            val outcomes = awaitAll(
                async { saves.writeResume(uid, 0, firstOperation, first) },
                async { saves.writeResume(uid, 0, secondOperation, second) },
            )
            assertEquals(1, outcomes.count { outcome -> outcome == ResumeWrite.Saved(1) })
            assertEquals(1, outcomes.count { outcome -> outcome == ResumeWrite.Stale })
            val winner = assertNotNull(saves.resume(uid).game)
            val operation = if (winner == first) firstOperation else secondOperation
            assertEquals(ResumeWrite.Saved(1), saves.writeResume(uid, 0, operation, winner))

            val game = CloudSavedGame("saved", 1, "1-0", "White", "Black", 1, "[Result \"1-0\"]")
            awaitAll(async { saves.saveHistory(uid, game) }, async { saves.deleteHistory(uid, game.id) })
            assertEquals(HistoryWrite.Deleted, saves.saveHistory(uid, game))
            assertTrue(saves.history(uid, null).items.isEmpty())

            awaitAll(
                async { saves.writeResume(uid, 1, UUID.randomUUID().toString(), winner); Unit },
                async { saves.deleteAccountData(uid) },
            )
            assertEquals(null, saves.resume(uid).game)
            assertEquals(ResumeWrite.AccountDeleting, saves.writeResume(uid, 0, UUID.randomUUID().toString(), winner))
        }
    }
}
