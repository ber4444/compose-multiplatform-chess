package com.example.myapplication.habits

import com.example.myapplication.MoveClass

/**
 * One flagged ply behind a [HabitSummary] — a player move whose [MoveClass] cost real winning
 * chances, kept so the UI can offer it as a practice position.
 *
 * [fenBefore] plus [bestMoveSan] is the whole "suggest a practice position" feature: the exact
 * position the habit recurred in, and the move that would have avoided it — both already computed
 * by [com.example.myapplication.MoveAssessor]/[com.example.myapplication.MotifDetector] when the
 * ply was assessed. Nothing here is invented; it is a reprojection of data that already exists.
 */
data class HabitOccurrence(
    val gameId: String,
    val gameResult: String,
    /** 0 = the most recent game considered, 1 = the one before that, etc. */
    val gamesAgo: Int,
    /** 1-based ply number, matching how a human would count moves in the game. */
    val plyNumber: Int,
    val san: String,
    val fenBefore: String,
    val moveClass: MoveClass,
    val cpLoss: Int,
    val bestMoveSan: String?,
)

/**
 * A cross-game pattern in the player's own [MoveAssessment][com.example.myapplication.MoveAssessment]
 * history. [motif] is a [com.example.myapplication.MotifDetector] slug (e.g. `"hangs-piece"`) when
 * the pattern is a specific recurring tactic, or `null` for the general "you keep giving back
 * winning chances" fallback used when no single tactic recurs often enough to name.
 *
 * [gamesAffected] counts **games**, not moves — a game with three hung pieces still counts once,
 * matching how a human would describe the habit ("in 4 of your last 10 games", not "12 times").
 */
data class HabitSummary(
    val motif: String?,
    val gamesAffected: Int,
    val gamesConsidered: Int,
    val occurrences: List<HabitOccurrence>,
)
