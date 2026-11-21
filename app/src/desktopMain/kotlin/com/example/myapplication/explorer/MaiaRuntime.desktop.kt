package com.example.myapplication.explorer

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer

/**
 * Desktop runs Maia-3 through ONNX Runtime when `CHESS_MAIA_MODEL` names an exported
 * `maia3-5m.onnx` (see `tools/maia3/README.md`). Unset, missing or unloadable means no runtime, and
 * the explorer falls back to Lichess alone — never a control that does nothing.
 *
 * The model is not downloaded: there is no hosted ONNX to download yet (Hugging Face carries only
 * the PyTorch checkpoint), and the licence of the weights needs a decision before the app fetches
 * them for users.
 */
internal actual fun createMaiaRuntime(): MaiaRuntime? {
    val path = System.getenv(MAIA_MODEL_ENV)?.takeIf { it.isNotBlank() } ?: return null
    val file = File(path)
    if (!file.isFile) {
        System.err.println("$MAIA_MODEL_ENV=$path is not a file; Maia predictions are off.")
        return null
    }
    return runCatching { OnnxMaiaRuntime(file) }
        .onFailure { System.err.println("Could not load Maia-3 from $path: ${it.message}") }
        .getOrNull()
}

internal const val MAIA_MODEL_ENV = "CHESS_MAIA_MODEL"

internal class OnnxMaiaRuntime(model: File) : MaiaRuntime {
    private val environment = OrtEnvironment.getEnvironment()
    private val session: OrtSession = OrtSession.SessionOptions().use { options ->
        environment.createSession(model.absolutePath, options)
    }

    override fun moveLogits(tokens: FloatArray, rating: Int): FloatArray {
        require(tokens.size == MaiaEncoder.SQUARES * MaiaEncoder.WIDTH) { "Bad token tensor: ${tokens.size}" }
        val elo = longArrayOf(rating.toLong())
        OnnxTensor.createTensor(environment, FloatBuffer.wrap(tokens), longArrayOf(1, 64, MaiaEncoder.WIDTH.toLong())).use { input ->
            OnnxTensor.createTensor(environment, LongBuffer.wrap(elo), longArrayOf(1)).use { self ->
                OnnxTensor.createTensor(environment, LongBuffer.wrap(elo), longArrayOf(1)).use { oppo ->
                    session.run(mapOf("tokens" to input, "self_elo" to self, "oppo_elo" to oppo), setOf("logits_move")).use { result ->
                        @Suppress("UNCHECKED_CAST")
                        return (result[0].value as Array<FloatArray>)[0]
                    }
                }
            }
        }
    }

    override fun close() = session.close()
}
