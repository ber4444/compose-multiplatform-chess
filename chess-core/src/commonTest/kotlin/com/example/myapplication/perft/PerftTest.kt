package com.example.myapplication.perft

import com.example.myapplication.FenConverter
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Oracle 1 — portable canonical gate. Runs on every target (`./gradlew test`).
 *
 * Depths are bounded so the parallel-list generator stays fast on wasm/native (the slowest
 * runners): start depth 3 (8,902), Kiwipete depth 2 (2,039), Position 3 depth 3 (2,812),
 * Positions 4/5/6 depth 2. Deeper verification (start depth 4 = 197,281, Kiwipete depth 3 =
 * 97,862, etc.) lives in `desktopTest` — `PerftCanonicalGateTest` — because the desktop JVM is
 * the per-commit gate. Both classes match on `*Perft*` so the loop command picks them up.
 *
 * Reference values are arithmetic ground truth (see [PerftPositions]); never edit them to make
 * a failing test pass.
 */
class PerftTest {

    @Test
    fun start_depth_1() =
        assertEquals(20L, perft(FenConverter.fenToGameState(PerftPositions.START.fen), 1))

    @Test
    fun start_depth_2() =
        assertEquals(400L, perft(FenConverter.fenToGameState(PerftPositions.START.fen), 2))

    @Test
    fun start_depth_3() =
        assertEquals(8_902L, perft(FenConverter.fenToGameState(PerftPositions.START.fen), 3))

    @Test
    fun kiwipete_depth_1() =
        assertEquals(48L, perft(FenConverter.fenToGameState(PerftPositions.KIWIPETE.fen), 1))

    @Test
    fun kiwipete_depth_2() =
        assertEquals(2_039L, perft(FenConverter.fenToGameState(PerftPositions.KIWIPETE.fen), 2))

    @Test
    fun position_3_depth_1() =
        assertEquals(14L, perft(FenConverter.fenToGameState(PerftPositions.POSITION_3.fen), 1))

    @Test
    fun position_3_depth_2() =
        assertEquals(191L, perft(FenConverter.fenToGameState(PerftPositions.POSITION_3.fen), 2))

    @Test
    fun position_3_depth_3() =
        assertEquals(2_812L, perft(FenConverter.fenToGameState(PerftPositions.POSITION_3.fen), 3))

    @Test
    fun position_4_depth_1() =
        assertEquals(6L, perft(FenConverter.fenToGameState(PerftPositions.POSITION_4.fen), 1))

    @Test
    fun position_4_depth_2() =
        assertEquals(264L, perft(FenConverter.fenToGameState(PerftPositions.POSITION_4.fen), 2))

    @Test
    fun position_5_depth_1() =
        assertEquals(44L, perft(FenConverter.fenToGameState(PerftPositions.POSITION_5.fen), 1))

    @Test
    fun position_5_depth_2() =
        assertEquals(1_486L, perft(FenConverter.fenToGameState(PerftPositions.POSITION_5.fen), 2))

    @Test
    fun position_6_depth_1() =
        assertEquals(46L, perft(FenConverter.fenToGameState(PerftPositions.POSITION_6.fen), 1))

    @Test
    fun position_6_depth_2() =
        assertEquals(2_079L, perft(FenConverter.fenToGameState(PerftPositions.POSITION_6.fen), 2))

    @Test
    fun promotion_fan_out_is_counted_four_times() {
        // White pawn on a7, kings only otherwise. Promotion must expand to 4 distinct UCI moves
        // (Q/R/B/N); a generator that collapses them to 1 (default-Queen) is the classic perft bug.
        val state = FenConverter.fenToGameState("4k3/P7/8/8/8/8/8/4K3 w - - 0 1")
        val divide = perftDivide(state, 1)
        assertEquals(1L, divide["a7a8q"], "queen promotion missing")
        assertEquals(1L, divide["a7a8r"], "rook promotion missing")
        assertEquals(1L, divide["a7a8b"], "bishop promotion missing")
        assertEquals(1L, divide["a7a8n"], "knight promotion missing")
        // Whole-position depth-1 count: 5 king moves (d1/d2/e2/f1/f2) + 4 promotions.
        assertEquals(9L, perft(state, 1))
    }
}
