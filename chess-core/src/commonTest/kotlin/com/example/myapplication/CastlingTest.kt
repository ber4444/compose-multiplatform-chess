package com.example.myapplication

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CastlingTest {

    @Test
    fun `getCastlingMoves pure function tests`() {
        // "r3k2r/8/8/8/8/8/8/R3K2R w KQkq - 0 1"
        val state = FenConverter.fenToGameState("r3k2r/8/8/8/8/8/8/R3K2R w KQkq - 0 1")

        val moves = getCastlingMoves(
            castlingRights = state.castlingRights,
            enemyPositions = state.positionsBlack,
            enemyPieces = state.piecesBlack,
            allyPositions = state.positionsWhite,
            allyPieces = state.piecesWhite
        )
        // White can castle both sides
        assertEquals(2, moves.size)
        assertTrue(moves.any { it.first == Pair(7, 6) }) // Kingside
        assertTrue(moves.any { it.first == Pair(7, 2) }) // Queenside

        // Blocked by a piece between (knight on b1 kills queenside only)
        val blockedState = FenConverter.fenToGameState("r3k2r/8/8/8/8/8/8/RN2K2R w KQkq - 0 1")
        val blockedMoves = getCastlingMoves(
            castlingRights = blockedState.castlingRights,
            enemyPositions = blockedState.positionsBlack,
            enemyPieces = blockedState.piecesBlack,
            allyPositions = blockedState.positionsWhite,
            allyPieces = blockedState.piecesWhite
        )
        assertEquals(1, blockedMoves.size)
        assertEquals(Pair(7, 6), blockedMoves[0].first) // Only kingside

        // King in check (black rook on e-file)
        val checkState = FenConverter.fenToGameState("r3k2r/8/8/4r3/8/8/8/R3K2R w KQkq - 0 1")
        val checkMoves = getCastlingMoves(
            castlingRights = checkState.castlingRights,
            enemyPositions = checkState.positionsBlack,
            enemyPieces = checkState.piecesBlack,
            allyPositions = checkState.positionsWhite,
            allyPieces = checkState.piecesWhite
        )
        assertEquals(0, checkMoves.size)

        // King crossing attacked square (black rook on f-file)
        val crossAttackedState = FenConverter.fenToGameState("r3k2r/8/8/5r2/8/8/8/R3K2R w KQkq - 0 1")
        val crossAttackedMoves = getCastlingMoves(
            castlingRights = crossAttackedState.castlingRights,
            enemyPositions = crossAttackedState.positionsBlack,
            enemyPieces = crossAttackedState.piecesBlack,
            allyPositions = crossAttackedState.positionsWhite,
            allyPieces = crossAttackedState.piecesWhite
        )
        assertEquals(1, crossAttackedMoves.size)
        assertEquals(Pair(7, 2), crossAttackedMoves[0].first) // Only queenside allowed

        // b1 attacked but empty -> queenside still allowed
        val b1AttackedState = FenConverter.fenToGameState("r3k2r/8/8/1r6/8/8/8/R3K2R w KQkq - 0 1")
        val b1AttackedMoves = getCastlingMoves(
            castlingRights = b1AttackedState.castlingRights,
            enemyPositions = b1AttackedState.positionsBlack,
            enemyPieces = b1AttackedState.piecesBlack,
            allyPositions = b1AttackedState.positionsWhite,
            allyPieces = b1AttackedState.piecesWhite
        )
        assertEquals(2, b1AttackedMoves.size) // Both allowed

        // rights "w - -" -> none
        val noRightsState = FenConverter.fenToGameState("r3k2r/8/8/8/8/8/8/R3K2R w - - 0 1")
        val noRightsMoves = getCastlingMoves(
            castlingRights = noRightsState.castlingRights,
            enemyPositions = noRightsState.positionsBlack,
            enemyPieces = noRightsState.piecesBlack,
            allyPositions = noRightsState.positionsWhite,
            allyPieces = noRightsState.piecesWhite
        )
        assertEquals(0, noRightsMoves.size)
    }

    @Test
    fun `playerMove white kingside`() {
        val viewModel = GameViewModel(FenConverter.fenToGameState("r3k2r/8/8/8/8/8/8/R3K2R w KQkq - 0 1"))
        val whiteKingIndex = viewModel.gameState.value.piecesWhite.indexOfFirst { it is King }

        // Move king e1 to g1
        viewModel.playerMove(whiteKingIndex, Pair(7, 6))

        val state = viewModel.gameState.value
        assertEquals(Set.BLACK, state.turn)
        assertEquals(Pair(7, 6), state.positionsWhite[whiteKingIndex])
        
        // Find the rook that was on h1 (7,7)
        val rookIndex = state.piecesWhite.indexOfLast { it is Rook } // Two rooks, we'll just check all positions
        assertTrue(state.positionsWhite.contains(Pair(7, 5)), "Rook should be on f1 (7,5)")
        assertFalse(state.positionsWhite.contains(Pair(7, 7)), "Rook should not be on h1 (7,7)")

        // White rights cleared
        assertFalse(state.castlingRights.whiteKingside)
        assertFalse(state.castlingRights.whiteQueenside)
        // Black rights remain
        assertTrue(state.castlingRights.blackKingside)
        assertTrue(state.castlingRights.blackQueenside)

        // Anim state has secondary piece
        val animState = viewModel.animState.value
        assertTrue(animState.secondaryPiece is Rook)
        assertEquals(Pair(7, 7), animState.secondaryStart)
        assertEquals(Pair(7, 5), animState.secondaryEnd)
    }

    @Test
    fun `Black castling via moveCPU with injected pickMove`() = kotlinx.coroutines.test.runTest {
        val viewModel = GameViewModel(FenConverter.fenToGameState("r3k2r/8/8/8/8/8/8/R3K2R b KQkq - 0 1"))
        
        viewModel.moveCPU(turn = Set.BLACK) { _, _, allyPositions, allyPieces ->
            val blackKingIndex = allyPieces.indexOfFirst { it is King }
            SelectedMove(Pair(0, 6), blackKingIndex)
        }

        val state = viewModel.gameState.value
        assertTrue(state.positionsBlack.contains(Pair(0, 5)), "Black rook should be on f8 (0,5)")
        assertFalse(state.positionsBlack.contains(Pair(0, 7)), "Black rook should not be on h8 (0,7)")
    }

    @Test
    fun `Rights revocation on rook move and capture`() {
        var state = FenConverter.fenToGameState("r3k2r/8/8/8/8/8/8/R3K2R w KQkq - 0 1")
        val viewModel = GameViewModel(state)
        
        // White h1 rook captures Black's h8 rook
        val h1RookIndex = state.positionsWhite.indexOf(Pair(7, 7))
        // We force a player move to an enemy position. The GameViewModel allows this if we bypass validation, 
        // wait, playerMove validates against getAllLegalMoves. 
        // h1 to h8 is blocked by the black rook on h8. It IS a legal capture move for a rook! 
        // Oh wait, there are no pieces in between in the FEN "r3k2r/8/8/8/8/8/8/R3K2R".
        // Yes, the h-file is clear between h1 and h8.
        viewModel.playerMove(h1RookIndex, Pair(0, 7))

        state = viewModel.gameState.value
        // Moving from h1 clears White's kingside right
        assertFalse(state.castlingRights.whiteKingside)
        assertTrue(state.castlingRights.whiteQueenside)
        
        // Capturing the rook on h8 clears Black's kingside right
        assertFalse(state.castlingRights.blackKingside)
        assertTrue(state.castlingRights.blackQueenside)
    }

    @Test
    fun `Stockfish path castling`() = kotlinx.coroutines.test.runTest {
        val state = FenConverter.fenToGameState("r3k2r/8/8/8/8/8/8/R3K2R b KQkq - 0 1")
        val blackKingIndex = state.piecesBlack.indexOfFirst { it is King }
        
        val engine = object : ChessEngine { 
            override suspend fun getBestMove(fen: String) = "e8g8"
            override fun close() {} 
        }

        val move = pickMoveStockfish(
            engine = engine,
            gameState = state,
            enemyPositions = state.positionsWhite,
            enemyPieces = state.piecesWhite,
            allyPositions = state.positionsBlack,
            allyPieces = state.piecesBlack
        )

        assertEquals(Pair(0, 6), move.position)
        assertEquals(blackKingIndex, move.pieceIndex)
    }
}
