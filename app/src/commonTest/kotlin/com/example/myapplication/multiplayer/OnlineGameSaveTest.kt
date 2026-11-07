package com.example.myapplication.multiplayer

import com.example.myapplication.GameUiState
import com.example.myapplication.Set
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OnlineGameSaveTest {
    @Test
    fun usesAuthoritativeResultForResignationAndFlag() {
        val game = match("resign", "black")
        val saved = assertNotNull(game.savedGame())
        assertEquals("game0001", saved.lichessGameId)
        assertEquals("0-1", saved.result)
        assertTrue(saved.pgn.contains("[White \"Alice\"]"))
        assertTrue(saved.pgn.contains("[Black \"Bob\"]"))
        assertTrue(saved.pgn.trimEnd().endsWith("0-1"))
        assertEquals("1-0", match("outoftime", "white").savedGame()?.result)
        assertEquals("1/2-1/2", match("draw", null).savedGame()?.result)
        assertNull(match("started", null).savedGame())
        assertNull(match("aborted", null).savedGame())
    }

    private fun match(status: String, winner: String?) = OnlineGame(
        id = "game0001",
        playerSide = Set.BLACK,
        board = GameUiState(),
        state = LichessGameState("", 60_000, 60_000, 10_000, 10_000, status, winner),
        white = LichessPlayer(name = "Alice"),
        black = LichessPlayer(name = "Bob"),
        initialFen = "startpos",
        createdAt = 1_750_000_000_000,
        initialClockMillis = 900_000,
        clockIncrementMillis = 10_000,
    )
}
