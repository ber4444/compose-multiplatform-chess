package com.example.myapplication.explorer

import com.example.myapplication.tflite.TfLiteInterpreterAllocateTensors
import com.example.myapplication.tflite.TfLiteInterpreterCreate
import com.example.myapplication.tflite.TfLiteInterpreterDelete
import com.example.myapplication.tflite.TfLiteInterpreterGetInputTensor
import com.example.myapplication.tflite.TfLiteInterpreterGetInputTensorCount
import com.example.myapplication.tflite.TfLiteInterpreterGetOutputTensor
import com.example.myapplication.tflite.TfLiteInterpreterInvoke
import com.example.myapplication.tflite.TfLiteInterpreterOptionsCreate
import com.example.myapplication.tflite.TfLiteInterpreterOptionsDelete
import com.example.myapplication.tflite.TfLiteInterpreterOptionsSetNumThreads
import com.example.myapplication.tflite.TfLiteModelCreateFromFile
import com.example.myapplication.tflite.TfLiteModelDelete
import com.example.myapplication.tflite.TfLiteTensorByteSize
import com.example.myapplication.tflite.TfLiteTensorCopyFromBuffer
import com.example.myapplication.tflite.TfLiteTensorCopyToBuffer
import com.example.myapplication.tflite.kTfLiteOk
import game.app.generated.resources.Res
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import org.jetbrains.compose.resources.ExperimentalResourceApi
import platform.Foundation.NSFileManager
import platform.Foundation.NSURL

/**
 * iOS runs the bundled LiteRT export (`src/iosMain/composeResources/files/maia/`) through the
 * TFLite C API (TensorFlowLiteC 2.17.0, fetched by `:app:fetchLiteRtIos`). The C API memory-maps
 * the file, so nothing is copied into the Kotlin heap. Only the factory's existence check runs
 * eagerly; the model loads on the first prediction.
 */
@OptIn(ExperimentalResourceApi::class)
internal actual fun maiaRuntimeFactory(): (suspend () -> MaiaRuntime)? {
    val path = runCatching { NSURL(string = Res.getUri("files/maia/${MaiaModelFiles.TFLITE_NAME}")).path }.getOrNull()
        ?: return null
    if (!NSFileManager.defaultManager.fileExistsAtPath(path)) return null
    return { TfLiteMaiaRuntime(path) }
}

@OptIn(ExperimentalForeignApi::class)
internal class TfLiteMaiaRuntime(path: String) : MaiaRuntime {
    private val model = TfLiteModelCreateFromFile(path) ?: error("LiteRT could not load $path")
    private val options = TfLiteInterpreterOptionsCreate()
    private val interpreter = run {
        TfLiteInterpreterOptionsSetNumThreads(options, 2)
        TfLiteInterpreterCreate(model, options) ?: error("LiteRT could not create an interpreter for $path")
    }

    init {
        check(TfLiteInterpreterAllocateTensors(interpreter) == kTfLiteOk) { "LiteRT could not allocate tensors" }
        check(TfLiteInterpreterGetInputTensorCount(interpreter) == 3) { "Unexpected Maia signature" }
    }

    override suspend fun moveLogits(tokens: FloatArray, rating: Int): FloatArray {
        require(tokens.size == MaiaEncoder.SQUARES * MaiaEncoder.WIDTH) { "Bad token tensor: ${tokens.size}" }
        copyIn(0, tokens.size * Float.SIZE_BYTES) { tokens.usePinned { pinned -> it(pinned.addressOf(0)) } }
        val elo = intArrayOf(rating)
        for (input in 1..2) copyIn(input, Int.SIZE_BYTES) { elo.usePinned { pinned -> it(pinned.addressOf(0)) } }
        check(TfLiteInterpreterInvoke(interpreter) == kTfLiteOk) { "LiteRT inference failed" }
        val output = TfLiteInterpreterGetOutputTensor(interpreter, 0) ?: error("No Maia output tensor")
        val logits = FloatArray(MaiaEncoder.VOCAB)
        check(TfLiteTensorByteSize(output).toLong() == logits.size.toLong() * Float.SIZE_BYTES) { "Unexpected Maia output size" }
        logits.usePinned { pinned ->
            check(TfLiteTensorCopyToBuffer(output, pinned.addressOf(0), (logits.size * Float.SIZE_BYTES).convert()) == kTfLiteOk)
        }
        return logits
    }

    private inline fun copyIn(index: Int, bytes: Int, withAddress: (( kotlinx.cinterop.CPointer<*>) -> Unit) -> Unit) {
        val tensor = TfLiteInterpreterGetInputTensor(interpreter, index) ?: error("No Maia input $index")
        check(TfLiteTensorByteSize(tensor).toLong() == bytes.toLong()) { "Maia input $index expects ${TfLiteTensorByteSize(tensor)} bytes, got $bytes" }
        withAddress { address -> check(TfLiteTensorCopyFromBuffer(tensor, address, bytes.convert()) == kTfLiteOk) }
    }

    override fun close() {
        TfLiteInterpreterDelete(interpreter)
        TfLiteInterpreterOptionsDelete(options)
        TfLiteModelDelete(model)
    }
}
