package com.example.myapplication

import kotlin.test.assertTrue
import kotlin.test.Test

class GameViewModelTest {
    private val viewModel = GameViewModel()

    @Test
    fun `test movePieceWhite within bounds and no overlap`() = kotlinx.coroutines.test.runTest {
        viewModel.moveCPU(Set.WHITE)  {
            enemyPositions: List<Pair<Int, Int>>,
            enemyPieces: List<Piece>,
            allyPositions: List<Pair<Int,Int>>,
            allyPieces: List<Piece> ->
            pickMoveCPU(enemyPositions, enemyPieces, allyPositions, allyPieces)
        }
        val positionWhite = viewModel.gameState.value.positionsWhite.first()
        val positionBlack = viewModel.gameState.value.positionsBlack.first()

        assertTrue(positionWhite.first in 0 until BOARD_SIZE && positionWhite.second in 0 until BOARD_SIZE, "White piece out of bounds")
        assertTrue(positionWhite != positionBlack, "Pieces overlap")
    }

    @Test
    fun `test movePieceBlack within bounds and no overlap`() = kotlinx.coroutines.test.runTest {
        viewModel.moveCPU(Set.BLACK) {
            enemyPositions: List<Pair<Int, Int>>,
            enemyPieces: List<Piece>,
            allyPositions: List<Pair<Int, Int>>,
            allyPieces: List<Piece> ->
            pickMoveCPU(enemyPositions, enemyPieces, allyPositions, allyPieces)
        }
        val positionBlack = viewModel.gameState.value.positionsBlack.first()
        val positionWhite = viewModel.gameState.value.positionsWhite.first()

        assertTrue(positionBlack.first in 0 until BOARD_SIZE && positionBlack.second in 0 until BOARD_SIZE, "Black piece out of bounds")
        assertTrue(positionBlack != positionWhite, "Pieces overlap")
    }

    @Test
    fun `play until game over and ensure no overlap`() = kotlinx.coroutines.test.runTest {
        while(viewModel.gameState.value.winState == WinState.NONE) {
            viewModel.moveCPU(Set.WHITE) {
                enemyPositions: List<Pair<Int, Int>>,
                enemyPieces: List<Piece>,
                allyPositions: List<Pair<Int, Int>>,
                allyPieces: List<Piece> ->
                pickMoveCPU(enemyPositions, enemyPieces, allyPositions, allyPieces)
            }
            viewModel.moveCPU(Set.BLACK) {
                enemyPositions: List<Pair<Int, Int>>,
                enemyPieces: List<Piece>,
                allyPositions: List<Pair<Int, Int>>,
                allyPieces: List<Piece> ->
                pickMoveCPU(enemyPositions, enemyPieces, allyPositions, allyPieces)
            }

            val positionWhite = viewModel.gameState.value.positionsWhite.firstOrNull()
            val positionBlack = viewModel.gameState.value.positionsBlack.firstOrNull()

            if (positionWhite != null) {
                assertTrue(positionWhite.first in 0 until BOARD_SIZE && positionWhite.second in 0 until BOARD_SIZE, "White piece out of bounds")
            }
            if (positionBlack != null) {
                assertTrue(positionBlack.first in 0 until BOARD_SIZE && positionBlack.second in 0 until BOARD_SIZE, "Black piece out of bounds")
            }
            if (positionWhite != null && positionBlack != null) {
                assertTrue(positionWhite != positionBlack, "Pieces overlap")
            }
        }
    }

    @Test
    fun `verify King is in a bad position`() {
        val kingPosition = Pair(3,3)
        val knightPositions = listOf(
            Pair(1,2),Pair(1,4),Pair(2,1),Pair(2,5),
            Pair(4,1),Pair(4,5),Pair(5,2),Pair(5,4)
        )
        val queenPositions = listOf(
            Pair(1,1),Pair(1,3),Pair(1,5),Pair(3,1),
            Pair(3,7),Pair(5,1),Pair(3,6),Pair(7,7)
        )

        val killingGameState = GameUiState(
            positionsBlack = knightPositions + queenPositions,
            positionsWhite = listOf(kingPosition),
            piecesBlack = listOf(
                Knight(Set.BLACK),Knight(Set.BLACK),Knight(Set.BLACK),Knight(Set.BLACK),
                Knight(Set.BLACK),Knight(Set.BLACK),Knight(Set.BLACK),Knight(Set.BLACK),
                Queen(Set.BLACK),Queen(Set.BLACK),Queen(Set.BLACK),Queen(Set.BLACK),
                Queen(Set.BLACK),Queen(Set.BLACK),Queen(Set.BLACK),Queen(Set.BLACK),
            ),
            piecesWhite = listOf(King(Set.WHITE))
        )

        assertTrue(
            checkCheck(
                kingPosition = Pair(3,3),
                enemyPositions = killingGameState.positionsBlack,
                enemyPieces = killingGameState.piecesBlack,
                allyPositions = killingGameState.positionsWhite
            )
        )
    }

    @Test
    fun `the King is not safe from Knights`() {
        val kingPosition = Pair(3,3)
        val knightPositions = listOf(
            Pair(1,2),Pair(1,4),Pair(2,1),Pair(2,5),
            Pair(4,1),Pair(4,5),Pair(5,2),Pair(5,4)
        )
        val whitePositions = listOf(
            Pair(1,3),Pair(2,2),Pair(2,3),Pair(2,4),
            Pair(3,1),Pair(3,2),Pair(3,4),Pair(3,5),
            Pair(4,2),Pair(4,3),Pair(4,4),Pair(5,3)
        )

        val killingGameState = GameUiState(
            positionsBlack = knightPositions,
            positionsWhite = whitePositions,
            piecesBlack = listOf(
                Knight(Set.BLACK),Knight(Set.BLACK),Knight(Set.BLACK),Knight(Set.BLACK),
                Knight(Set.BLACK),Knight(Set.BLACK),Knight(Set.BLACK),Knight(Set.BLACK)
            ),
            piecesWhite = listOf(King(Set.WHITE)) // we don't care about pieces, just locations
        )

        assertTrue(
            checkCheck(
                kingPosition = kingPosition,
                enemyPositions = killingGameState.positionsBlack,
                enemyPieces = killingGameState.piecesBlack,
                allyPositions = killingGameState.positionsWhite
            ),
            "Knights should be able to capture King even when allies are in between"
        )
    }

    @Test
    fun `the King is safe with allies blocking`() {
        val kingPairPosition = Pair(3,3)
        val whitePositions = listOf(
            Pair(2,2),Pair(2,3),Pair(2,4),
            Pair(3,2),Pair(3,4),
            Pair(4,2),Pair(4,3),Pair(4,4)
        )
        val queenPositions = listOf(
            Pair(1,1),Pair(1,3),Pair(1,5),Pair(3,1),
            Pair(3,7),Pair(5,1),Pair(3,6),Pair(7,7)
        )

        val killingGameState = GameUiState(
            positionsBlack = queenPositions,
            positionsWhite = whitePositions,
            piecesBlack = listOf(
                Queen(Set.BLACK),Queen(Set.BLACK),Queen(Set.BLACK),Queen(Set.BLACK),
                Queen(Set.BLACK),Queen(Set.BLACK),Queen(Set.BLACK),Queen(Set.BLACK),
            ),
            piecesWhite = listOf(King(Set.WHITE)) // we don't care about pieces, just locations
        )

        val kingIsDead = checkCheck(
            kingPosition = kingPairPosition,
            enemyPositions = killingGameState.positionsBlack,
            enemyPieces = killingGameState.piecesBlack,
            allyPositions = killingGameState.positionsWhite
        )

        assertTrue(
            !kingIsDead,
            "Queens should be blocked by King's allies"
        )
    }

    @Test
    fun `verify King is in check but not checkmate`() {
        val kingPosition = Pair(3,3)
        val rookPosition = Pair(3,0)
        val killingGameState = GameUiState(
            positionsBlack = listOf(rookPosition),
            positionsWhite = listOf(kingPosition),
            piecesBlack = listOf(Rook(Set.BLACK)),
            piecesWhite = listOf(King(Set.WHITE))
        )
        val kingInCheck = checkCheck(
            kingPosition = kingPosition,
            enemyPositions = killingGameState.positionsBlack,
            enemyPieces = killingGameState.piecesBlack,
            allyPositions = killingGameState.positionsWhite
        )

        val playerHasLegalMove = hasLegalMoves(
            enemyPositions = killingGameState.positionsBlack,
            enemyPieces = killingGameState.piecesBlack,
            allyPositions = killingGameState.positionsWhite,
            allyPieces = killingGameState.piecesWhite,
        )
        assertTrue(kingInCheck && playerHasLegalMove, "King should be in check and still have valid move")
    }

    @Test
    fun `verify King is in stalemate`() {
        val kingPosition = Pair(3,3)
        val rookPositions = listOf(
            Pair(0,2), Pair(2,0), Pair(4,5), Pair(5,4)
        )
        val killingGameState = GameUiState(
            positionsBlack = rookPositions,
            positionsWhite = listOf(kingPosition),
            piecesBlack = listOf(
                Rook(Set.BLACK), Rook(Set.BLACK), Rook(Set.BLACK), Rook(Set.BLACK)
            ),
            piecesWhite = listOf(King(Set.WHITE))
        )
        val kingInCheck = checkCheck(
            kingPosition = kingPosition,
            enemyPositions = killingGameState.positionsBlack,
            enemyPieces = killingGameState.piecesBlack,
            allyPositions = killingGameState.positionsWhite
        )

        val playerHasLegalMove = hasLegalMoves(
            enemyPositions = killingGameState.positionsBlack,
            enemyPieces = killingGameState.piecesBlack,
            allyPositions = killingGameState.positionsWhite,
            allyPieces = killingGameState.piecesWhite,
        )
        assertTrue(!kingInCheck && !playerHasLegalMove, "King should be not be in check, but no legal moves (stalemate)")
    }

    @Test
    fun `test white pieces do not turn black after first CPU move`() = kotlinx.coroutines.test.runTest {
        val viewModel = GameViewModel()

        // Execute the first automatic move for white
        viewModel.moveCPU(Set.WHITE) { enemyPositions, enemyPieces, allyPositions, allyPieces ->
            pickMoveCPU(enemyPositions, enemyPieces, allyPositions, allyPieces)
        }

        // At this point, the turn should switch to BLACK
        assertTrue(viewModel.gameState.value.turn == Set.BLACK, "Turn should be BLACK")

        // Check that white pieces are still correctly stored in white arrays
        val piecesWhite = viewModel.gameState.value.piecesWhite
        for (piece in piecesWhite) {
            assertTrue(piece.set == Set.WHITE, "A white piece turned to black: ${piece.name}")
        }

        // Execute the automatic move for black to ensure black pieces stay black
        viewModel.moveCPU(Set.BLACK) { enemyPositions, enemyPieces, allyPositions, allyPieces ->
            pickMoveCPU(enemyPositions, enemyPieces, allyPositions, allyPieces)
        }

        val piecesBlack = viewModel.gameState.value.piecesBlack
        for (piece in piecesBlack) {
            assertTrue(piece.set == Set.BLACK, "A black piece turned to white: ${piece.name}")
        }

        val piecesWhiteAfterBlackMove = viewModel.gameState.value.piecesWhite
        for (piece in piecesWhiteAfterBlackMove) {
            assertTrue(piece.set == Set.WHITE, "A white piece turned to black after black's move: ${piece.name}")
        }
    }

    @Test
    fun test3DToggleUpdatesViewStateCorrectly() {
        val viewModel = GameViewModel()
        
        // Initial state
        assertTrue(viewModel.viewState.value.show3D, "Should start with 3D on")
        assertTrue(!viewModel.viewState.value.board3DUnavailable, "Unavailable flag should start false")
        
        // Turn off 3D
        viewModel.setShow3D(false)
        assertTrue(!viewModel.viewState.value.show3D, "setShow3D(false) should set show3D to false")
        assertTrue(!viewModel.viewState.value.board3DUnavailable, "board3DUnavailable should be false")
        
        // Mark unavailable
        viewModel.markBoard3DUnavailable()
        assertTrue(!viewModel.viewState.value.show3D, "markBoard3DUnavailable should turn off show3D")
        assertTrue(viewModel.viewState.value.board3DUnavailable, "markBoard3DUnavailable should set flag to true")
        
        // Turn on 3D again
        viewModel.setShow3D(true)
        assertTrue(viewModel.viewState.value.show3D, "setShow3D(true) should set show3D to true")
        assertTrue(!viewModel.viewState.value.board3DUnavailable, "setShow3D(true) should clear unavailable flag")
    }

    @Test
    fun `testStockfishMoveClearsSelectedSquare`() = kotlinx.coroutines.test.runTest {
        val viewModel = GameViewModel()
        
        // Simulate White selecting a piece
        viewModel.updateSelected(Pair(6, 4))
        
        // Simulate Black (Stockfish) making a move
        viewModel.moveCPU(Set.BLACK) { _, _, _, _ ->
            SelectedMove(position = Pair(4, 4), pieceIndex = 0)
        }
        
        // Verify that selectedSquare was cleared
        kotlin.test.assertEquals(INVALID_POSITION, viewModel.gameState.value.selectedSquare)
    }
}