package com.example.myapplication

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * SAN generation tests. Each case drives a single move through `GameViewModel.moveCPU` (which
 * routes through `deriveNewGameState` and appends a [MoveRecord]) and asserts `moveHistory.last().san`.
 *
 * Cases covered: quiet pawn push, piece development, capture, pawn capture, knight capture,
 * castling kingside/queenside, disambiguation by file, disambiguation by rank, promotion,
 * capture-promotion, check (+), checkmate (#).
 */
class SanConverterTest {

    @Test
    fun `quiet pawn push produces 'e4'`() = runTest {
        val vm = GameViewModel()
        val e2 = vm.gameState.value.positionsWhite.indexOf(Pair(6, 4))
        vm.moveCPU(Set.WHITE) { _, _, _, _ -> SelectedMove(Pair(4, 4), e2) }
        assertEquals("e4", vm.gameState.value.moveHistory.single().san)
    }

    @Test
    fun `knight development produces 'Nf3'`() = runTest {
        val vm = GameViewModel()
        val g1 = vm.gameState.value.positionsWhite.indexOf(Pair(7, 6))
        vm.moveCPU(Set.WHITE) { _, _, _, _ -> SelectedMove(Pair(5, 5), g1) }
        assertEquals("Nf3", vm.gameState.value.moveHistory.single().san)
    }

    @Test
    fun `pawn capture produces 'exd5'`() = runTest {
        // After 1.e4 d5: White pawn on e4 can capture the d5 pawn.
        val vm = GameViewModel(FenConverter.fenToGameState("rnbqkbnr/ppp1pppp/8/3p4/4P3/8/PPPP1PPP/RNBQKBNR w KQkq d6 0 2"))
        val e4 = vm.gameState.value.positionsWhite.indexOf(Pair(4, 4))
        vm.moveCPU(Set.WHITE) { _, _, _, _ -> SelectedMove(Pair(3, 3), e4) }
        assertEquals("exd5", vm.gameState.value.moveHistory.single().san)
    }

    @Test
    fun `knight capture produces 'Nxe5'`() = runTest {
        val vm = GameViewModel(FenConverter.fenToGameState("r1bqkbnr/pppp1ppp/2n5/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R w KQkq - 0 3"))
        val f3 = vm.gameState.value.positionsWhite.indexOf(Pair(5, 5))
        vm.moveCPU(Set.WHITE) { _, _, _, _ -> SelectedMove(Pair(3, 4), f3) }
        assertEquals("Nxe5", vm.gameState.value.moveHistory.single().san)
    }

    @Test
    fun `kingside castling produces 'O-O'`() = runTest {
        val vm = GameViewModel(FenConverter.fenToGameState("r3k2r/8/8/8/8/8/8/R3K2R w KQkq - 0 1"))
        val kingIdx = vm.gameState.value.piecesWhite.indexOfFirst { it is King }
        vm.moveCPU(Set.WHITE) { _, _, _, _ -> SelectedMove(Pair(7, 6), kingIdx) }
        assertEquals("O-O", vm.gameState.value.moveHistory.single().san)
    }

    @Test
    fun `queenside castling produces 'O-O-O'`() = runTest {
        val vm = GameViewModel(FenConverter.fenToGameState("r3k2r/8/8/8/8/8/8/R3K2R w KQkq - 0 1"))
        val kingIdx = vm.gameState.value.piecesWhite.indexOfFirst { it is King }
        vm.moveCPU(Set.WHITE) { _, _, _, _ -> SelectedMove(Pair(7, 2), kingIdx) }
        assertEquals("O-O-O", vm.gameState.value.moveHistory.single().san)
    }

    @Test
    fun `disambiguation by file produces 'Nbd2'`() = runTest {
        // Knights on b1 and f1 both reach d2; b1 is the mover.
        val vm = GameViewModel(FenConverter.fenToGameState("4k3/8/8/8/8/8/8/1N3N1K w - - 0 1"))
        val b1 = vm.gameState.value.positionsWhite.indexOf(Pair(7, 1))
        vm.moveCPU(Set.WHITE) { _, _, _, _ -> SelectedMove(Pair(6, 3), b1) }
        assertEquals("Nbd2", vm.gameState.value.moveHistory.single().san)
    }

    @Test
    fun `disambiguation by rank produces 'R1a3'`() = runTest {
        // Rooks on a1 and a5 both reach a3; a1 is the mover. (Same file → rank disambiguates.)
        val vm = GameViewModel(FenConverter.fenToGameState("4k3/8/8/R7/8/8/8/R3K3 w - - 0 1"))
        val a1 = vm.gameState.value.positionsWhite.indexOf(Pair(7, 0))
        vm.moveCPU(Set.WHITE) { _, _, _, _ -> SelectedMove(Pair(5, 0), a1) }
        assertEquals("R1a3", vm.gameState.value.moveHistory.single().san)
    }

    @Test
    fun `straight promotion produces 'e8=Q'`() = runTest {
        // Black king on g7 so the promoting queen on e8 doesn't give a check (which would append `+`).
        val vm = GameViewModel(FenConverter.fenToGameState("8/4P1k1/8/8/8/8/8/4K3 w - - 0 1"))
        val e7 = vm.gameState.value.positionsWhite.indexOf(Pair(1, 4))
        vm.moveCPU(Set.WHITE) { _, _, _, _ -> SelectedMove(Pair(0, 4), e7, PromotionType.QUEEN) }
        assertEquals("e8=Q", vm.gameState.value.moveHistory.single().san)
    }

    @Test
    fun `capture promotion produces 'gxh8=N'`() = runTest {
        // White pawn on g7 captures the rook on h8, promoting to a knight.
        val vm = GameViewModel(FenConverter.fenToGameState("7r/6P1/5k2/8/8/8/8/4K3 w - - 0 1"))
        val g7 = vm.gameState.value.positionsWhite.indexOf(Pair(1, 6))
        vm.moveCPU(Set.WHITE) { _, _, _, _ -> SelectedMove(Pair(0, 7), g7, PromotionType.KNIGHT) }
        assertEquals("gxh8=N", vm.gameState.value.moveHistory.single().san)
    }

    @Test
    fun `check suffix plus on a queen check`() = runTest {
        // Qa1 -> a8 checks the black king along rank 8 (king has legal escapes).
        val vm = GameViewModel(FenConverter.fenToGameState("4k3/8/8/8/8/8/8/Q3K3 w - - 0 1"))
        val a1 = vm.gameState.value.positionsWhite.indexOf(Pair(7, 0))
        vm.moveCPU(Set.WHITE) { _, _, _, _ -> SelectedMove(Pair(0, 0), a1) }
        val san = vm.gameState.value.moveHistory.single().san
        assertTrue(san.endsWith("+"), "Expected check suffix on: $san")
        assertEquals("Qa8+", san)
    }

    @Test
    fun `mate suffix hashes on a back-rank checkmate`() = runTest {
        // Back-rank mate: Ra1 -> a8 mates the black king on g8 (pawns block rank 7).
        val vm = GameViewModel(FenConverter.fenToGameState("6k1/5ppp/8/8/8/8/8/R3K3 w - - 0 1"))
        val a1 = vm.gameState.value.positionsWhite.indexOf(Pair(7, 0))
        vm.moveCPU(Set.WHITE) { _, _, _, _ -> SelectedMove(Pair(0, 0), a1) }
        val state = vm.gameState.value
        val san = state.moveHistory.single().san
        assertEquals("Ra8#", san)
        assertEquals(WinState.WHITE, state.winState)
    }

    @Test
    fun `move record carries UCI and fenAfter`() = runTest {
        val vm = GameViewModel()
        val e2 = vm.gameState.value.positionsWhite.indexOf(Pair(6, 4))
        vm.moveCPU(Set.WHITE) { _, _, _, _ -> SelectedMove(Pair(4, 4), e2) }
        val record = vm.gameState.value.moveHistory.single()
        assertEquals("e2e4", record.uci)
        // fenAfter must round-trip to the same FEN the post-move state would emit.
        assertEquals(FenConverter.gameStateToFen(vm.gameState.value), record.fenAfter)
    }
}
