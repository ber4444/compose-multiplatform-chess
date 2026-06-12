package com.example.myapplication

interface ChessEngine {
    suspend fun getBestMove(fen: String): String?
    fun close()
    
    /**
     * Position evaluation in centipawns from WHITE's perspective (positive = White better).
     * Mate-in-N maps to ±(100000 - N). Null = unavailable (callers fall back to material balance).
     */
    suspend fun evaluate(fen: String): Int? = null
}
