package com.example.myapplication

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

class PromotionTest {
    private val WHITE_PROMO = "4k3/P7/8/8/8/8/8/4K3 w - - 0 1"
    private val BLACK_PROMO = "4k3/8/8/8/8/8/p3K3/8 b - - 0 1"
    private val CAPTURE_PROMO = "1r2k3/P7/8/8/8/8/8/4K3 w - - 0 1"
    private val MATE_PROMO = "k7/2P5/1K6/8/8/8/8/8 w - - 0 1"

    @Test
    fun testWhitePromotionFlow() {
        val viewModel = GameViewModel(FenConverter.fenToGameState(WHITE_PROMO))
        val state = viewModel.gameState.value
        val pawnIdx = state.piecesWhite.indexOfFirst { it is Pawn }
        
        // 1. playerMove to row 0 sets pendingPromotion
        viewModel.playerMove(pawnIdx, Pair(0, 0))
        var newState = viewModel.gameState.value
        assertTrue(newState.piecesWhite[pawnIdx] is Pawn)
        assertEquals(Set.WHITE, newState.turn)
        assertNull(viewModel.animState.value.pieceToAnimate)
        assertNotNull(newState.pendingPromotion)

        // 6. playerMove is ignored while pending
        viewModel.playerMove(newState.piecesWhite.indexOfFirst { it is King }, Pair(6, 4))
        assertEquals(newState, viewModel.gameState.value) // unchanged

        // 5. cancelPromotion
        viewModel.cancelPromotion()
        newState = viewModel.gameState.value
        assertNull(newState.pendingPromotion)
        assertTrue(newState.piecesWhite[pawnIdx] is Pawn)
        assertEquals(Set.WHITE, newState.turn)

        // Do move again
        viewModel.playerMove(pawnIdx, Pair(0, 0))
        
        // 2. promotePawn(KNIGHT)
        viewModel.promotePawn(PromotionType.KNIGHT)
        newState = viewModel.gameState.value
        assertTrue(newState.piecesWhite[pawnIdx] is Knight)
        assertEquals(Set.BLACK, newState.turn)
        assertNull(newState.pendingPromotion)
        assertEquals(Pair(1, 0), viewModel.animState.value.animatePositionStart)
        assertEquals(Pair(0, 0), viewModel.animState.value.animatePositionEnd)
        assertFalse(newState.inCheckBlack) // 4. KNIGHT gives no check
        
        viewModel.close()
    }

    @Test
    fun testWhitePromotionQueenCheck() {
        val viewModel = GameViewModel(FenConverter.fenToGameState(WHITE_PROMO))
        val state = viewModel.gameState.value
        val pawnIdx = state.piecesWhite.indexOfFirst { it is Pawn }
        
        viewModel.playerMove(pawnIdx, Pair(0, 0))
        viewModel.promotePawn(PromotionType.QUEEN)
        val newState = viewModel.gameState.value
        assertTrue(newState.inCheckBlack)
        
        val fen = FenConverter.gameStateToFen(newState)
        assertTrue(fen.startsWith("Q3k3")) // 3. Promoted piece in FEN
        
        viewModel.close()
    }

    @Test
    fun testCPUPromotion() = kotlinx.coroutines.test.runTest {
        val viewModel = GameViewModel(FenConverter.fenToGameState(BLACK_PROMO))
        val state = viewModel.gameState.value
        val blackPawnIdx = state.piecesBlack.indexOfFirst { it is Pawn }
        
        // 8. CPU black promotes to Queen by default
        viewModel.moveCPU(Set.BLACK) { _, _, _, _ ->
            SelectedMove(Pair(7, 0), blackPawnIdx)
        }
        var newState = viewModel.gameState.value
        assertTrue(newState.piecesBlack[blackPawnIdx] is Queen)
        
        viewModel.close()
        
        // 9. CPU honors underpromotion
        val viewModel2 = GameViewModel(FenConverter.fenToGameState(BLACK_PROMO))
        viewModel2.moveCPU(Set.BLACK) { _, _, _, _ ->
            SelectedMove(Pair(7, 0), blackPawnIdx, PromotionType.ROOK)
        }
        assertTrue(viewModel2.gameState.value.piecesBlack[blackPawnIdx] is Rook)
        viewModel2.close()
    }

    @Test
    fun testCapturePromotion() {
        val viewModel = GameViewModel(FenConverter.fenToGameState(CAPTURE_PROMO))
        val state = viewModel.gameState.value
        val pawnIdx = state.piecesWhite.indexOfFirst { it is Pawn }
        
        viewModel.playerMove(pawnIdx, Pair(0, 1)) // capture on b8
        viewModel.promotePawn(PromotionType.QUEEN)
        
        val newState = viewModel.gameState.value
        assertEquals(1, newState.piecesBlack.size) // Only king left
        assertTrue(newState.piecesWhite[pawnIdx] is Queen)
        assertEquals(Pair(0, 1), newState.positionsWhite[pawnIdx])
        assertTrue(newState.inCheckBlack)
        
        viewModel.close()
    }

    @Test
    fun testMatePromotion() = kotlinx.coroutines.test.runTest {
        val viewModel = GameViewModel(FenConverter.fenToGameState(MATE_PROMO))
        val state = viewModel.gameState.value
        val pawnIdx = state.piecesWhite.indexOfFirst { it is Pawn }
        
        viewModel.playerMove(pawnIdx, Pair(0, 2)) // c8
        viewModel.promotePawn(PromotionType.QUEEN)
        
        viewModel.animationEnd() // runs moveCPU(BLACK) asynchronously
        
        viewModel.awaitState { it.winState == WinState.WHITE }
        assertEquals(WinState.WHITE, viewModel.gameState.value.winState)
        
        viewModel.close()
    }
}
