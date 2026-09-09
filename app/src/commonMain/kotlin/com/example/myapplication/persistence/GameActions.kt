package com.example.myapplication.persistence

import com.example.myapplication.GameUiState
import com.example.myapplication.PgnSerializer
import com.example.myapplication.PgnTags
import com.example.myapplication.Set

/**
 * Builds the PGN artifacts saved/shared at game end (Phase 3). Pure logic — no platform or UI deps —
 * so it's unit-testable in `commonTest`.
 *
 * The human is always "Player" and the opponent is "Stockfish" when a real engine is attached, else
 * "CPU" — which colour each label lands on follows [playerSide]. This used to hardcode White as the
 * human regardless of [playerSide], from before the player-side setting (`#112`) existed: every
 * saved PGN and every `SavedGame` mislabelled the colours for a Black-side game, and — more
 * consequentially — [GameHistoryBackfiller] and cross-game habit aggregation both need to know which
 * colour's moves are the player's, and had no way to ask.
 */
object GameActions {

    /** Builds the [PgnTags] for the finished [state]. */
    fun pgnTags(
        state: GameUiState,
        engineAttached: Boolean,
        date: String = todayPgnDate(),
        playerSide: Set = Set.WHITE,
    ): PgnTags {
        val opponent = if (engineAttached) "Stockfish" else "CPU"
        return PgnTags(
            date = date,
            white = if (playerSide == Set.WHITE) "Player" else opponent,
            black = if (playerSide == Set.BLACK) "Player" else opponent,
            result = PgnSerializer.resultToken(state.winState),
        )
    }

    /** Builds the full PGN string (tags + movetext + result) for the finished [state]. */
    fun toPgn(
        state: GameUiState,
        engineAttached: Boolean,
        date: String = todayPgnDate(),
        playerSide: Set = Set.WHITE,
    ): String =
        PgnSerializer.toPgn(pgnTags(state, engineAttached, date, playerSide), state.moveHistory)

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
    ): SavedGame {
        val tags = pgnTags(state, engineAttached, date = todayPgnDate(), playerSide = playerSide)
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
}
