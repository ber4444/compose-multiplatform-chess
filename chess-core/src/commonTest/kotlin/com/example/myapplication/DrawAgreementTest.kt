package com.example.myapplication

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlin.test.assertNull

class DrawAgreementTest {

    private fun mockEngine(eval: Int?): ChessEngine {
        return object : ChessEngine {
            override suspend fun getBestMove(fen: String): String? = null
            override suspend fun evaluate(fen: String): Int? = eval
            override fun close() {}
        }
    }

    @Test
    fun testMaterialBalanceCp() {
        val startState = GameUiState()
        assertEquals(0, materialBalanceCp(startState))

        val whiteRookState = FenConverter.fenToGameState("4k3/8/8/8/8/8/8/R3K3 w - - 0 1")
        assertEquals(500, materialBalanceCp(whiteRookState))

        val blackQueenState = FenConverter.fenToGameState("3qk3/8/8/8/8/8/8/4K3 w - - 0 1")
        assertEquals(-900, materialBalanceCp(blackQueenState))
    }

    @Test
    fun testShouldBlackAcceptDraw() {
        assertFalse(shouldBlackAcceptDraw(0))
        assertFalse(shouldBlackAcceptDraw(60))
        assertTrue(shouldBlackAcceptDraw(100))
        assertTrue(shouldBlackAcceptDraw(99997))

        assertFalse(shouldBlackAcceptDraw(-100))
        assertFalse(shouldBlackAcceptDraw(-99997))
    }

    @Test
    fun testShouldBlackOfferDraw() {
        assertTrue(shouldBlackOfferDraw(0))
        assertTrue(shouldBlackOfferDraw(60))
        assertTrue(shouldBlackOfferDraw(-60))
        
        assertFalse(shouldBlackOfferDraw(61))
        assertFalse(shouldBlackOfferDraw(-61))
    }

    @Test
    fun testBlackDrawOfferPreconditions() {
        var state = GameUiState(
            winState = WinState.NONE,
            drawOffer = null,
            pendingPromotion = null,
            fullmoveNumber = 20,
            halfmoveClock = 8,
            lastDrawOfferFullmove = 0
        )
        assertTrue(blackDrawOfferPreconditions(state))

        assertFalse(blackDrawOfferPreconditions(state.copy(fullmoveNumber = 19)))
        assertFalse(blackDrawOfferPreconditions(state.copy(halfmoveClock = 7)))
        assertFalse(blackDrawOfferPreconditions(state.copy(drawOffer = Set.BLACK)))
        
        // Cooldown check
        state = state.copy(lastDrawOfferFullmove = 25, fullmoveNumber = 30)
        assertFalse(blackDrawOfferPreconditions(state))
        
        state = state.copy(lastDrawOfferFullmove = 20, fullmoveNumber = 30)
        assertTrue(blackDrawOfferPreconditions(state))
    }

    @Test
    fun testCanOfferDraw() {
        val state = GameUiState(
            turn = Set.WHITE,
            winState = WinState.NONE,
            pendingPromotion = null,
            drawOffer = null,
            fullmoveNumber = 5,
            lastDrawOfferFullmove = 0
        )
        assertTrue(canOfferDraw(state))

        assertFalse(canOfferDraw(state.copy(turn = Set.BLACK)))
        assertFalse(canOfferDraw(state.copy(winState = WinState.BLACK)))
        assertFalse(canOfferDraw(state.copy(pendingPromotion = PendingPromotion(0, Pair(0,0), Pair(0,0)))))
        assertFalse(canOfferDraw(state.copy(drawOffer = Set.WHITE)))
        assertFalse(canOfferDraw(state.copy(lastDrawOfferFullmove = 5))) // Same fullmove
    }

    @Test
    fun testWhiteOffers_mockEval0_EarlyPositionDeclined() = kotlinx.coroutines.test.runTest {
        val viewModel = GameViewModel()
        viewModel.attachEngine(mockEngine(0))
        
        viewModel.offerDraw()
        
        assertEquals(WinState.NONE, viewModel.gameState.value.winState)
        assertNull(viewModel.gameState.value.drawOffer)
        assertEquals(Set.BLACK, viewModel.gameState.value.drawOfferDeclinedBy)
    }

    @Test
    fun testWhiteOffers_mockEval0_LateQuietPositionAccepted() = kotlinx.coroutines.test.runTest {
        val state = GameUiState(fullmoveNumber = 30, halfmoveClock = 12)
        val viewModel = GameViewModel(state)
        viewModel.attachEngine(mockEngine(0))

        viewModel.offerDraw()

        assertEquals(WinState.DRAW, viewModel.gameState.value.winState)
        assertNull(viewModel.gameState.value.drawOffer)
        assertNull(viewModel.gameState.value.drawOfferDeclinedBy)
    }

    @Test
    fun testWhiteOffers_mockEvalMinus400_Declined() = kotlinx.coroutines.test.runTest {
        val viewModel = GameViewModel()
        viewModel.attachEngine(mockEngine(-400))
        
        viewModel.offerDraw()
        
        assertEquals(WinState.NONE, viewModel.gameState.value.winState)
        assertEquals(Set.BLACK, viewModel.gameState.value.drawOfferDeclinedBy)
        assertFalse(canOfferDraw(viewModel.gameState.value)) // cooldown active
    }

    @Test
    fun testWhiteOffers_NoEngine_EqualMaterial_EarlyPositionDeclined() = kotlinx.coroutines.test.runTest {
        val viewModel = GameViewModel(GameUiState()) // start pos material is 0
        
        viewModel.offerDraw()
        
        assertEquals(WinState.NONE, viewModel.gameState.value.winState)
        assertEquals(Set.BLACK, viewModel.gameState.value.drawOfferDeclinedBy)
    }

    @Test
    fun testWhiteOffers_NoEngine_WhiteAhead_Accepted() = kotlinx.coroutines.test.runTest {
        val state = FenConverter.fenToGameState("4k3/8/8/8/8/8/8/R3K3 w - - 0 1") // White rook
        val viewModel = GameViewModel(state)

        viewModel.offerDraw()

        assertEquals(WinState.DRAW, viewModel.gameState.value.winState)
        assertNull(viewModel.gameState.value.drawOffer)
    }

    @Test
    fun testWhiteOffers_NoEngine_BlackAhead_Declined() = kotlinx.coroutines.test.runTest {
        val state = FenConverter.fenToGameState("3qk3/8/8/8/8/8/8/4K3 w - - 0 1") // Black queen
        val viewModel = GameViewModel(state)
        
        viewModel.offerDraw()
        
        assertEquals(WinState.NONE, viewModel.gameState.value.winState)
        assertEquals(Set.BLACK, viewModel.gameState.value.drawOfferDeclinedBy)
    }

    @Test
    fun testWhiteOffers_mockEvalNull_FallsBackToMaterial() = kotlinx.coroutines.test.runTest {
        val state = FenConverter.fenToGameState("3qk3/8/8/8/8/8/8/4K3 w - - 0 1")
        val viewModel = GameViewModel(state)
        viewModel.attachEngine(mockEngine(null))
        
        viewModel.offerDraw()
        
        assertEquals(WinState.NONE, viewModel.gameState.value.winState)
        assertEquals(Set.BLACK, viewModel.gameState.value.drawOfferDeclinedBy)
    }

    @Test
    fun testCooldownResetsAfterMove() = kotlinx.coroutines.test.runTest {
        val viewModel = GameViewModel()
        viewModel.attachEngine(mockEngine(-400))
        
        viewModel.offerDraw()
        assertEquals(Set.BLACK, viewModel.gameState.value.drawOfferDeclinedBy)
        
        val e2PawnIdx = viewModel.gameState.value.positionsWhite.indexOf(Pair(6, 4))
        viewModel.playerMove(e2PawnIdx, Pair(4, 4))
        
        assertNull(viewModel.gameState.value.drawOfferDeclinedBy)
    }

    @Test
    fun testOfferDrawMidPromotion_NoOps() = kotlinx.coroutines.test.runTest {
        val state = FenConverter.fenToGameState("4k3/P7/8/8/8/8/8/4K3 w - - 0 1")
        val viewModel = GameViewModel(state)
        
        val a7PawnIdx = viewModel.gameState.value.positionsWhite.indexOf(Pair(1, 0))
        viewModel.playerMove(a7PawnIdx, Pair(0, 0))
        
        // pendingPromotion is set
        viewModel.offerDraw()
        
        assertNull(viewModel.gameState.value.drawOffer)
    }

    @Test
    fun testBlackProactivelyOffers() = kotlinx.coroutines.test.runTest {
        // FEN with R+P each, so it doesn't trigger insufficient material
        val state = FenConverter.fenToGameState("r3k3/p7/8/8/8/8/P7/R3K3 w - - 10 30")
        val viewModel = GameViewModel(state)
        
        val a1RookIdx = viewModel.gameState.value.positionsWhite.indexOf(Pair(7, 0))
        viewModel.playerMove(a1RookIdx, Pair(7, 3)) // rook move
        
        // Trigger animation end
        viewModel.animationEnd()
        viewModel.awaitState { it.drawOffer == Set.BLACK }
        
        assertEquals(WinState.NONE, viewModel.gameState.value.winState)
    }

    @Test
    fun testBlackDrawOfferCooldown() = kotlinx.coroutines.test.runTest {
        val state = FenConverter.fenToGameState("r3k3/p7/8/8/8/8/P7/R3K3 w - - 10 30")
        val viewModel = GameViewModel(state.copy(lastDrawOfferFullmove = 29))
        
        val a1RookIdx = viewModel.gameState.value.positionsWhite.indexOf(Pair(7, 0))
        viewModel.playerMove(a1RookIdx, Pair(7, 3))
        
        viewModel.animationEnd()
        viewModel.awaitState { it.turn == Set.WHITE }
        
        assertNull(viewModel.gameState.value.drawOffer)
    }

    @Test
    fun testAcceptDrawOffer() {
        val state = GameUiState(turn = Set.BLACK, drawOffer = Set.BLACK)
        val viewModel = GameViewModel(state)
        
        viewModel.acceptDrawOffer()
        
        assertEquals(WinState.DRAW, viewModel.gameState.value.winState)
        assertNull(viewModel.gameState.value.drawOffer)
    }

    @Test
    fun testDeclineDrawOffer() {
        val state = GameUiState(turn = Set.BLACK, drawOffer = Set.BLACK)
        val viewModel = GameViewModel(state)
        
        viewModel.declineDrawOffer()
        
        assertNull(viewModel.gameState.value.drawOffer)
        assertEquals(WinState.NONE, viewModel.gameState.value.winState)
    }

    @Test
    fun testPlayerMoveIgnoredWhileDrawOfferPending() {
        val state = GameUiState(turn = Set.WHITE, drawOffer = Set.BLACK)
        val viewModel = GameViewModel(state)
        
        val e2PawnIdx = viewModel.gameState.value.positionsWhite.indexOf(Pair(6, 4))
        viewModel.playerMove(e2PawnIdx, Pair(4, 4))
        
        assertEquals(Pair(6, 4), viewModel.gameState.value.positionsWhite[e2PawnIdx])
    }

    @Test
    fun testResetGameClearsFields() {
        val state = GameUiState(drawOffer = Set.BLACK, drawOfferDeclinedBy = Set.BLACK, lastDrawOfferFullmove = 5)
        val viewModel = GameViewModel(state)
        
        viewModel.resetGame()
        
        assertNull(viewModel.gameState.value.drawOffer)
        assertNull(viewModel.gameState.value.drawOfferDeclinedBy)
        assertEquals(0, viewModel.gameState.value.lastDrawOfferFullmove)
    }
}
