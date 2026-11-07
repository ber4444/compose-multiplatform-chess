package com.example.myapplication.multiplayer

import com.example.myapplication.FenConverter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class LichessMoveCodecTest {
    @Test
    fun normalizesBothLichessCastles() {
        val board = LichessMoveCodec.replay("r3k2r/8/8/8/8/8/8/R3K2R w KQkq - 0 1", listOf("e1h1", "e8a8"))
        assertEquals(listOf("e1g1", "e8c8"), board.moveHistory.map { it.uci })
        assertEquals(listOf("O-O", "O-O-O"), board.moveHistory.map { it.san })
        assertEquals("2kr3r/8/8/8/8/8/8/R4RK1 w - - 2 2", FenConverter.gameStateToFen(board))
    }

    @Test
    fun enPassantAndUnderpromotionKeepThePlayedMove() {
        val capture = LichessMoveCodec.replay("startpos", listOf("e2e4", "a7a6", "e4e5", "d7d5", "e5d6"))
        assertEquals("exd6", capture.moveHistory.last().san)
        val promotion = LichessMoveCodec.replay("7k/P7/8/8/8/8/8/7K w - - 0 1", listOf("a7a8n"))
        assertEquals("a8=N", promotion.moveHistory.single().san)
    }

    @Test
    fun replayRejectsIllegalAndIncompleteMoves() {
        assertFailsWith<IllegalArgumentException> { LichessMoveCodec.replay("startpos", listOf("e2e5")) }
        assertFailsWith<IllegalArgumentException> {
            LichessMoveCodec.replay("7k/P7/8/8/8/8/8/7K w - - 0 1", listOf("a7a8"))
        }
    }
}
