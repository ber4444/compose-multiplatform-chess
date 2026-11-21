package com.example.myapplication.explorer

import com.example.myapplication.FenConverter
import com.example.myapplication.GameUiState
import com.example.myapplication.PromotionType
import com.example.myapplication.Set
import com.example.myapplication.UciMoveConverter
import com.example.myapplication.applyMove
import java.io.File
import kotlin.math.abs
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * End-to-end parity: Kotlin encoder + ONNX Runtime against the reference PyTorch top moves in
 * `MaiaGolden`. Needs the exported model (`tools/maia3/README.md`) at `CHESS_MAIA_MODEL`, so it is
 * skipped — visibly, as an assumption failure — on CI, which has no model to load.
 */
class OnnxMaiaRuntimeTest {
    @Test
    fun topMovesMatchReferencePyTorch() = runBlocking {
        val path = System.getenv(MAIA_MODEL_ENV)
        assumeTrue("$MAIA_MODEL_ENV not set; skipping ONNX parity", !path.isNullOrBlank() && File(path).isFile)
        MaiaHumanMovePredictor(OnnxMaiaRuntime(File(path))).use { predictor ->
            for (case in MaiaGolden.cases) {
                val position = ExplorerPosition(history(case).last(), history(case).takeLast(MAIA_HISTORY))
                for ((rating, expected) in case.top) {
                    val band = RatingBand.forRating(rating)
                    // The golden ratings are band representatives, so the band round-trips exactly.
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
