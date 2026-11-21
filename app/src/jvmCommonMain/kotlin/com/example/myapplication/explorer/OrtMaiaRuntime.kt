package com.example.myapplication.explorer

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.IOException
import java.nio.FloatBuffer
import java.nio.LongBuffer

/**
 * The bundled Maia-3 5M export (`src/jvmCommonMain/resources/maia/`), shared by Android and desktop.
 * Provenance, hashes and the reproducible export are in `docs/maia3-model-provenance.md`;
 * `MaiaModelProvenanceTest` fails if the file drifts from [SHA256].
 *
 * The file is AGPL-3.0 (CSSLab's Maia-3), distributed with this GPL-3.0-or-later app as GPLv3 §13
 * permits. See COPYING.md.
 */
internal object BundledMaiaModel {
    const val RESOURCE = "/maia/maia3-5m.onnx"
    const val BYTES = 21_346_659L
    const val SHA256 = "3d0b22170a965944379499737aac1f7375b93b9bca83446f91e9314443b78ab6"

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

internal actual fun maiaRuntimeFactory(): (() -> MaiaRuntime)? =
    if (BundledMaiaModel.isPresent()) ({ OrtMaiaRuntime(BundledMaiaModel.readBytes()) }) else null

/**
 * Maia-3 through ONNX Runtime's Java API, which `onnxruntime` (desktop) and `onnxruntime-android`
 * expose identically. Two intra-op threads and sequential execution keep peak memory and CPU
 * pressure predictable on phones; one inference is ~3 ms on Apple Silicon.
 */
internal class OrtMaiaRuntime(model: ByteArray) : MaiaRuntime {
    private val environment = OrtEnvironment.getEnvironment()
    private val session: OrtSession = OrtSession.SessionOptions().use { options ->
        options.setIntraOpNumThreads(2)
        options.setInterOpNumThreads(1)
        options.setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL)
        environment.createSession(model, options)
    }

    override fun moveLogits(tokens: FloatArray, rating: Int): FloatArray {
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
