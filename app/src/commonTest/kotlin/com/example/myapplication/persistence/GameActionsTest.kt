package com.example.myapplication.persistence

import com.example.myapplication.GameUiState
import com.example.myapplication.WinState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GameActionsTest {

    @Test
    fun `pgnTags names Stockfish when engine attached and CPU otherwise`() {
        val state = GameUiState(winState = WinState.WHITE)

        val withEngine = GameActions.pgnTags(state, engineAttached = true, date = "2026.07.02")
        assertEquals("Stockfish", withEngine.black)

        val withCpu = GameActions.pgnTags(state, engineAttached = false, date = "2026.07.02")
        assertEquals("CPU", withCpu.black)
    }

    @Test
    fun `pgnTags date and result come from state`() {
        val state = GameUiState(winState = WinState.DRAW)
        val tags = GameActions.pgnTags(state, engineAttached = false, date = "2026.07.02")
        assertEquals("2026.07.02", tags.date)
        assertEquals("1/2-1/2", tags.result)
        assertEquals("Player", tags.white)
    }

    @Test
    fun `toPgn produces a complete PGN with tags and movetext`() = kotlinx.coroutines.test.runTest {
        // A one-move game: 1.e4 then White "wins" (contrived for the test). The PGN must contain the
        // Seven Tag Roster and the e4 SAN.
        val vm = com.example.myapplication.GameViewModel()
        vm.moveCPU(com.example.myapplication.Set.WHITE) { _, _, _, _ ->
            com.example.myapplication.SelectedMove(
                kotlin.Pair(4, 4),
                vm.gameState.value.positionsWhite.indexOf(kotlin.Pair(6, 4))
            )
        }
        val finished = vm.gameState.value.copy(winState = WinState.WHITE)
        val pgn = GameActions.toPgn(finished, engineAttached = true, date = "2026.07.02")

        assertTrue("[White \"Player\"]" in pgn)
        assertTrue("[Black \"Stockfish\"]" in pgn)
        assertTrue("[Date \"2026.07.02\"]" in pgn)
        assertTrue("[Result \"1-0\"]" in pgn)
        assertTrue("e4" in pgn)
    }

    @Test
    fun `toSavedGame captures id result moveCount and pgn`() = kotlinx.coroutines.test.runTest {
        val vm = com.example.myapplication.GameViewModel()
        vm.moveCPU(com.example.myapplication.Set.WHITE) { _, _, _, _ ->
            com.example.myapplication.SelectedMove(
                kotlin.Pair(4, 4),
                vm.gameState.value.positionsWhite.indexOf(kotlin.Pair(6, 4))
            )
        }
        val finished = vm.gameState.value.copy(winState = WinState.WHITE)
        val saved = GameActions.toSavedGame(finished, engineAttached = true, savedAtEpochMillis = 1_700_000_000_000L)

        assertEquals("1700000000000", saved.id)
        assertEquals(1_700_000_000_000L, saved.savedAtEpochMillis)
        assertEquals("1-0", saved.result)
        assertEquals("Player", saved.white)
        assertEquals("Stockfish", saved.black)
        assertEquals(1, saved.moveCount)
        assertTrue("e4" in saved.pgn)
    }
}
