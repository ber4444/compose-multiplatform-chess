package com.example.myapplication

import com.example.myapplication.explorer.BundledMaiaParity
import com.example.myapplication.explorer.MaiaModelFiles
import com.example.myapplication.explorer.TfLiteMaiaRuntime
import com.example.myapplication.explorer.maiaRuntimeFactory
import game.app.generated.resources.Res
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.test.runTest
import org.jetbrains.compose.resources.ExperimentalResourceApi
import platform.CoreCrypto.CC_SHA256
import platform.CoreCrypto.CC_SHA256_DIGEST_LENGTH
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.measureTime

/** iOS bundles the pinned int8 LiteRT file, runs it through the TFLite C API, and matches PyTorch. */
@OptIn(ExperimentalResourceApi::class, ExperimentalForeignApi::class)
class MaiaLiteRtSimulatorTest {
    @Test
    fun bundledModelIsTheProvenancePinnedFile() = runTest {
        val bytes = Res.readBytes("files/maia/${MaiaModelFiles.TFLITE_NAME}")
        assertEquals(MaiaModelFiles.TFLITE_BYTES, bytes.size.toLong())
        val sha = memScoped {
            val digest = allocArray<UByteVar>(CC_SHA256_DIGEST_LENGTH)
            bytes.usePinned { CC_SHA256(it.addressOf(0), bytes.size.convert(), digest) }
            (0 until CC_SHA256_DIGEST_LENGTH).joinToString("") { digest[it].toString(16).padStart(2, '0') }
        }
        assertEquals(MaiaModelFiles.TFLITE_SHA256, sha)
    }

    @Test
    fun topMovesMatchReferencePyTorch() = runTest {
        BundledMaiaParity.assertMatchesPyTorch(assertNotNull(maiaRuntimeFactory()), BundledMaiaParity.INT8_SHARE_TOLERANCE)
    }

    @Test
    fun iosRunsLiteRtAndLogsItsCost() = runTest {
        val open = assertNotNull(maiaRuntimeFactory(), "iOS must find the bundled model")
        lateinit var runtime: com.example.myapplication.explorer.MaiaRuntime
        val load = measureTime { runtime = open() }
        runtime.use {
            assertTrue(it is TfLiteMaiaRuntime)
            val tokens = FloatArray(64 * 97)
            val warm = mutableListOf<kotlin.time.Duration>()
            repeat(20) { _ -> warm += measureTime { it.moveLogits(tokens, 1500) } }
            warm.sort()
            println("MaiaLiteRt load=${load.inWholeMilliseconds}ms inference median=${warm[10].inWholeMicroseconds}us")
        }
    }
}
