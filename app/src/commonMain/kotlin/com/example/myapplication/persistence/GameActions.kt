package com.example.myapplication.persistence

import com.example.myapplication.GameUiState
import com.example.myapplication.PgnSerializer
import com.example.myapplication.PgnTags
import com.example.myapplication.Set

/**
 * Builds the PGN artifacts saved/shared at game end (Phase 3). Pure logic — no platform or UI deps —
 * so it's unit-testable in `commonTest`.
 *
 * The human is always "Player"; the opponent is named by the caller (`GameViewModel.opponentName`:
 * "Maia-3 (1400–1599)", "Stockfish" or "CPU"), defaulting to "Stockfish" when a real engine is
 * attached, else "CPU". Which colour each label lands on follows [playerSide]. This used to hardcode
 * White as the human regardless of [playerSide], from before the player-side setting (`#112`)
 * existed: every saved PGN and every `SavedGame` mislabelled the colours for a Black-side game, and
 * — more consequentially — [GameHistoryBackfiller] and cross-game habit aggregation both need to know
 * which colour's moves are the player's, and had no way to ask.
 */
object GameActions {

    /** Builds the [PgnTags] for the finished [state]. */
    fun pgnTags(
        state: GameUiState,
        engineAttached: Boolean,
        date: String = todayPgnDate(),
        playerSide: Set = Set.WHITE,
        opponent: String = defaultOpponent(engineAttached),
    ): PgnTags = PgnTags(
        date = date,
        white = if (playerSide == Set.WHITE) "Player" else opponent,
        black = if (playerSide == Set.BLACK) "Player" else opponent,
        result = PgnSerializer.resultToken(state.winState),
    )

    /** Builds the full PGN string (tags + movetext + result) for the finished [state]. */
    fun toPgn(
        state: GameUiState,
        engineAttached: Boolean,
        date: String = todayPgnDate(),
        playerSide: Set = Set.WHITE,
        opponent: String = defaultOpponent(engineAttached),
    ): String = PgnSerializer.toPgn(pgnTags(state, engineAttached, date, playerSide, opponent), state.moveHistory)

    /**
     * Builds a [SavedGame] ready for [GameHistoryRepository.add]. The [id] is derived from
     * [savedAtEpochMillis] (falling back to a UUID-ish suffix on collision within the same ms is
     * unnecessary at this call rate — the repo dedupes by exact id on delete only).
     */
    fun toSavedGame(
        state: GameUiState,
        engineAttached: Boolean,
        savedAtEpochMillis: Long = nowEpochMillis(),
        playerSide: Set = Set.WHITE,
        opponent: String = defaultOpponent(engineAttached),
    ): SavedGame {
        val tags = pgnTags(state, engineAttached, date = todayPgnDate(), playerSide = playerSide, opponent = opponent)
        val pgn = PgnSerializer.toPgn(tags, state.moveHistory)
        return SavedGame(
            id = savedAtEpochMillis.toString(),
            savedAtEpochMillis = savedAtEpochMillis,
            result = tags.result,
            white = tags.white,
            black = tags.black,
            moveCount = state.moveHistory.size,
            pgn = pgn,
            moveRecords = state.moveHistory,
            playerSide = playerSide.name,
        )
    }

    /** `GameViewModel.opponentName`'s fallback, for callers with no view model. */
    private fun defaultOpponent(engineAttached: Boolean) = if (engineAttached) "Stockfish" else "CPU"
}
