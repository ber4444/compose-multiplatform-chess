package com.example.myapplication

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable

/**
 * A single ply recorded for PGN / history. Built once per move inside [GameViewModel.deriveNewGameState]
 * from the pre-move state, the resulting post-move FEN, and the SAN built by [SanConverter].
 *
 * - [uci] is the UCI movetext (e.g. "e2e4", "e7e8q") — useful to replay the move against a UCI engine.
 * - [san] is Standard Algebraic Notation (e.g. "Nf3", "exd5", "O-O", "e8=Q+") — what PGN movetext
 *   is made of.
 * - [fenAfter] is the full FEN after the move — handy for history scrubbing, validation, and the
 *   Phase 3 "load saved game" affordance.
 */
@Immutable
@Serializable
data class MoveRecord(
    val uci: String,
    val san: String,
    val fenAfter: String,
)
