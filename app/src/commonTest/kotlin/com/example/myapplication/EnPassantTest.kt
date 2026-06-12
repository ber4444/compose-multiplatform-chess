package com.example.myapplication

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EnPassantTest {

    @Test
    fun `Generation and getAllLegalMoves`() {
        // FEN with black playing d7-d5, white pawn on e5. En passant target is d6.
        val state = FenConverter.fenToGameState("rnbqkbnr/ppp1pppp/8/3pP3/8/8/PPPP1PPP/RNBQKBNR w KQkq d6 0 3")
        assertEquals(Pair(2, 3), state.enPassantTarget)

        val e5PawnIndex = state.positionsWhite.indexOf(Pair(3, 4))
        assertTrue(e5PawnIndex != -1)

        val movesWithTarget = getAllLegalMoves(
            enemyPositions = state.positionsBlack,
            enemyPieces = state.piecesBlack,
            allyPositions = state.positionsWhite,
            allyPieces = state.piecesWhite,
            enPassantTarget = state.enPassantTarget
        )
        assertTrue(movesWithTarget.contains(Pair(Pair(2, 3), e5PawnIndex)))

        val movesWithoutTarget = getAllLegalMoves(
            enemyPositions = state.positionsBlack,
            enemyPieces = state.piecesBlack,
            allyPositions = state.positionsWhite,
            allyPieces = state.piecesWhite,
            enPassantTarget = null
        )
        assertFalse(movesWithoutTarget.contains(Pair(Pair(2, 3), e5PawnIndex)))
    }

    @Test
    fun `Execution via playerMove`() {
        val state = FenConverter.fenToGameState("rnbqkbnr/ppp1pppp/8/3pP3/8/8/PPPP1PPP/RNBQKBNR w KQkq d6 0 3")
        val viewModel = GameViewModel(state)
        val e5PawnIndex = state.positionsWhite.indexOf(Pair(3, 4))

        val initialBlackPawnCount = state.piecesBlack.count { it is Pawn }

        viewModel.playerMove(e5PawnIndex, Pair(2, 3))

        val newState = viewModel.gameState.value
        assertEquals(Set.BLACK, newState.turn)
        assertNull(newState.enPassantTarget)
        
        // Assert victim removed
        assertEquals(initialBlackPawnCount - 1, newState.piecesBlack.count { it is Pawn })
        assertFalse(newState.positionsBlack.contains(Pair(3, 3)))
        
        // Assert capturer moved
        assertEquals(Pair(2, 3), newState.positionsWhite[e5PawnIndex])
    }

    @Test
    fun `Target set on double push and expiry`() = kotlinx.coroutines.test.runTest {
        val viewModel = GameViewModel(GameUiState())
        val e2PawnIndex = viewModel.gameState.value.positionsWhite.indexOf(Pair(6, 4))

        // Play e2-e4
        viewModel.playerMove(e2PawnIndex, Pair(4, 4))
        var state = viewModel.gameState.value
        
        assertEquals(Pair(5, 4), state.enPassantTarget)
        assertTrue(FenConverter.gameStateToFen(state).contains(" e3 "))

        // Play Black move (e.g. a7-a6, index 0 is a7 at (1, 0))
        val a7PawnIndex = state.positionsBlack.indexOf(Pair(1, 0))
        // Force CPU move by injecting it
        viewModel.moveCPU(Set.BLACK) { _, _, _, _ ->
            SelectedMove(Pair(2, 0), a7PawnIndex)
        }

        state = viewModel.gameState.value
        assertNull(state.enPassantTarget)
        assertTrue(FenConverter.gameStateToFen(state).contains(" - "))
    }

    @Test
    fun `Pinned en passant is illegal`() {
        val state = FenConverter.fenToGameState("4k3/8/8/r2pP2K/8/8/8/8 w - d6 0 1")
        val e5PawnIndex = state.positionsWhite.indexOf(Pair(3, 4))
        
        val moves = getAllLegalMoves(
            enemyPositions = state.positionsBlack,
            enemyPieces = state.piecesBlack,
            allyPositions = state.positionsWhite,
            allyPieces = state.piecesWhite,
            enPassantTarget = state.enPassantTarget
        )
        assertFalse(moves.contains(Pair(Pair(2, 3), e5PawnIndex)))
    }

    @Test
    fun `FEN round-trip`() {
        val fen = "rnbqkbnr/ppp1pppp/8/3pP3/8/8/PPPP1PPP/RNBQKBNR w KQkq d6 0 1"
        val state = FenConverter.fenToGameState(fen)
        assertEquals(Pair(2, 3), state.enPassantTarget)
        val generatedFen = FenConverter.gameStateToFen(state)
        assertTrue(generatedFen.contains(" d6 "))
    }

    @Test
    fun `Black side via moveCPU`() = kotlinx.coroutines.test.runTest {
        val state = FenConverter.fenToGameState("rnbqkbnr/pppp1ppp/8/8/3Pp3/8/PPP1PPPP/RNBQKBNR b KQkq d3 0 3")
        val viewModel = GameViewModel(state)
        
        val e4PawnIndex = state.positionsBlack.indexOf(Pair(4, 4))
        
        val initialWhitePawnCount = state.piecesWhite.count { it is Pawn }

        viewModel.moveCPU(Set.BLACK) { _, _, _, _ ->
            SelectedMove(Pair(5, 3), e4PawnIndex)
        }
        
        val newState = viewModel.gameState.value
        assertEquals(Set.WHITE, newState.turn)
        assertNull(newState.enPassantTarget)
        
        // Assert victim removed
        assertEquals(initialWhitePawnCount - 1, newState.piecesWhite.count { it is Pawn })
        assertFalse(newState.positionsWhite.contains(Pair(4, 3)))
    }
}
