package com.example.myapplication.explorer

import com.example.myapplication.FenConverter
import com.example.myapplication.GameUiState
import com.example.myapplication.PromotionType
import com.example.myapplication.Set
import com.example.myapplication.UciMoveConverter
import com.example.myapplication.applyMove
import java.security.MessageDigest
import kotlin.math.abs
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * End-to-end parity: the Kotlin encoder plus ONNX Runtime over the *bundled* model, against the
 * reference PyTorch top moves in `MaiaGolden`. Runs everywhere desktop tests run.
 */
class OnnxMaiaRuntimeTest {
    @Test
    fun bundledModelIsWhatTheProvenanceDocumentPins() {
        val bytes = BundledMaiaModel.readBytes()
        val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        assertEquals(BundledMaiaModel.SHA256, sha, "re-export with tools/maia3 and update docs/maia3-model-provenance.md")
    }

    @Test
    fun topMovesMatchReferencePyTorch() = runBlocking {
        val open = assertNotNull(maiaRuntimeFactory(), "desktop must find the bundled model")
        MaiaHumanMovePredictor(open).use { predictor ->
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
