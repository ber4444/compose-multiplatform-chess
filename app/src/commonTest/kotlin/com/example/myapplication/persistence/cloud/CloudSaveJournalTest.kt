package com.example.myapplication.persistence.cloud

import com.example.myapplication.persistence.MapSettings
import com.example.myapplication.GameSnapshot
import com.example.myapplication.Set
import com.example.saveapi.CloudResumeGame
import com.example.saveapi.CloudSavedGame
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue

class CloudSaveJournalTest {
    private val settings = MapSettings()
    private val game = CloudSavedGame("first", 2, "1-0", "White", "Black", 4, "pgn".repeat(4000))
    private val resume = CloudResumeGame("game", Set.BLACK, GameSnapshot("start"))

    @Test
    fun readsLargeJournalAfterRelaunchAndIsolatesAccount() {
        val journal = CloudSaveJournal(settings)
        val state = CloudJournal(history = listOf(HistoryWrite(game.id, game)))
        journal.write("user-a", state)

        assertEquals(state, CloudSaveJournal(settings).read("user-a"))
        assertTrue(CloudSaveJournal(settings).read("user-b").history.isEmpty())
        journal.clear("user-a")
        assertTrue(CloudSaveJournal(settings).read("user-a").history.isEmpty())
    }

    @Test
    fun interruptedCommitKeepsPreviousJournal() {
        val journal = CloudSaveJournal(settings)
        val state = CloudJournal(history = listOf(HistoryWrite(game.id, game)))
        journal.write("user", state)
        val failing = object : com.russhwolf.settings.Settings by settings {
            override fun putString(key: String, value: String) {
                error("simulated storage failure")
            }
        }

        assertFails { CloudSaveJournal(failing).write("user", CloudJournal()) }
        assertEquals(state, journal.read("user"))
    }

    @Test
    fun resumeCoalescesOnlyUnclaimedPositionsAndKeepsResetInOrder() {
        val first = ResumeWrite("1", resume, expectedRevision = 3)
        val second = ResumeWrite("2", resume.copy(snapshot = GameSnapshot("second")))
        val third = ResumeWrite("3", resume.copy(snapshot = GameSnapshot("third")))
        val clear = ResumeWrite("4", null)
        val newGame = ResumeWrite("5", resume.copy(gameId = "new"))

        val queue = listOf(first).enqueue(second).enqueue(third).enqueue(clear).enqueue(newGame)
        assertEquals(listOf(first, third, clear, newGame), queue)
    }
}
