package com.example.myapplication.perft

import com.example.myapplication.FenConverter
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.Assume

/**
 * Opt-in depth-5/6 canonical gate, desktop/JVM only.
 *
 * Always-on PR gates stop at start d4 / Kiwipete d3 (see [PerftCanonicalGateTest]) because the
 * parallel-list representation allocates heavily. The plan §4.2 calls for deeper runs to be
 * exercised by a CI nightly rather than every commit, so each test gates on the `perft.deep`
 * system property:
 *
 *     ./gradlew :app:desktopTest --tests "*PerftDeepTest*" -Dperft.deep=true
 *
 * Without `-Dperft.deep=true` every test in this class [Assume.assumeTrue]s out and JUnit reports
 * them as skipped, so collecting this class in a broad `--tests "*Perft*"` filter (e.g. the
 * per-commit gate) is harmless.
 *
 * Reference values are arithmetic ground truth (see [PerftPositions]); never edit them to make a
 * failing test pass.
 */
class PerftDeepTest {

    private fun assumeDeepEnabled() {
        Assume.assumeTrue(
            "Set -Dperft.deep=true to run opt-in depth-5/6 perft (too slow for the per-commit gate)",
            System.getProperty("perft.deep")?.toBoolean() == true,
        )
    }

    @Test
    fun start_depth_5() {
        assumeDeepEnabled()
        assertEquals(4_865_609L, perft(FenConverter.fenToGameState(PerftPositions.START.fen), 5))
    }

    @Test
    fun kiwipete_depth_4() {
        assumeDeepEnabled()
        assertEquals(4_085_603L, perft(FenConverter.fenToGameState(PerftPositions.KIWIPETE.fen), 4))
    }

    @Test
    fun position_3_depth_5() {
        assumeDeepEnabled()
        assertEquals(674_624L, perft(FenConverter.fenToGameState(PerftPositions.POSITION_3.fen), 5))
    }

    @Test
    fun position_4_depth_4() {
        assumeDeepEnabled()
        assertEquals(422_333L, perft(FenConverter.fenToGameState(PerftPositions.POSITION_4.fen), 4))
    }

    @Test
    fun position_5_depth_4() {
        assumeDeepEnabled()
        assertEquals(2_103_487L, perft(FenConverter.fenToGameState(PerftPositions.POSITION_5.fen), 4))
    }

    @Test
    fun position_6_depth_4() {
        assumeDeepEnabled()
        assertEquals(3_894_594L, perft(FenConverter.fenToGameState(PerftPositions.POSITION_6.fen), 4))
    }
}
