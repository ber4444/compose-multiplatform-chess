package com.example.myapplication

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.myapplication.persistence.CurrentGameStore
import com.example.myapplication.persistence.createSettings
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Phase 2 instrumented test: drives the autosave → restore path on the real Android
 * `SharedPreferences` backend. No engine is attached (CPU fallback only) so the moves are
 * deterministic. Mirrors the commonTest `GameViewModelTest` VM round-trip but exercises the actual
 * `createSettings` Android actual (which reads `ChessApplication`'s appContext).
 */
@RunWith(AndroidJUnit4::class)
class AutoSaveRestoreTest {

    @Test
    fun playMovesThenRestoreFromStoreReproducesBoard() = runBlocking {
        val store = CurrentGameStore(createSettings("chess"))

        // Start clean.
        store.clear()
        val vmA = GameViewModel(currentGameStore = store)

        // 1.e4 — CPU WHITE move (no engine; deterministic SelectedMove).
        vmA.moveCPU(Set.WHITE) { _, _, _, _ ->
            val from = vmA.gameState.value.positionsWhite.indexOf(Pair(6, 4))
            SelectedMove(Pair(4, 4), from)
        }
        // 1...e5
        vmA.moveCPU(Set.BLACK) { _, _, _, _ ->
            val from = vmA.gameState.value.positionsBlack.indexOf(Pair(1, 4))
            SelectedMove(Pair(3, 4), from)
        }

        // Autosave fired after each move — the snapshot is on disk.
        val snapshot = store.load()
        assertNotNull(snapshot)
        assertEquals(2, snapshot!!.moveHistory.size)

        // Simulate process death: build a fresh VM from the restored state (exactly what
        // AndroidGameViewModel does on a cold start).
        val restored = com.example.myapplication.persistence.GameSnapshotMapper.toState(snapshot)
        val vmB = GameViewModel(restored)

        assertEquals(vmA.gameState.value.positionsWhite, vmB.gameState.value.positionsWhite)
        assertEquals(vmA.gameState.value.positionsBlack, vmB.gameState.value.positionsBlack)
        assertEquals(
            vmA.gameState.value.moveHistory.map { it.san },
            vmB.gameState.value.moveHistory.map { it.san },
        )

        store.clear()
    }

    @Test
    fun resetGameClearsTheStore() = runBlocking {
        val store = CurrentGameStore(createSettings("chess"))
        store.clear()
        val vm = GameViewModel(currentGameStore = store)

        vm.moveCPU(Set.WHITE) { _, _, _, _ ->
            val from = vm.gameState.value.positionsWhite.indexOf(Pair(6, 4))
            SelectedMove(Pair(4, 4), from)
        }
        assertTrue(store.load() != null)

        vm.resetGame()
        assertEquals(null, store.load())
    }
}
