package com.example.myapplication.habits

import com.example.myapplication.MoveClass
import com.example.myapplication.MotifDetector
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HabitNarratorTest {

    private fun occurrence(gamesAgo: Int = 0, plyNumber: Int = 1, bestMoveSan: String? = "Nf6") = HabitOccurrence(
        gameId = "g",
        gameResult = "0-1",
        gamesAgo = gamesAgo,
        plyNumber = plyNumber,
        san = "Nc6",
        fenBefore = "startpos",
        moveClass = MoveClass.BLUNDER,
        cpLoss = 400,
        bestMoveSan = bestMoveSan,
    )

    @Test
    fun `headline names the motif and both counts`() {
        val summary = HabitSummary(MotifDetector.HANGS_PIECE, gamesAffected = 4, gamesConsidered = 10, occurrences = emptyList())
        val headline = HabitNarrator.headline(summary)
        assertTrue("hung a piece" in headline)
        assertTrue("4" in headline)
        assertTrue("10" in headline)
    }

    @Test
    fun `headline falls back to a general phrase when motif is null`() {
        val summary = HabitSummary(null, gamesAffected = 3, gamesConsidered = 10, occurrences = emptyList())
        assertTrue("given back winning chances" in HabitNarrator.headline(summary))
    }

    @Test
    fun `occurrence line names the recency the move and the better alternative`() {
        val line = HabitNarrator.occurrenceLine(occurrence(gamesAgo = 0, plyNumber = 1))
        assertTrue("your most recent game" in line)
        assertTrue("1.Nc6" in line)
        assertTrue("Nf6" in line)
    }

    @Test
    fun `occurrence line handles a Black ply number and older games`() {
        val line = HabitNarrator.occurrenceLine(occurrence(gamesAgo = 3, plyNumber = 6, bestMoveSan = null))
        assertTrue("3 games ago" in line)
        // ply 6 (1-based) is White's 3rd... wait: (6-1)/2+1 = 3, (6-1)%2==1 -> Black's move -> "3..."
        assertTrue("3...Nc6" in line)
        assertEquals(false, line.contains(" — "))
    }
}
