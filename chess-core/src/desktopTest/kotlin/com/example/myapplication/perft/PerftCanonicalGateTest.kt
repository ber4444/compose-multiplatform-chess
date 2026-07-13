package com.example.myapplication.perft

import com.example.myapplication.FenConverter
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Oracle 1 (deep) — full canonical gate, desktop/JVM only.
 *
 * Per the plan §4.2, the always-on per-commit gate runs on the desktop target where allocation
 * is cheap: start depth 4 (197,281), Kiwipete depth 3 (97,862), Position 3 depth 4 (43,238),
 * Positions 4/5/6 depth 3. (Common-test shallow depths in [PerftTest] cover wasm/native.)
 *
 * The full command the autonomous loop targets — `./gradlew :app:desktopTest --tests "*Perft*"`
 * — runs both classes. Reference values are arithmetic ground truth (see [PerftPositions]);
 * never edit them to make a failing test pass.
 */
class PerftCanonicalGateTest {

    @Test
    fun start_depth_4() =
        assertEquals(197_281L, perft(FenConverter.fenToGameState(PerftPositions.START.fen), 4))

    @Test
    fun kiwipete_depth_3() =
        assertEquals(97_862L, perft(FenConverter.fenToGameState(PerftPositions.KIWIPETE.fen), 3))

    @Test
    fun position_3_depth_4() =
        assertEquals(43_238L, perft(FenConverter.fenToGameState(PerftPositions.POSITION_3.fen), 4))

    @Test
    fun position_4_depth_3() =
        assertEquals(9_467L, perft(FenConverter.fenToGameState(PerftPositions.POSITION_4.fen), 3))

    @Test
    fun position_5_depth_3() =
        assertEquals(62_379L, perft(FenConverter.fenToGameState(PerftPositions.POSITION_5.fen), 3))

    @Test
    fun position_6_depth_3() =
        assertEquals(89_890L, perft(FenConverter.fenToGameState(PerftPositions.POSITION_6.fen), 3))
}
