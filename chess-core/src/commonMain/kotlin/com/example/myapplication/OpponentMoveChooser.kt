package com.example.myapplication

/**
 * Chooses the engine side's moves instead of the attached [ChessEngine]'s best move, e.g. a
 * human-move model playing as the opponent. The [ChessEngine] stays attached and keeps every other
 * job: hints, per-move assessments and draw offers all still come from it, because a model of what
 * humans play is not an evaluation.
 *
 * Kept engine-agnostic on purpose: the core knows nothing about any model, only that something
 * other than the engine may pick the move.
 */
interface OpponentMoveChooser {
    /** Who is playing, for the PGN and the History list, e.g. "Maia-3 (1400–1599)". */
    val name: String

    /**
     * A UCI move for the side to move in `fenHistory.last()`, or `null` to let the engine (or the
     * CPU fallback) move instead. [fenHistory] holds the most recent positions, oldest first, ending
     * with the current one. An illegal or unparseable move, or a thrown exception, is treated like
     * `null`.
     */
    suspend fun chooseMove(fenHistory: List<String>): String?
}

/** How many positions [OpponentMoveChooser.chooseMove] is given: the current one plus seven before it. */
const val OPPONENT_HISTORY_POSITIONS = 8
