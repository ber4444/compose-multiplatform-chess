package com.example.myapplication

import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.Test

class FenConverterTest {

    @Test
    fun `starting position produces correct FEN`() {
        val gameState = GameUiState()
        val fen = FenConverter.gameStateToFen(gameState)

        // Starting position: black on rows 0-1, white on rows 6-7
        // FEN piece placement should match standard starting position
        assertTrue(
            fen.startsWith("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR"),
            "FEN should start with piece placement"
        )
        assertTrue(fen.contains(" w "), "FEN should indicate white to move")
        assertTrue(fen.contains(" KQkq "), "FEN should indicate all castling rights available")
    }

    @Test
    fun `empty board with only kings produces correct FEN`() {
        val gameState = GameUiState(
            piecesWhite = listOf(King(Set.WHITE)),
            positionsWhite = listOf(Pair(7, 4)),  // e1
            piecesBlack = listOf(King(Set.BLACK)),
            positionsBlack = listOf(Pair(0, 4))   // e8
        )
        val fen = FenConverter.gameStateToFen(gameState)

        // Row 0: "4k3" (king at column 4)
        // Rows 1-6: "8" (empty)
        // Row 7: "4K3" (king at column 4)
        assertTrue(
            fen.startsWith("4k3/8/8/8/8/8/8/4K3"),
            "FEN should have black king on rank 8"
        )
    }

    @Test
    fun `pieceToFenChar returns correct characters`() {
        assertEquals('K', FenConverter.pieceToFenChar(King(Set.WHITE)))
        assertEquals('k', FenConverter.pieceToFenChar(King(Set.BLACK)))
        assertEquals('Q', FenConverter.pieceToFenChar(Queen(Set.WHITE)))
        assertEquals('q', FenConverter.pieceToFenChar(Queen(Set.BLACK)))
        assertEquals('R', FenConverter.pieceToFenChar(Rook(Set.WHITE)))
        assertEquals('r', FenConverter.pieceToFenChar(Rook(Set.BLACK)))
        assertEquals('B', FenConverter.pieceToFenChar(Bishop(Set.WHITE)))
        assertEquals('b', FenConverter.pieceToFenChar(Bishop(Set.BLACK)))
        assertEquals('N', FenConverter.pieceToFenChar(Knight(Set.WHITE)))
        assertEquals('n', FenConverter.pieceToFenChar(Knight(Set.BLACK)))
        assertEquals('P', FenConverter.pieceToFenChar(Pawn(Set.WHITE)))
        assertEquals('p', FenConverter.pieceToFenChar(Pawn(Set.BLACK)))
    }

    @Test
    fun `FEN includes correct turn indicator`() {
        val whiteToMove = GameUiState(turn = Set.WHITE)
        val blackToMove = GameUiState(turn = Set.BLACK)

        val fenWhite = FenConverter.gameStateToFen(whiteToMove)
        val fenBlack = FenConverter.gameStateToFen(blackToMove)

        assertTrue(fenWhite.contains(" w "), "White turn FEN should contain ' w '")
        assertTrue(fenBlack.contains(" b "), "Black turn FEN should contain ' b '")
    }

    @Test
    fun `FEN handles custom castling rights`() {
        val gameState = GameUiState(
            castlingRights = CastlingRights(
                whiteKingside = false,
                whiteQueenside = true,
                blackKingside = true,
                blackQueenside = false
            )
        )
        val fen = FenConverter.gameStateToFen(gameState)
        assertTrue(fen.contains(" Qk "), "FEN should serialize castling correctly")

        val deserialized = FenConverter.fenToGameState(fen)
        assertEquals(false, deserialized.castlingRights.whiteKingside)
        assertEquals(true, deserialized.castlingRights.whiteQueenside)
        assertEquals(true, deserialized.castlingRights.blackKingside)
        assertEquals(false, deserialized.castlingRights.blackQueenside)
    }

    @Test
    fun `FEN handles captured pieces correctly`() {
        // Simulate a game where most pieces have been captured
        val gameState = GameUiState(
            piecesWhite = listOf(King(Set.WHITE), Rook(Set.WHITE)),
            positionsWhite = listOf(Pair(7, 4), Pair(7, 0)),  // e1, a1
            piecesBlack = listOf(King(Set.BLACK), Queen(Set.BLACK)),
            positionsBlack = listOf(Pair(0, 4), Pair(3, 3))   // e8, d5
        )
        val fen = FenConverter.gameStateToFen(gameState)

        // Verify the FEN has the right structure (8 ranks separated by /)
        val ranks = fen.split(" ")[0].split("/")
        assertEquals(8, ranks.size, "FEN should have 8 ranks")

        // Row 0 (rank 8): "4k3"
        assertEquals("4k3", ranks[0])
        // Row 3 (rank 5): "3q4"
        assertEquals("3q4", ranks[3])
        // Row 7 (rank 1): "R3K3"
        assertEquals("R3K3", ranks[7])
    }

    @Test
    fun `starting position FEN constant is standard`() {
        assertEquals(
            "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1",
            FenConverter.STARTING_FEN
        )
    }

    @Test
    fun `clock round-trip`() {
        val state = FenConverter.fenToGameState("4k3/8/8/8/8/8/8/4K3 w - - 12 34")
        assertEquals(12, state.halfmoveClock)
        assertEquals(34, state.fullmoveNumber)
        assertEquals("4k3/8/8/8/8/8/8/4K3 w - - 12 34", FenConverter.gameStateToFen(state))
    }

    @Test
    fun `missing clock fields default`() {
        val state = FenConverter.fenToGameState("4k3/8/8/8/8/8/8/4K3 w - -")
        assertEquals(0, state.halfmoveClock)
        assertEquals(1, state.fullmoveNumber)
        val startState = FenConverter.fenToGameState(FenConverter.STARTING_FEN)
        assertEquals(FenConverter.STARTING_FEN, FenConverter.gameStateToFen(startState))
    }
}
