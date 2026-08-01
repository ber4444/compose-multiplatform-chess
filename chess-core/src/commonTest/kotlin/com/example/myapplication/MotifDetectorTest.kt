package com.example.myapplication

import kotlin.test.Test
import kotlin.test.assertTrue

class MotifDetectorTest {
    @Test
    fun `detects fork`() {
        val fenBefore = "k7/8/8/8/8/8/8/3N3K w - - 0 1" // Knight at d1
        // Knight moves to e3, attacking king at c4 and rook at g4
        val fenAfter = "8/8/8/8/2k3r1/4N3/8/7K b - - 0 1"
        
        val stateBefore = FenConverter.fenToGameState(fenBefore)
        val stateAfter = FenConverter.fenToGameState(fenAfter)
        
        // e3 is (4, 4) in 0-indexed (col 4, row 4)
        // Wait, rank 8 is row 0, rank 1 is row 7.
        // file 'e' is col 4.
        // rank 3 is row 5.
        // So e3 is (5, 4)
        
        val motifs = MotifDetector.detectMotifs(stateBefore, stateAfter, Set.WHITE, Pair(5, 4))
        
        assertTrue(motifs.contains("Fork"))
    }
}
