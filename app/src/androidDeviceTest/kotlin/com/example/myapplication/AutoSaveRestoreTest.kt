package com.example.myapplication

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.myapplication.persistence.CurrentGameStore
import com.example.myapplication.persistence.GameSnapshotMapper
import com.russhwolf.settings.SharedPreferencesSettings
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Phase 2 instrumented test: drives the autosave → restore path on the real Android
 * `SharedPreferences` backend. No engine is attached (CPU fallback only) so the moves are
 * deterministic.
 *
 * The store is built directly from the instrumentation [Context]'s SharedPreferences rather than
 * via `createSettings()`: the latter reads the process-wide `appContext` lateinit populated by
 * `ChessApplication.onCreate`, but the test runner uses the default `Application`, not
 * `ChessApplication`, so `appContext` is never initialized. Building `SharedPreferencesSettings`
 * from [ApplicationProvider.getApplicationContext] keeps the test self-contained while still
 * exercising the real backend.
 */
@RunWith(AndroidJUnit4::class)
class AutoSaveRestoreTest {

    private fun newStore(): CurrentGameStore {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val prefs = context.getSharedPreferences("chess_test_${System.nanoTime()}", android.content.Context.MODE_PRIVATE)
        return CurrentGameStore(SharedPreferencesSettings(prefs))
    }

    @Test
    fun playMovesThenRestoreFromStoreReproducesBoard() = runBlocking {
        val store = newStore()
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

        // Autosave fired after each move — the snapshot is persisted.
        val snapshot = store.load()
        assertNotNull(snapshot)
        assertEquals(2, snapshot!!.moveHistory.size)

        // Simulate process death: build a fresh VM from the restored state (exactly what
        // AndroidGameViewModel does on a cold start).
        val restored = GameSnapshotMapper.toState(snapshot)
        val vmB = GameViewModel(restored)

        // Compare via FEN (lossless board) + SAN list — GameUiState's auto-generated equals is
        // identity-based on Piece instances, so it can't see that two Rook(WHITE)s are "the same".
        assertEquals(
            FenConverter.gameStateToFen(vmA.gameState.value),
            FenConverter.gameStateToFen(vmB.gameState.value),
        )
        assertEquals(
            vmA.gameState.value.moveHistory.map { it.san },
            vmB.gameState.value.moveHistory.map { it.san },
        )
    }

    @Test
    fun resetGameClearsTheStore() = runBlocking {
        val store = newStore()
        val vm = GameViewModel(currentGameStore = store)

        vm.moveCPU(Set.WHITE) { _, _, _, _ ->
            val from = vm.gameState.value.positionsWhite.indexOf(Pair(6, 4))
            SelectedMove(Pair(4, 4), from)
        }
        assertNotNull(store.load())

        vm.resetGame()
        assertNull(store.load())
    }
}
