package com.example.myapplication

interface ChessEngine {
    suspend fun getBestMove(fen: String): String?
    fun close()

    /**
     * Position evaluation in centipawns from WHITE's perspective (positive = White better).
     * Mate-in-N maps to ±(100000 - N). Null = unavailable (callers fall back to material balance).
     */
    suspend fun evaluate(fen: String): Int? = null

    /**
     * Apply a play-strength setting (issue #39 Phase 4). Default no-op: the built-in CPU fallback
     * (no `ChessEngine`) ignores it, and any engine that doesn't override it plays at default strength.
     * Real engines override this to send `setoption name Skill Level` + adjust their movetime budget.
     */
    suspend fun configure(difficulty: EngineDifficulty) {}
}
