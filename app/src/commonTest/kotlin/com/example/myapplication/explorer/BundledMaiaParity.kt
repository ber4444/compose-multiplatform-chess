package com.example.myapplication.explorer

import com.example.myapplication.FenConverter
import com.example.myapplication.GameUiState
import com.example.myapplication.PromotionType
import com.example.myapplication.Set
import com.example.myapplication.UciMoveConverter
import com.example.myapplication.applyMove
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * End-to-end parity: the Kotlin encoder plus a platform's Maia runtime against the reference
 * PyTorch top-5 moves in `MaiaGolden`. Not a test itself: commonTest also compiles into Android host
 * tests, where LiteRT's native library cannot load. `OnnxMaiaRuntimeTest` (desktop, ONNX) and
 * `MaiaLiteRtSimulatorTest` (iOS, LiteRT) call it; Android runs the same numbers on a device in
 * `MaiaOnDeviceTest`, and web was checked in a browser against the same file.
 */
internal object BundledMaiaParity {
    suspend fun assertMatchesPyTorch(open: suspend () -> MaiaRuntime) {
        MaiaHumanMovePredictor(open).use { predictor ->
            for (case in MaiaGolden.cases) {
                val history = history(case)
                val position = ExplorerPosition(history.last(), history.takeLast(MAIA_HISTORY))
                for ((rating, expected) in case.top) {
                    val band = RatingBand.forRating(rating)
                    assertEquals(rating, band.maiaRating, "golden ratings must be band ratings")
                    val actual = predictor.predict(position, band)!!.moves
                    assertEquals(expected.map { it.first }, actual.map { it.uci }, "${case.name} @ $rating")
                    expected.zip(actual).forEach { (want, got) ->
                        assertTrue(abs(want.second - got.share) < 1e-3, "${case.name} @ $rating ${want.first}: ${want.second} vs ${got.share}")
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
