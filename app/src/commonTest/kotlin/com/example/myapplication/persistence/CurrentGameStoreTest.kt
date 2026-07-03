package com.example.myapplication.persistence

import com.example.myapplication.FenConverter
import com.example.myapplication.GameUiState
import com.example.myapplication.WinState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CurrentGameStoreTest {

    @Test
    fun `save then load round-trips a snapshot`() {
        val store = CurrentGameStore(MapSettings())
        val snapshot = GameSnapshot(
            fen = FenConverter.STARTING_FEN,
            winState = WinState.NONE,
            moveHistory = emptyList(),
            savedAtEpochMillis = 1_700_000_000_000L,
        )
        store.save(snapshot)

        val loaded = store.load()
        assertEquals(snapshot, loaded)
    }

    @Test
    fun `load returns null when nothing was saved`() {
        val store = CurrentGameStore(MapSettings())
        assertNull(store.load())
    }

    @Test
    fun `corrupt JSON returns null instead of throwing`() {
        val backing = MapSettings()
        backing.putString(CurrentGameStore.KEY, "{ this is not valid json")
        val store = CurrentGameStore(backing)
        assertNull(store.load())
    }

    @Test
    fun `clear removes the snapshot`() {
        val backing = MapSettings()
        val store = CurrentGameStore(backing)
        store.save(GameSnapshot(fen = FenConverter.STARTING_FEN, winState = WinState.NONE))
        assertTrue(backing.hasKey(CurrentGameStore.KEY))

        store.clear()
        assertFalse(backing.hasKey(CurrentGameStore.KEY))
        assertNull(store.load())
    }

    @Test
    fun `version key is v1`() {
        assertEquals("current_game.v1", CurrentGameStore.KEY)
    }

    @Test
    fun `ignoreUnknownKeys lets newer snapshots load from older readers`() {
        // An older reader that doesn't know about (say) a future `newField` shouldn't crash.
        val backing = MapSettings()
        backing.putString(
            CurrentGameStore.KEY,
            """{"fen":"${FenConverter.STARTING_FEN}","moveHistory":[],"positionHistory":[],"winState":"NONE","futureField":42}"""
        )
        val loaded = CurrentGameStore(backing).load()
        assertEquals(FenConverter.STARTING_FEN, loaded?.fen)
    }
}

class CurrentGameStoreSupportTest {

    @Test
    fun `loadInitialState restores an in-progress game`() {
        val store = CurrentGameStore(MapSettings())
        store.save(GameSnapshot(fen = FenConverter.STARTING_FEN, winState = WinState.NONE))

        val initial = CurrentGameStoreSupport.loadInitialState(store)
        assertFalse(initial.shouldClear)
        // The restored state matches the saved FEN. (GameUiState.equals is identity-based on Piece
        // instances, so compare via FEN — the lossless board representation.)
        assertEquals(FenConverter.STARTING_FEN, FenConverter.gameStateToFen(initial.state))
    }

    @Test
    fun `loadInitialState starts fresh and asks to clear a finished game`() {
        val store = CurrentGameStore(MapSettings())
        store.save(GameSnapshot(fen = FenConverter.STARTING_FEN, winState = WinState.WHITE))

        val initial = CurrentGameStoreSupport.loadInitialState(store)
        assertTrue(initial.shouldClear)
        // Fresh state — compare via FEN since GameUiState.equals is identity-based on pieces.
        assertEquals(FenConverter.STARTING_FEN, FenConverter.gameStateToFen(initial.state))
    }

    @Test
    fun `loadInitialState returns a fresh game when nothing is saved`() {
        val store = CurrentGameStore(MapSettings())
        val initial = CurrentGameStoreSupport.loadInitialState(store)
        assertFalse(initial.shouldClear)
        assertEquals(FenConverter.STARTING_FEN, FenConverter.gameStateToFen(initial.state))
    }
}
