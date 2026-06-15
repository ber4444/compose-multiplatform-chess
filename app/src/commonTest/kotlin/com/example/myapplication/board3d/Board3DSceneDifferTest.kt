package com.example.myapplication.board3d

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class Board3DSceneDifferTest {

    @Test
    fun testSimpleMove() {
        val prev = Board3DScene(
            pieces = listOf(
                Piece3DInstance(PieceKind.PAWN, PieceColor.WHITE, BoardSquare(6, 4), Vec3(0f, 0f, 0f), 0f),
                Piece3DInstance(PieceKind.KING, PieceColor.BLACK, BoardSquare(0, 4), Vec3(0f, 0f, 0f), 0f)
            ),
            sideToMove = PieceColor.WHITE
        )

        val next = Board3DScene(
            pieces = listOf(
                Piece3DInstance(PieceKind.PAWN, PieceColor.WHITE, BoardSquare(4, 4), Vec3(0f, 0f, 0f), 0f),
                Piece3DInstance(PieceKind.KING, PieceColor.BLACK, BoardSquare(0, 4), Vec3(0f, 0f, 0f), 0f)
            ),
            sideToMove = PieceColor.BLACK
        )

        val diff = Board3DSceneDiffer.diff(prev, next)
        assertTrue(diff is Board3DTransition.Move)
        assertEquals(BoardSquare(6, 4), diff.from)
        assertEquals(BoardSquare(4, 4), diff.to)
        assertEquals(PieceKind.PAWN, diff.kind)
        assertEquals(PieceColor.WHITE, diff.color)
    }

    @Test
    fun testCapture() {
        val prev = Board3DScene(
            pieces = listOf(
                Piece3DInstance(PieceKind.PAWN, PieceColor.WHITE, BoardSquare(4, 4), Vec3(0f, 0f, 0f), 0f),
                Piece3DInstance(PieceKind.PAWN, PieceColor.BLACK, BoardSquare(3, 5), Vec3(0f, 0f, 0f), 0f)
            ),
            sideToMove = PieceColor.WHITE
        )

        val next = Board3DScene(
            pieces = listOf(
                Piece3DInstance(PieceKind.PAWN, PieceColor.WHITE, BoardSquare(3, 5), Vec3(0f, 0f, 0f), 0f)
            ),
            sideToMove = PieceColor.BLACK
        )

        val diff = Board3DSceneDiffer.diff(prev, next)
        assertTrue(diff is Board3DTransition.Capture)
        assertEquals(BoardSquare(4, 4), diff.move.from)
        assertEquals(BoardSquare(3, 5), diff.move.to)
        assertEquals(BoardSquare(3, 5), diff.capturedSquare)
        assertEquals(PieceKind.PAWN, diff.capturedKind)
        assertEquals(PieceColor.BLACK, diff.capturedColor)
    }

    @Test
    fun testPromotion() {
        val prev = Board3DScene(
            pieces = listOf(
                Piece3DInstance(PieceKind.PAWN, PieceColor.WHITE, BoardSquare(1, 4), Vec3(0f, 0f, 0f), 0f)
            ),
            sideToMove = PieceColor.WHITE
        )

        val next = Board3DScene(
            pieces = listOf(
                Piece3DInstance(PieceKind.QUEEN, PieceColor.WHITE, BoardSquare(0, 4), Vec3(0f, 0f, 0f), 0f)
            ),
            sideToMove = PieceColor.BLACK
        )

        val diff = Board3DSceneDiffer.diff(prev, next)
        assertTrue(diff is Board3DTransition.Promotion)
        assertEquals(BoardSquare(1, 4), diff.move.from)
        assertEquals(BoardSquare(0, 4), diff.move.to)
        assertEquals(PieceKind.QUEEN, diff.promotedTo)
    }

    @Test
    fun testCastling() {
        val prev = Board3DScene(
            pieces = listOf(
                Piece3DInstance(PieceKind.KING, PieceColor.WHITE, BoardSquare(7, 4), Vec3(0f, 0f, 0f), 0f),
                Piece3DInstance(PieceKind.ROOK, PieceColor.WHITE, BoardSquare(7, 7), Vec3(0f, 0f, 0f), 0f)
            ),
            sideToMove = PieceColor.WHITE
        )

        val next = Board3DScene(
            pieces = listOf(
                Piece3DInstance(PieceKind.KING, PieceColor.WHITE, BoardSquare(7, 6), Vec3(0f, 0f, 0f), 0f),
                Piece3DInstance(PieceKind.ROOK, PieceColor.WHITE, BoardSquare(7, 5), Vec3(0f, 0f, 0f), 0f)
            ),
            sideToMove = PieceColor.BLACK
        )

        val diff = Board3DSceneDiffer.diff(prev, next)
        assertTrue(diff is Board3DTransition.Move)
        assertEquals(BoardSquare(7, 4), diff.from)
        assertEquals(BoardSquare(7, 6), diff.to)
        assertEquals(PieceKind.KING, diff.kind)
        assertTrue(diff.secondary != null)
        assertEquals(BoardSquare(7, 7), diff.secondary!!.from)
        assertEquals(BoardSquare(7, 5), diff.secondary!!.to)
        assertEquals(PieceKind.ROOK, diff.secondary!!.kind)
    }
}
