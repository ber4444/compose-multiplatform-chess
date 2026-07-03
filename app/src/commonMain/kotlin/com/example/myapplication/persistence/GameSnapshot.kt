package com.example.myapplication.persistence

import com.example.myapplication.GameUiState
import com.example.myapplication.WinState
import com.example.myapplication.FenConverter
import com.example.myapplication.MoveRecord
import com.example.myapplication.Set
import kotlinx.serialization.Serializable

/**
 * A serializable snapshot of a game in progress — the autosave payload for resume-later (Phase 2).
 *
 * The board, clocks, castling rights, en passant target and side-to-move round-trip through FEN
 * (lossless, already implemented by [FenConverter]); the rest of the state that FEN does *not*
 * carry — the PGN move list, the threefold `positionHistory`, the win/draw flags and draw-offer
 * bookkeeping — is stored in small @Serializable DTOs alongside it. See the plan's "Serialize DTOs,
 * never `GameUiState` directly" architectural note.
 *
 * [clockWhiteMillis]/[clockBlackMillis] are reserved for the Phase 5 time-control subsystem; they
 * stay null until then (the mapper seeds the VM with them only when present).
 */
@Serializable
data class GameSnapshot(
    val fen: String,
    val moveHistory: List<MoveRecord> = emptyList(),
    val positionHistory: List<String> = emptyList(),
    val winState: WinState = WinState.NONE,
    val drawOffer: String? = null,            // Set?.name
    val drawOfferDeclinedBy: String? = null,
    val lastDrawOfferFullmove: Int = 0,
    val clockWhiteMillis: Long? = null,        // Phase 5
    val clockBlackMillis: Long? = null,        // Phase 5
    val savedAtEpochMillis: Long = 0,          // populated by Phase 3 (nowEpochMillis())
)

/**
 * Converts between a live [GameUiState] and a serializable [GameSnapshot]. All `Set?`/`WinState`
 * fields are stored as their `.name` strings (FEN-safe, stable across refactors) and restored with
 * tolerant `runCatching` so a stale or hand-edited value can never crash load.
 */
object GameSnapshotMapper {

    fun fromState(state: GameUiState, clockWhiteMillis: Long? = null, clockBlackMillis: Long? = null): GameSnapshot =
        GameSnapshot(
            fen = FenConverter.gameStateToFen(state),
            moveHistory = state.moveHistory,
            positionHistory = state.positionHistory,
            winState = state.winState,
            drawOffer = state.drawOffer?.name,
            drawOfferDeclinedBy = state.drawOfferDeclinedBy?.name,
            lastDrawOfferFullmove = state.lastDrawOfferFullmove,
            clockWhiteMillis = clockWhiteMillis,
            clockBlackMillis = clockBlackMillis,
        )

    /**
     * Rebuilds a [GameUiState] from a snapshot. Starts from [FenConverter.fenToGameState] (board,
     * turn, clocks, castling, en passant) then `.copy`s the non-FEN fields back in.
     *
     * Terminal/check state is **not** re-derived here — `GameViewModel.init` already re-applies
     * `applyWinConditions`/`applyDrawConditions` defensively on the restored state, and entry-point
     * code paths always construct a VM from the result of `toState`. Callers that need the
     * re-derived terminal state without a VM should re-run those checks themselves.
     */
    fun toState(snapshot: GameSnapshot): GameUiState =
        FenConverter.fenToGameState(snapshot.fen).copy(
            moveHistory = snapshot.moveHistory,
            positionHistory = snapshot.positionHistory,
            winState = snapshot.winState,
            drawOffer = snapshot.drawOffer?.let { parseSet(it) },
            drawOfferDeclinedBy = snapshot.drawOfferDeclinedBy?.let { parseSet(it) },
            lastDrawOfferFullmove = snapshot.lastDrawOfferFullmove,
        )

    private fun parseSet(name: String): Set? = runCatching { Set.valueOf(name) }.getOrNull()
}
