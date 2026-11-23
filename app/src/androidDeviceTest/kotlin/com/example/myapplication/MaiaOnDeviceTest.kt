package com.example.myapplication

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.myapplication.explorer.ExplorerPosition
import com.example.myapplication.explorer.MaiaHumanMovePredictor
import com.example.myapplication.explorer.MaiaModelFiles
import com.example.myapplication.explorer.RatingBand
import com.example.myapplication.explorer.maiaRuntimeFactory
import kotlin.math.abs
import kotlin.time.measureTime
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The bundled int8 Maia-3 LiteRT model loads and predicts through LiteRT's interpreter, and agrees with
 * the reference PyTorch numbers (copied from `MaiaGolden`, which this source set cannot see). Proves the
 * release-shaped wiring: the resource is packaged, the JNI classes survive, the encoder matches.
 * Logs load and inference time under the `MaiaOnDevice` tag.
 *
 * Tolerances follow `BundledMaiaParity`: same top move, and each PyTorch share within
 * [INT8_SHARE_TOLERANCE], looked up by move rather than by rank.
 */
@RunWith(AndroidJUnit4::class)
class MaiaOnDeviceTest {
    @Test
    fun bundledModelIsTheProvenancePinnedFile() {
        val bytes = assertNotNull(javaClass.getResourceAsStream("/maia/${MaiaModelFiles.TFLITE_NAME}")).use { it.readBytes() }
        val sha = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        assertEquals(MaiaModelFiles.TFLITE_SHA256, sha)
    }

    @Test
    fun bundledModelPredictsReferenceMovesOnDevice(): Unit = runBlocking {
        val open = assertNotNull(maiaRuntimeFactory(), "the APK must carry maia/${MaiaModelFiles.TFLITE_NAME}")
        MaiaHumanMovePredictor(open).use { predictor ->
            val italian = ExplorerPosition("r1bqkbnr/pppp1ppp/2n5/4p3/2B1P3/5N2/PPPP1PPP/RNBQK2R b KQkq - 4 4")
            var first: List<Pair<String, Double>> = emptyList()
            val cold = measureTime {
                first = predictor.predict(italian, RatingBand.R1400)!!.moves.map { it.uci to it.share }
            }
            val expected = listOf("g8f6" to 0.353496, "f8c5" to 0.264120, "h7h6" to 0.114169, "d7d6" to 0.102508, "f8e7" to 0.053691)
            assertEquals(expected.first().first, first.first().first)
            val shares = first.toMap()
            expected.forEach { (uci, share) ->
                val got = assertNotNull(shares[uci], "$uci missing from $first")
                assertTrue(abs(share - got) < INT8_SHARE_TOLERANCE, "$uci: $share vs $got")
            }

            val start = ExplorerPosition(FenConverter.STARTING_FEN)
            val warm = (1..20).map { measureTime { predictor.predict(start, RatingBand.R1800) } }.sorted()
            val top = predictor.predict(start, RatingBand.R1800)!!.moves.first()
            assertEquals("e2e4", top.uci)
            assertTrue(abs(top.share - 0.590967) < INT8_SHARE_TOLERANCE, "e4 share ${top.share}")
            Log.i("MaiaOnDevice", "cold load+first=${cold.inWholeMilliseconds}ms warm median=${warm[10].inWholeMilliseconds}ms max=${warm.last().inWholeMilliseconds}ms")
        }
    }
}

/** `BundledMaiaParity.INT8_SHARE_TOLERANCE`, which this source set cannot see. Change both together. */
private const val INT8_SHARE_TOLERANCE = 0.02
