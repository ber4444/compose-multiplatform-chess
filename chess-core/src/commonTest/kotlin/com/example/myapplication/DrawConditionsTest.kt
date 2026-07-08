package com.example.myapplication

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DrawConditionsTest {

    @Test
    fun `quiet moves increment clock fullmove after Black`() = kotlinx.coroutines.test.runTest {
        val vm = GameViewModel()
        vm.playerMove(6, Pair(5, 5)) // Ng1-f3
        assertEquals(1, vm.gameState.value.halfmoveClock)
        assertEquals(1, vm.gameState.value.fullmoveNumber)
        
        vm.moveCPU(Set.BLACK) { _, _, _, _ -> SelectedMove(Pair(2, 0), 1) } // Nb8-a6
        assertEquals(2, vm.gameState.value.halfmoveClock)
        assertEquals(2, vm.gameState.value.fullmoveNumber)
        assertTrue(FenConverter.gameStateToFen(vm.gameState.value).endsWith(" 2 2"))
    }

    @Test
    fun `pawn move resets clock`() {
        val vm = GameViewModel()
        vm.playerMove(12, Pair(4, 4)) // e2-e4
        assertEquals(0, vm.gameState.value.halfmoveClock)
        assertEquals(1, vm.gameState.value.positionHistory.size)
    }

    @Test
    fun `capture resets clock and history`() {
        val vm = GameViewModel(FenConverter.fenToGameState("4k3/8/8/3p4/8/8/8/3RK3 w - - 5 10"))
        val rookIdx = vm.gameState.value.positionsWhite.indexOf(Pair(7, 3))
        vm.playerMove(rookIdx, Pair(3, 3)) // Rxd5
        assertEquals(0, vm.gameState.value.halfmoveClock)
        assertEquals(1, vm.gameState.value.positionHistory.size)
        assertEquals(WinState.NONE, vm.gameState.value.winState)
        assertEquals(10, vm.gameState.value.fullmoveNumber)
    }

    @Test
    fun `en passant capture resets clock`() {
        val vm = GameViewModel(FenConverter.fenToGameState("rnbqkbnr/ppp1pppp/8/3pP3/8/8/PPPP1PPP/RNBQKBNR w KQkq d6 7 3"))
        val pawnIdx = vm.gameState.value.positionsWhite.indexOf(Pair(3, 4))
        vm.playerMove(pawnIdx, Pair(2, 3))
        assertEquals(0, vm.gameState.value.halfmoveClock)
        assertEquals(1, vm.gameState.value.positionHistory.size)
    }

    @Test
    fun `fifty-move draw at clock 100`() {
        val vm = GameViewModel(FenConverter.fenToGameState("4k2r/8/8/8/8/8/8/4K2R w - - 99 80"))
        val rookIdx = vm.gameState.value.positionsWhite.indexOf(Pair(7, 7))
        vm.playerMove(rookIdx, Pair(4, 7)) // Rh1-h4, quiet, no check
        assertEquals(100, vm.gameState.value.halfmoveClock)
        assertEquals(WinState.DRAW, vm.gameState.value.winState)
    }

    @Test
    fun `no draw below 100`() {
        val vm = GameViewModel(FenConverter.fenToGameState("4k2r/8/8/8/8/8/8/4K2R w - - 98 80"))
        val rookIdx = vm.gameState.value.positionsWhite.indexOf(Pair(7, 7))
        vm.playerMove(rookIdx, Pair(4, 7)) // Rh1-h4
        assertEquals(99, vm.gameState.value.halfmoveClock)
        assertEquals(WinState.NONE, vm.gameState.value.winState)
    }

    @Test
    fun `fifty-move deferred while in check`() = kotlinx.coroutines.test.runTest {
        val vm = GameViewModel(FenConverter.fenToGameState("4k3/8/8/8/8/8/8/4K2R w - - 99 80"))
        val rookIdx = vm.gameState.value.positionsWhite.indexOf(Pair(7, 7))
        vm.playerMove(rookIdx, Pair(0, 7)) // Rh1-h8+
        assertEquals(100, vm.gameState.value.halfmoveClock)
        assertEquals(WinState.NONE, vm.gameState.value.winState)
        assertTrue(vm.gameState.value.inCheckBlack)

        vm.moveCPU(Set.BLACK) { _, _, _, _ -> SelectedMove(Pair(1, 3), 0) } // Ke8-d7
        assertEquals(101, vm.gameState.value.halfmoveClock)
        assertEquals(WinState.DRAW, vm.gameState.value.winState)
    }

    @Test
    fun `pawn move at 99 prevents draw`() {
        val vm = GameViewModel(FenConverter.fenToGameState("4k3/8/8/8/8/8/4P3/4K3 w - - 99 80"))
        val pawnIdx = vm.gameState.value.positionsWhite.indexOf(Pair(6, 4))
        vm.playerMove(pawnIdx, Pair(5, 4))
        assertEquals(0, vm.gameState.value.halfmoveClock)
        assertEquals(WinState.NONE, vm.gameState.value.winState)
    }

    @Test
    fun `threefold via knight shuffle`() = kotlinx.coroutines.test.runTest {
        val vm = GameViewModel()
        
        // Cycle 1
        vm.playerMove(6, Pair(5, 5))
        vm.moveCPU(Set.BLACK) { _, _, _, _ -> SelectedMove(Pair(2, 5), 6) }
        vm.playerMove(6, Pair(7, 6))
        vm.moveCPU(Set.BLACK) { _, _, _, _ -> SelectedMove(Pair(0, 6), 6) }
        
        assertEquals(WinState.NONE, vm.gameState.value.winState) // 2nd occurrence (incl start)
        
        // Cycle 2
        vm.playerMove(6, Pair(5, 5))
        vm.moveCPU(Set.BLACK) { _, _, _, _ -> SelectedMove(Pair(2, 5), 6) }
        vm.playerMove(6, Pair(7, 6))
        vm.moveCPU(Set.BLACK) { _, _, _, _ -> SelectedMove(Pair(0, 6), 6) }
        
        assertEquals(WinState.DRAW, vm.gameState.value.winState) // 3rd occurrence
    }

    @Test
    fun `repetition key includes castling rights`() = kotlinx.coroutines.test.runTest {
        val vm = GameViewModel(FenConverter.fenToGameState("r3k2r/8/8/8/8/8/8/R3K2R w KQkq - 0 1"))
        
        repeat(2) {
            vm.playerMove(2, Pair(6, 7)) // Rh1-h2
            vm.moveCPU(Set.BLACK) { _, _, _, _ -> SelectedMove(Pair(1, 7), 2) } // Rh8-h7
            vm.playerMove(2, Pair(7, 7)) // Rh2-h1
            vm.moveCPU(Set.BLACK) { _, _, _, _ -> SelectedMove(Pair(0, 7), 2) } // Rh7-h8
        }
        assertEquals(WinState.NONE, vm.gameState.value.winState) // only 2 matches because first had different castling rights
        
        vm.playerMove(2, Pair(6, 7))
        vm.moveCPU(Set.BLACK) { _, _, _, _ -> SelectedMove(Pair(1, 7), 2) }
        assertEquals(WinState.DRAW, vm.gameState.value.winState) // 3rd occurrence
    }

    @Test
    fun `insufficient material positives`() {
        val fens = listOf(
            "4k3/8/8/8/8/8/8/4K3 w - - 0 1",
            "4k3/8/8/8/8/8/8/2B1K3 w - - 0 1",
            "4k3/8/8/8/8/8/8/1N2K3 w - - 0 1",
            "4kn2/8/8/8/8/8/8/4K3 w - - 0 1",
            "4kb2/8/8/8/8/8/8/2B1K3 w - - 0 1"
        )
        for (f in fens) {
            assertTrue(isInsufficientMaterial(FenConverter.fenToGameState(f)), "Failed for $f")
        }
    }

    @Test
    fun `insufficient material negatives`() {
        val fens = listOf(
            "2b1k3/8/8/8/8/8/8/2B1K3 w - - 0 1",
            "1n2k3/8/8/8/8/8/8/1N2K3 w - - 0 1",
            "4k3/8/8/8/8/8/8/R3K3 w - - 0 1",
            "4k3/8/8/8/8/4P3/8/4K3 w - - 0 1",
            "4k3/8/8/8/8/8/8/1NB1K3 w - - 0 1"
        )
        for (f in fens) {
            assertFalse(isInsufficientMaterial(FenConverter.fenToGameState(f)), "Failed for $f")
        }
    }

    @Test
    fun `integration capture leaves K+B vs K produces DRAW`() {
        val vm = GameViewModel(FenConverter.fenToGameState("4k3/8/8/6n1/8/8/8/2B1K3 w - - 10 40"))
        val bishopIdx = vm.gameState.value.positionsWhite.indexOf(Pair(7, 2))
        vm.playerMove(bishopIdx, Pair(3, 6)) // Bc1xg5
        assertEquals(WinState.DRAW, vm.gameState.value.winState)
        assertEquals(1, vm.gameState.value.piecesBlack.size)
        assertEquals(0, vm.gameState.value.halfmoveClock)
    }

    @Test
    fun `threefold helper contract`() {
        val s = GameUiState()
        val key = FenConverter.positionKey(s)
        assertTrue(isThreefoldRepetition(s.copy(positionHistory = List(3) { key })))
        assertFalse(isThreefoldRepetition(s.copy(positionHistory = List(2) { key })))
    }
}
