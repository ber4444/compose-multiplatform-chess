package com.example.myapplication

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class UciMoveConverterTest {

    @Test
    fun `uciSquareToPosition converts correctly`() {
        assertEquals(Pair(7, 0), UciMoveConverter.uciSquareToPosition("a1"))
        assertEquals(Pair(0, 7), UciMoveConverter.uciSquareToPosition("h8"))
        assertEquals(Pair(6, 4), UciMoveConverter.uciSquareToPosition("e2"))
        assertEquals(Pair(4, 4), UciMoveConverter.uciSquareToPosition("e4"))
        assertEquals(Pair(1, 3), UciMoveConverter.uciSquareToPosition("d7"))
    }

    @Test
    fun `positionToUciSquare converts correctly`() {
        assertEquals("a1", UciMoveConverter.positionToUciSquare(Pair(7, 0)))
        assertEquals("h8", UciMoveConverter.positionToUciSquare(Pair(0, 7)))
        assertEquals("e2", UciMoveConverter.positionToUciSquare(Pair(6, 4)))
        assertEquals("e4", UciMoveConverter.positionToUciSquare(Pair(4, 4)))
        assertEquals("d7", UciMoveConverter.positionToUciSquare(Pair(1, 3)))
    }

    @Test
    fun `round trip conversion is identity`() {
        for (row in 0 until BOARD_SIZE) {
            for (col in 0 until BOARD_SIZE) {
                val pos = Pair(row, col)
                val uci = UciMoveConverter.positionToUciSquare(pos)
                val back = UciMoveConverter.uciSquareToPosition(uci)
                assertEquals(pos, back, "Round trip failed for $pos -> $uci")
            }
        }
    }

    @Test
    fun `parseUciMove parses standard moves`() {
        val (from, to) = UciMoveConverter.parseUciMove("e2e4")
        assertEquals(Pair(6, 4), from)
        assertEquals(Pair(4, 4), to)
    }

    @Test
    fun `parseUciMove parses promotion moves`() {
        val (from, to) = UciMoveConverter.parseUciMove("e7e8q")
        assertEquals(Pair(1, 4), from)
        assertEquals(Pair(0, 4), to)
    }

    @Test
    fun `uciMoveToAppMove finds correct piece`() {
        val positions = listOf(Pair(7, 4), Pair(6, 4))
        val result = UciMoveConverter.uciMoveToAppMove("e2e4", positions)

        assertNotNull(result)
        assertEquals(Pair(4, 4), result.position)
        assertEquals(1, result.pieceIndex)
    }

    @Test
    fun `uciMoveToAppMove returns null for missing piece`() {
        val positions = listOf(Pair(7, 4))
        val result = UciMoveConverter.uciMoveToAppMove("e2e4", positions)

        assertNull(result, "Should return null when no piece at source square")
    }

    @Test
    fun `appMoveToUci produces correct UCI string`() {
        val uci = UciMoveConverter.appMoveToUci(Pair(6, 4), Pair(4, 4))
        assertEquals("e2e4", uci)

        val uci2 = UciMoveConverter.appMoveToUci(Pair(0, 1), Pair(2, 0))
        assertEquals("b8a6", uci2)
    }

    @Test
    fun `uciSquareToPosition rejects invalid file`() {
        assertFailsWith<IllegalArgumentException> {
            UciMoveConverter.uciSquareToPosition("i1")
        }
    }

    @Test
    fun `uciSquareToPosition rejects invalid rank`() {
        assertFailsWith<IllegalArgumentException> {
            UciMoveConverter.uciSquareToPosition("a9")
        }
    }

    @Test
    fun `uciSquareToPosition rejects wrong length`() {
        assertFailsWith<IllegalArgumentException> {
            UciMoveConverter.uciSquareToPosition("e")
        }
    }

    @Test
    fun `parseUciMove rejects too short`() {
        assertFailsWith<IllegalArgumentException> {
            UciMoveConverter.parseUciMove("e2")
        }
    }

    @Test
    fun `uciMoveToAppMove parses promotion`() {
        val positions = listOf(Pair(1, 0)) // a7
        
        val qResult = UciMoveConverter.uciMoveToAppMove("a7a8q", positions)
        assertNotNull(qResult)
        assertEquals(PromotionType.QUEEN, qResult.promotion)

        val nResult = UciMoveConverter.uciMoveToAppMove("a7a8n", positions)
        assertNotNull(nResult)
        assertEquals(PromotionType.KNIGHT, nResult.promotion)

        val nullPromo = UciMoveConverter.uciMoveToAppMove("e2e4", listOf(Pair(6, 4)))
        assertNotNull(nullPromo)
        assertNull(nullPromo.promotion)

        val invalidPromo = UciMoveConverter.uciMoveToAppMove("a7a8x", positions)
        assertNull(invalidPromo)
    }
}
