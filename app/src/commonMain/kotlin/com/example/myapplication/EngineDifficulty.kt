package com.example.myapplication

/**
 * Engine play-strength setting (issue #39 Phase 4). Each level maps to:
 *  - [skillLevel]: Stockfish `Skill Level` option (0–20), sent via `setoption name Skill Level value N`.
 *  - [thinkTimeMs]: per-move `movetime` budget (ms) used in the `go movetime` command.
 *
 * Lower levels weaken play both by lowering Stockfish's skill and by giving it less time to think.
 * The CPU fallback (no `ChessEngine` attached) ignores this — it always uses `pickMoveCPU`.
 */
enum class EngineDifficulty(val skillLevel: Int, val thinkTimeMs: Long) {
    EASY(skillLevel = 2, thinkTimeMs = 200L),
    MEDIUM(skillLevel = 8, thinkTimeMs = 500L),
    HARD(skillLevel = 15, thinkTimeMs = 1000L),
    MAX(skillLevel = 20, thinkTimeMs = 2000L);
}
