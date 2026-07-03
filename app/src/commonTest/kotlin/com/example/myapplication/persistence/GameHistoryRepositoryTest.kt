package com.example.myapplication.persistence

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GameHistoryRepositoryTest {

    private fun savedGame(id: String, millis: Long = id.toLong()) = SavedGame(
        id = id,
        savedAtEpochMillis = millis,
        result = "1-0",
        white = "Player",
        black = "Stockfish",
        moveCount = 10,
        pgn = "[Event \"Casual Game\"]\n...",
    )

    @Test
    fun `starts empty when nothing is saved`() {
        val repo = GameHistoryRepository(MapSettings())
        assertTrue(repo.games.value.isEmpty())
    }

    @Test
    fun `add prepends and the flow reflects newest first`() {
        val repo = GameHistoryRepository(MapSettings())
        repo.add(savedGame("1", 1_000))
        repo.add(savedGame("2", 2_000))
        repo.add(savedGame("3", 3_000))

        assertEquals(listOf("3", "2", "1"), repo.games.value.map { it.id })
    }

    @Test
    fun `add persists so a fresh repo over the same backing reads it`() {
        val backing = MapSettings()
        GameHistoryRepository(backing).add(savedGame("7", 7_000))

        val reloaded = GameHistoryRepository(backing)
        assertEquals(listOf("7"), reloaded.games.value.map { it.id })
    }

    @Test
    fun `delete removes the matching id and updates the flow`() {
        val repo = GameHistoryRepository(MapSettings())
        repo.add(savedGame("1"))
        repo.add(savedGame("2"))
        repo.add(savedGame("3"))

        repo.delete("2")

        assertEquals(listOf("3", "1"), repo.games.value.map { it.id })
        assertFalse(repo.games.value.any { it.id == "2" })
    }

    @Test
    fun `delete of a missing id is a no-op`() {
        val repo = GameHistoryRepository(MapSettings())
        repo.add(savedGame("1"))
        repo.delete("does-not-exist")
        assertEquals(listOf("1"), repo.games.value.map { it.id })
    }

    @Test
    fun `cap evicts the oldest entries past MAX_GAMES`() {
        val repo = GameHistoryRepository(MapSettings())
        // Fill to the cap.
        repeat(GameHistoryRepository.MAX_GAMES) { repo.add(savedGame(it.toString(), it.toLong())) }
        assertEquals(GameHistoryRepository.MAX_GAMES, repo.games.value.size)
        // The newest is the last added (id = MAX_GAMES - 1).
        assertEquals((GameHistoryRepository.MAX_GAMES - 1).toString(), repo.games.value.first().id)

        // One more should evict the oldest (id = "0").
        repo.add(savedGame("overflow", 1_000_000L))
        assertEquals(GameHistoryRepository.MAX_GAMES, repo.games.value.size)
        assertFalse(repo.games.value.any { it.id == "0" })
        assertEquals("overflow", repo.games.value.first().id)
    }

    @Test
    fun `corrupt blob yields an empty list instead of throwing`() {
        val backing = MapSettings()
        backing.putString(GameHistoryRepository.KEY, "{ not valid json")
        val repo = GameHistoryRepository(backing)
        assertTrue(repo.games.value.isEmpty())
    }

    @Test
    fun `version key is v1`() {
        assertEquals("game_history.v1", GameHistoryRepository.KEY)
    }
}
