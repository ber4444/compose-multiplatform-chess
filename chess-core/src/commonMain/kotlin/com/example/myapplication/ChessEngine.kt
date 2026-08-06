package com.example.myapplication

data class BestMoveResult(
    val uci: String,
    val evaluationCp: Int?
)

interface ChessEngine {
    suspend fun getBestMove(fen: String, thinkTimeMs: Long? = null): BestMoveResult?
    fun close()

    /**
     * Position evaluation in centipawns from WHITE's perspective (positive = White better).
     * Mate-in-N maps to ±(100000 - N). Null = unavailable (callers fall back to material balance).
     * If thinkTimeMs is provided, bounds the evaluation by time rather than depth.
     */
    suspend fun evaluate(fen: String, thinkTimeMs: Long? = null): Int? = null

    /**
     * Apply a play-strength setting (issue #39 Phase 4). Default no-op: the built-in CPU fallback
     * (no `ChessEngine`) ignores it, and any engine that doesn't override it plays at default strength.
     * Real engines override this to send `setoption name Skill Level` + adjust their movetime budget.
     */
    suspend fun configure(difficulty: EngineDifficulty) {}
}
