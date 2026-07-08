package com.example.myapplication

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class UciEvaluationTest {

    @Test
    fun testParseInfoScore() {
        assertEquals(35, UciEvaluation.parseInfoScore("info depth 12 ... score cp 35 ... pv e2e4"))
        assertEquals(-210, UciEvaluation.parseInfoScore("info depth 12 score cp -210"))
        assertEquals(13, UciEvaluation.parseInfoScore("info score cp 13 lowerbound"))
        assertEquals(99997, UciEvaluation.parseInfoScore("info score mate 3"))
        assertEquals(-99998, UciEvaluation.parseInfoScore("info score mate -2"))
        assertEquals(-100000, UciEvaluation.parseInfoScore("info score mate 0"))
        
        assertNull(UciEvaluation.parseInfoScore("bestmove e2e4"))
        assertNull(UciEvaluation.parseInfoScore("info string NNUE evaluation"))
        assertNull(UciEvaluation.parseInfoScore("info depth 12"))
    }

    @Test
    fun testToWhitePerspective() {
        assertEquals(-50, UciEvaluation.toWhitePerspective(50, whiteToMove = false))
        assertEquals(50, UciEvaluation.toWhitePerspective(50, whiteToMove = true))
        assertEquals(100, UciEvaluation.toWhitePerspective(-100, whiteToMove = false))
    }

    @Test
    fun testIsWhiteToMove() {
        assertTrue(UciEvaluation.isWhiteToMove("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"))
        assertFalse(UciEvaluation.isWhiteToMove("rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq e3 0 1"))
        // Fallback for malformed
        assertTrue(UciEvaluation.isWhiteToMove("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR"))
    }
}
