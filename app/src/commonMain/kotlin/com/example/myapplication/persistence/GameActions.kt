package com.example.myapplication.persistence

import com.example.myapplication.GameUiState
import com.example.myapplication.PgnSerializer
import com.example.myapplication.PgnTags

/**
 * Builds the PGN artifacts saved/shared at game end (Phase 3). Pure logic — no platform or UI deps —
 * so it's unit-testable in `commonTest`.
 *
 * The player naming follows the plan: White is always the human "Player"; Black is "Stockfish" when
 * a real engine is attached, else "CPU".
 */
object GameActions {

    /** Builds the [PgnTags] for the finished [state]. */
    fun pgnTags(state: GameUiState, engineAttached: Boolean, date: String = todayPgnDate()): PgnTags = PgnTags(
        date = date,
        white = "Player",
        black = if (engineAttached) "Stockfish" else "CPU",
        result = PgnSerializer.resultToken(state.winState),
    )

    /** Builds the full PGN string (tags + movetext + result) for the finished [state]. */
    fun toPgn(state: GameUiState, engineAttached: Boolean, date: String = todayPgnDate()): String =
        PgnSerializer.toPgn(pgnTags(state, engineAttached, date), state.moveHistory)

    /**
     * Builds a [SavedGame] ready for [GameHistoryRepository.add]. The [id] is derived from
     * [savedAtEpochMillis] (falling back to a UUID-ish suffix on collision within the same ms is
     * unnecessary at this call rate — the repo dedupes by exact id on delete only).
     */
    fun toSavedGame(state: GameUiState, engineAttached: Boolean, savedAtEpochMillis: Long = nowEpochMillis()): SavedGame {
        val pgn = toPgn(state, engineAttached, date = todayPgnDate())
        return SavedGame(
            id = savedAtEpochMillis.toString(),
            savedAtEpochMillis = savedAtEpochMillis,
            result = PgnSerializer.resultToken(state.winState),
            white = "Player",
            black = if (engineAttached) "Stockfish" else "CPU",
            moveCount = state.moveHistory.size,
            pgn = pgn,
            moveRecords = state.moveHistory,
        )
    }
}
