package com.example.myapplication

import kotlin.test.Test
import kotlin.test.assertEquals

class MoveTest {

    @Test
    fun `the CPU fallback always takes a capture when one is on offer`() {
        // White Kh1 + Ra1 against Black Kh8 + Na8: a dozen quiet moves and exactly one capture.
        val white = listOf(King(Set.WHITE), Rook(Set.WHITE))
        val whiteSquares = listOf(7 to 7, 7 to 0)
        val black = listOf(King(Set.BLACK), Knight(Set.BLACK))
        val blackSquares = listOf(0 to 7, 0 to 0)

        // The choice is random, so a single draw proves little; every draw must be Rxa8.
        repeat(25) {
            assertEquals(SelectedMove(0 to 0, 1), pickMoveCPU(blackSquares, black, whiteSquares, white))
        }
    }

    @Test
    fun `the CPU fallback reports no move for a stalemated side`() {
        val state = FenConverter.fenToGameState("7k/5Q2/6K1/8/8/8/8/8 b - - 0 1")

        val move = pickMoveCPU(state.positionsWhite, state.piecesWhite, state.positionsBlack, state.piecesBlack)

        assertEquals(INVALID_POSITION, move.position)
        assertEquals(-1, move.pieceIndex)
    }

    @Test
    fun `pickMoveStockfish parses promotion from engine`() = kotlinx.coroutines.test.runTest {
        val whitePawns = listOf(Pawn(Set.WHITE), King(Set.WHITE))
        val whitePositions = listOf(Pair(1, 0), Pair(7, 4)) // a7, e1
        val blackKingOnly = listOf<Piece>(King(Set.BLACK))
        val blackPositions = listOf(Pair(0, 4)) // e8

        val engine = object : ChessEngine {
            override suspend fun getBestMove(fen: String, thinkTimeMs: Long?) = BestMoveResult("a7a8n", null)
            override fun close() {}
        }

        val move = pickMoveStockfish(
            engine = engine,
            gameState = GameUiState(),
            enemyPositions = blackPositions, enemyPieces = blackKingOnly,
            allyPositions = whitePositions, allyPieces = whitePawns,
        )

        assertEquals(Pair(0, 0), move.position)
        assertEquals(0, move.pieceIndex)
        assertEquals(PromotionType.KNIGHT, move.promotion)
    }
}
