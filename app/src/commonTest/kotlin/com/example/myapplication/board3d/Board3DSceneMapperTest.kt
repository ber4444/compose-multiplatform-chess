package com.example.myapplication.board3d

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class Board3DSceneMapperTest {

    @Test
    fun testStartingPosition() {
        val fen = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
        val scene = Board3DSceneMapper.fromFen(fen)
        
        assertEquals(PieceColor.WHITE, scene.sideToMove)
        assertEquals(32, scene.pieces.size)
        
        // Check white rook on a1
        val whiteRookA1 = scene.pieces.find { it.square == BoardSquare(7, 0) }
        assertTrue(whiteRookA1 != null)
        assertEquals(PieceKind.ROOK, whiteRookA1.kind)
        assertEquals(PieceColor.WHITE, whiteRookA1.color)
        assertEquals(0f, whiteRookA1.rotationYDegrees)
        assertEquals(Vec3(-3.5f, 0f, 3.5f), whiteRookA1.position)
        
        // Check black king on e8
        val blackKingE8 = scene.pieces.find { it.square == BoardSquare(0, 4) }
        assertTrue(blackKingE8 != null)
        assertEquals(PieceKind.KING, blackKingE8.kind)
        assertEquals(PieceColor.BLACK, blackKingE8.color)
        assertEquals(180f, blackKingE8.rotationYDegrees)
        assertEquals(Vec3(0.5f, 0f, -3.5f), blackKingE8.position)
    }

    @Test
    fun testEmptyBoard() {
        val fen = "8/8/8/8/8/8/8/8 b - - 0 1"
        val scene = Board3DSceneMapper.fromFen(fen)
        assertEquals(PieceColor.BLACK, scene.sideToMove)
        assertEquals(0, scene.pieces.size)
    }

    @Test
    fun testInvalidFen() {
        assertFailsWith<IllegalArgumentException> {
            Board3DSceneMapper.fromFen("rnbqkbnr/pppppppp") // Missing rows
        }
        assertFailsWith<IllegalArgumentException> {
            Board3DSceneMapper.fromFen("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNw w") // Invalid char
        }
        assertFailsWith<IllegalArgumentException> {
            Board3DSceneMapper.fromFen("8/8/8/8/8/8/8/9 w") // Too many columns
        }
    }
}
