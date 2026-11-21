package com.example.myapplication

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LegalMovesTest {
    private fun moves(fen: String) = legalUciMoves(FenConverter.fenToGameState(fen)).toSet()

    @Test
    fun startingPositionHasTwentyMoves() {
        val start = moves(FenConverter.STARTING_FEN)
        assertEquals(20, start.size)
        assertTrue("e2e4" in start && "g1f3" in start)
    }

    @Test
    fun backRankPawnMoveExpandsIntoFourPromotions() {
        val promotions = moves("8/4P1k1/8/8/8/8/6K1/8 w - - 0 1").filter { it.startsWith("e7") }
        assertEquals(setOf("e7e8q", "e7e8r", "e7e8b", "e7e8n"), promotions.toSet())
    }

    @Test
    fun blackPromotionsLandOnTheFirstRank() {
        val promotions = moves("8/6K1/8/8/8/8/2p1k3/8 b - - 0 1").filter { it.startsWith("c2") }
        assertEquals(setOf("c2c1q", "c2c1r", "c2c1b", "c2c1n"), promotions.toSet())
    }

    @Test
    fun castlingAndEnPassantAreIncluded() {
        val castles = moves("r3k2r/pppq1ppp/2npbn2/2b1p3/2B1P3/2NPBN2/PPPQ1PPP/R3K2R b KQkq - 6 8")
        assertTrue("e8g8" in castles && "e8c8" in castles)
        assertTrue("e5f6" in moves("rnbqkbnr/ppp1p1pp/8/3pPp2/8/8/PPPP1PPP/RNBQKBNR w KQkq f6 0 3"))
    }
}
