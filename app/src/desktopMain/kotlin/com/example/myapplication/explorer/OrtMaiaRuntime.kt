package com.example.myapplication.explorer

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.IOException
import java.nio.FloatBuffer
import java.nio.LongBuffer

/** The bundled ONNX export (`src/desktopMain/resources/maia/`), pinned by [MaiaModelFiles]. */
internal object BundledMaiaModel {
    const val RESOURCE = "/maia/${MaiaModelFiles.ONNX_NAME}"
    const val BYTES = MaiaModelFiles.ONNX_BYTES
    const val SHA256 = MaiaModelFiles.ONNX_SHA256

    /** Cheap: a resource lookup, no read. */
    fun isPresent(): Boolean = BundledMaiaModel::class.java.getResource(RESOURCE) != null

    fun readBytes(): ByteArray {
        val bytes = BundledMaiaModel::class.java.getResourceAsStream(RESOURCE)?.use { it.readBytes() }
            ?: throw IOException("Maia model resource $RESOURCE is missing")
        // A truncated or placeholder file would otherwise load as a confusing ONNX parse error.
        if (bytes.size.toLong() != BYTES) throw IOException("Maia model has ${bytes.size} bytes; expected $BYTES")
        return bytes
    }
}

internal actual fun maiaRuntimeFactory(): (suspend () -> MaiaRuntime)? =
    if (BundledMaiaModel.isPresent()) ({ OrtMaiaRuntime(BundledMaiaModel.readBytes()) }) else null

/**
 * Maia-3 through ONNX Runtime on desktop. LiteRT has no desktop JVM runtime, so this is the one
 * platform on ONNX. Two intra-op threads and sequential execution keep CPU use modest next to
 * Stockfish; one inference is ~3 ms on Apple Silicon.
 */
internal class OrtMaiaRuntime(model: ByteArray) : MaiaRuntime {
    private val environment = OrtEnvironment.getEnvironment()
    private val session: OrtSession = OrtSession.SessionOptions().use { options ->
        options.setIntraOpNumThreads(2)
        options.setInterOpNumThreads(1)
        options.setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL)
        environment.createSession(model, options)
    }

    override suspend fun moveLogits(tokens: FloatArray, rating: Int): FloatArray {
        require(tokens.size == MaiaEncoder.SQUARES * MaiaEncoder.WIDTH) { "Bad token tensor: ${tokens.size}" }
        val shape = longArrayOf(1, MaiaEncoder.SQUARES.toLong(), MaiaEncoder.WIDTH.toLong())
        val elo = longArrayOf(rating.toLong())
        OnnxTensor.createTensor(environment, FloatBuffer.wrap(tokens), shape).use { input ->
            OnnxTensor.createTensor(environment, LongBuffer.wrap(elo), longArrayOf(1)).use { self ->
                OnnxTensor.createTensor(environment, LongBuffer.wrap(elo), longArrayOf(1)).use { oppo ->
                    val inputs = mapOf("tokens" to input, "self_elo" to self, "oppo_elo" to oppo)
                    session.run(inputs, setOf("logits_move")).use { result ->
                        @Suppress("UNCHECKED_CAST")
                        return (result[0].value as Array<FloatArray>)[0]
                    }
                }
            }
        }
    }

    override fun close() = session.close()
}
