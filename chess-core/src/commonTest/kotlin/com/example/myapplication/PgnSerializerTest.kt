package com.example.myapplication

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PgnSerializerTest {

    @Test
    fun `resultToken maps each WinState for White-to-move player`() {
        assertEquals("1-0", PgnSerializer.resultToken(WinState.WHITE))
        assertEquals("0-1", PgnSerializer.resultToken(WinState.BLACK))
        assertEquals("1/2-1/2", PgnSerializer.resultToken(WinState.DRAW))
        assertEquals("1/2-1/2", PgnSerializer.resultToken(WinState.STALEMATE))
        assertEquals("*", PgnSerializer.resultToken(WinState.NONE))
    }

    @Test
    fun `resultToken flips for the Black-perspective player`() {
        assertEquals("0-1", PgnSerializer.resultToken(WinState.WHITE, playerIsWhite = false))
        assertEquals("1-0", PgnSerializer.resultToken(WinState.BLACK, playerIsWhite = false))
    }

    @Test
    fun `toPgn emits Seven Tag Roster and trailing result for an empty game`() {
        val tags = PgnTags(
            date = "2026.06.21",
            white = "Player",
            black = "Stockfish",
            result = "*",
        )
        val pgn = PgnSerializer.toPgn(tags, moves = emptyList())
        val lines = pgn.lines()
        assertTrue("[Event \"Casual Game\"]" in lines, "Missing Event tag: $lines")
        assertTrue("[Site \"Compose Multiplatform Chess\"]" in lines)
        assertTrue("[Date \"2026.06.21\"]" in lines)
        assertTrue("[White \"Player\"]" in lines)
        assertTrue("[Black \"Stockfish\"]" in lines)
        assertTrue("[Result \"*\"]" in lines)
        // Empty game still ends with the result token.
        assertTrue(pgn.endsWith("*"))
    }

    @Test
    fun `toPgn numbers movetext starting from 1 for a standard start`() = runTest {
        // 1.e4 e5 2.Nf3 — three plies, all numbered from move 1.
        val vm = GameViewModel()
        val e2 = vm.gameState.value.positionsWhite.indexOf(Pair(6, 4))
        vm.moveCPU(Set.WHITE) { _, _, _, _ -> SelectedMove(Pair(4, 4), e2) }
        val e7 = vm.gameState.value.positionsBlack.indexOf(Pair(1, 4))
        vm.moveCPU(Set.BLACK) { _, _, _, _ -> SelectedMove(Pair(3, 4), e7) }
        val g1 = vm.gameState.value.positionsWhite.indexOf(Pair(7, 6))
        vm.moveCPU(Set.WHITE) { _, _, _, _ -> SelectedMove(Pair(5, 5), g1) }

        val tags = PgnTags(date = "2026.06.21", result = "*")
        val pgn = PgnSerializer.toPgn(tags, vm.gameState.value.moveHistory)

        assertTrue(pgn.contains("1. e4 e5 2. Nf3 "), "Expected numbered movetext in: $pgn")
        assertTrue(pgn.endsWith("*"))
    }

    @Test
    fun `toPgn emits SetUp and FEN tags and numbers from the FEN's move number when starting mid-game`() = runTest {
        // Start from a FEN on move 5 (Black to move): play one Black ply, expect "5. ... <san>".
        val startFen = "rnbqkbnr/pppp1ppp/8/4p3/4P3/8/PPPP1PPP/RNBQKBNR b KQkq e3 0 3"
        val vm = GameViewModel(FenConverter.fenToGameState(startFen))
        val g8 = vm.gameState.value.positionsBlack.indexOf(Pair(0, 6))
        vm.moveCPU(Set.BLACK) { _, _, _, _ -> SelectedMove(Pair(2, 5), g8) }

        val tags = PgnTags(
            date = "2026.06.21",
            result = "*",
            setUpFen = startFen,
        )
        val pgn = PgnSerializer.toPgn(tags, vm.gameState.value.moveHistory)

        assertTrue("[SetUp \"1\"]" in pgn, "Missing SetUp tag: $pgn")
        assertTrue("[FEN \"$startFen\"]" in pgn)
        // FEN's fullmove number is 3; the lone Black ply should be prefixed "3. ... <san>".
        val san = vm.gameState.value.moveHistory.single().san
        assertTrue("3. ... $san" in pgn, "Expected '3. ... $san' in: $pgn")
    }
}
