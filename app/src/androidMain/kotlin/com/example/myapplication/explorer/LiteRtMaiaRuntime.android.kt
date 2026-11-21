package com.example.myapplication.explorer

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.tensorflow.lite.Interpreter

/** The bundled LiteRT export (`src/androidMain/resources/maia/`), pinned by [MaiaModelFiles]. */
internal object BundledMaiaTflite {
    const val RESOURCE = "/maia/${MaiaModelFiles.TFLITE_NAME}"

    /** Cheap: a resource lookup, no read. */
    fun isPresent(): Boolean = BundledMaiaTflite::class.java.getResource(RESOURCE) != null

    /** The model in a direct buffer, which is what [Interpreter] maps its weights from. */
    fun load(): ByteBuffer {
        val bytes = BundledMaiaTflite::class.java.getResourceAsStream(RESOURCE)?.use { it.readBytes() }
            ?: throw IOException("Maia model resource $RESOURCE is missing")
        if (bytes.size.toLong() != MaiaModelFiles.TFLITE_BYTES) {
            throw IOException("Maia model has ${bytes.size} bytes; expected ${MaiaModelFiles.TFLITE_BYTES}")
        }
        return ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).put(bytes).also { it.rewind() }
    }
}

internal actual fun maiaRuntimeFactory(): (suspend () -> MaiaRuntime)? =
    if (BundledMaiaTflite.isPresent()) ({ LiteRtMaiaRuntime(BundledMaiaTflite.load()) }) else null

/**
 * Maia-3 through LiteRT's interpreter on the CPU (XNNPACK). Two threads keep it from crowding
 * Stockfish. Inputs are the model's signature order: tokens, self Elo, opponent Elo (int32).
 */
internal class LiteRtMaiaRuntime(model: ByteBuffer) : MaiaRuntime {
    private val interpreter = Interpreter(model, Interpreter.Options().setNumThreads(2))
    private val tokens = ByteBuffer.allocateDirect(MaiaEncoder.SQUARES * MaiaEncoder.WIDTH * 4).order(ByteOrder.nativeOrder())
    private val logits = Array(1) { FloatArray(MaiaEncoder.VOCAB) }

    override suspend fun moveLogits(tokens: FloatArray, rating: Int): FloatArray {
        require(tokens.size == MaiaEncoder.SQUARES * MaiaEncoder.WIDTH) { "Bad token tensor: ${tokens.size}" }
        this.tokens.rewind()
        this.tokens.asFloatBuffer().put(tokens)
        val elo = intArrayOf(rating)
        interpreter.runForMultipleInputsOutputs(arrayOf(this.tokens, elo, elo.copyOf()), mapOf(0 to logits))
        return logits[0].copyOf()
    }

    override fun close() = interpreter.close()
}
