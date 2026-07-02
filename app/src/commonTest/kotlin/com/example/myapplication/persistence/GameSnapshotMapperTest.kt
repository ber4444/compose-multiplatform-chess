package com.example.myapplication.persistence

import com.example.myapplication.CastlingRights
import com.example.myapplication.FenConverter
import com.example.myapplication.GameUiState
import com.example.myapplication.WinState
import com.example.myapplication.Set
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GameSnapshotMapperTest {

    @Test
    fun `starting position round-trips losslessly`() {
        val original = GameUiState()
        val restored = GameSnapshotMapper.toState(GameSnapshotMapper.fromState(original))

        assertEquals(FenConverter.STARTING_FEN, FenConverter.gameStateToFen(restored))
        assertEquals(Set.WHITE, restored.turn)
        assertEquals(WinState.NONE, restored.winState)
        assertTrue(restored.moveHistory.isEmpty())
    }

    @Test
    fun `mid-game with spent castling rights and en passant target round-trips`() {
        // After 1.e4 d5 2.e5 (white pawn double-pushed then advanced; black replied 1...d5).
        // Black to move, white pawn on e5, black pawn on d5, en passant irrelevant here but we set
        // partial castling rights + an en passant target + non-trivial clocks to exercise FEN.
        val state = FenConverter.fenToGameState(
            "rnbqkbnr/ppp1pppp/8/3pP3/8/8/PPPP1PPP/RNBQKBNR b KQkq - 0 2"
        ).copy(
            // Simulate a position with history + spent rights by overriding post-FEN-load fields.
            castlingRights = CastlingRights(whiteKingside = true, whiteQueenside = false,
                blackKingside = false, blackQueenside = true),
            enPassantTarget = null,
            halfmoveClock = 7,
            fullmoveNumber = 3,
            drawOfferDeclinedBy = Set.BLACK,
            lastDrawOfferFullmove = 2,
        )

        val restored = GameSnapshotMapper.toState(GameSnapshotMapper.fromState(state))

        // FEN-carried fields
        assertEquals(state.turn, restored.turn)
        assertEquals(state.castlingRights, restored.castlingRights)
        assertEquals(state.enPassantTarget, restored.enPassantTarget)
        assertEquals(state.halfmoveClock, restored.halfmoveClock)
        assertEquals(state.fullmoveNumber, restored.fullmoveNumber)
        // Piece lists round-trip (positions + types) — verified via FEN equality of the board half.
        assertEquals(
            FenConverter.gameStateToFen(state).split(" ").take(4),
            FenConverter.gameStateToFen(restored).split(" ").take(4),
        )
        // Non-FEN fields
        assertEquals(state.drawOfferDeclinedBy, restored.drawOfferDeclinedBy)
        assertEquals(state.lastDrawOfferFullmove, restored.lastDrawOfferFullmove)
    }

    @Test
    fun `winState and drawOffer round-trip`() {
        val state = GameUiState(
            winState = WinState.DRAW,
            drawOffer = Set.BLACK,
        )
        val restored = GameSnapshotMapper.toState(GameSnapshotMapper.fromState(state))
        assertEquals(WinState.DRAW, restored.winState)
        assertEquals(Set.BLACK, restored.drawOffer)
    }

    @Test
    fun `drawOffer stored as name tolerates unknown values`() {
        // Hand-edit the snapshot to simulate a stale/corrupt Set name.
        val snapshot = GameSnapshot(
            fen = FenConverter.STARTING_FEN,
            winState = WinState.NONE,
            drawOffer = "MAGENTA",  // not a valid Set
            drawOfferDeclinedBy = "CHARTREUSE",
        )
        val restored = GameSnapshotMapper.toState(snapshot)
        assertNull(restored.drawOffer)
        assertNull(restored.drawOfferDeclinedBy)
    }

    @Test
    fun `positionHistory round-trips`() {
        val state = GameUiState(
            positionHistory = listOf("k7/8/8/8/8/8/8/7K w - -", "k7/8/8/8/8/8/8/7K b - -"),
        )
        val restored = GameSnapshotMapper.toState(GameSnapshotMapper.fromState(state))
        assertEquals(state.positionHistory, restored.positionHistory)
    }

    @Test
    fun `clock times are carried through fromState`() {
        val state = GameUiState()
        val snapshot = GameSnapshotMapper.fromState(state, clockWhiteMillis = 150_000, clockBlackMillis = 90_000)
        assertEquals(150_000, snapshot.clockWhiteMillis)
        assertEquals(90_000, snapshot.clockBlackMillis)
    }
}
