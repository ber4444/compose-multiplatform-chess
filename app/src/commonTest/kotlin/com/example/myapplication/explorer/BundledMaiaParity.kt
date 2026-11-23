package com.example.myapplication.explorer

import com.example.myapplication.FenConverter
import com.example.myapplication.GameUiState
import com.example.myapplication.PromotionType
import com.example.myapplication.Set
import com.example.myapplication.UciMoveConverter
import com.example.myapplication.applyMove
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * End-to-end parity: the Kotlin encoder plus a platform's Maia runtime against the reference
 * PyTorch top-5 moves in `MaiaGolden`. Not a test itself: commonTest also compiles into Android host
 * tests, where LiteRT's native library cannot load. `OnnxMaiaRuntimeTest` (desktop, float ONNX) and
 * `MaiaLiteRtSimulatorTest` (iOS, int8 LiteRT) call it; Android runs the same numbers on a device in
 * `MaiaOnDeviceTest`, and web was checked in a browser against the float LiteRT file.
 *
 * The top move must agree, and each of PyTorch's five moves must be within [shareTolerance] of its
 * share. The tail is compared by share rather than rank: PyTorch's fourth and fifth moves are often
 * a few tenths of a percent apart, and the int8 file swaps one such pair (`black_promotes` at 1500).
 */
internal object BundledMaiaParity {
    /** Float exports (ONNX, float LiteRT): measured within 2e-5 of PyTorch. */
    const val FLOAT_SHARE_TOLERANCE = 1e-3

    /**
     * The int8 LiteRT file Android and iOS bundle: measured within 0.0077 of PyTorch on `MaiaGolden`.
     * The same limit gates `tools/maia3/quantize_litert.py`; change both together.
     */
    const val INT8_SHARE_TOLERANCE = 0.02

    suspend fun assertMatchesPyTorch(open: suspend () -> MaiaRuntime, shareTolerance: Double) {
        // Every legal move, so a PyTorch move that ranks sixth here is still found by its share.
        MaiaHumanMovePredictor(open, top = Int.MAX_VALUE).use { predictor ->
            for (case in MaiaGolden.cases) {
                val history = history(case)
                val position = ExplorerPosition(history.last(), history.takeLast(MAIA_HISTORY))
                for ((rating, expected) in case.top) {
                    val band = RatingBand.forRating(rating)
                    assertEquals(rating, band.maiaRating, "golden ratings must be band ratings")
                    val actual = predictor.predict(position, band)!!.moves
                    assertEquals(expected.first().first, actual.first().uci, "${case.name} @ $rating: top move")
                    val shares = actual.associate { it.uci to it.share }
                    for ((uci, share) in expected) {
                        val got = assertNotNull(shares[uci], "${case.name} @ $rating: $uci is not legal")
                        assertTrue(abs(share - got) < shareTolerance, "${case.name} @ $rating $uci: $share vs $got")
                    }
                }
            }
        }
    }

    private fun history(case: MaiaGolden.Case): List<String> {
        var state = FenConverter.fenToGameState(case.startFen)
        val fens = mutableListOf(case.startFen)
        for (uci in case.moves) {
            state = apply(state, uci)
            fens += FenConverter.gameStateToFen(state)
        }
        return fens
    }

    private fun apply(state: GameUiState, uci: String): GameUiState {
        val (from, to) = UciMoveConverter.parseUciMove(uci)
        val mover = if (state.turn == Set.WHITE) state.positionsWhite else state.positionsBlack
        val promotion = uci.getOrNull(4)?.let { c -> PromotionType.entries.first { it.uciChar == c } }
        return applyMove(state, mover.indexOf(from), to, promotion)
    }
}
