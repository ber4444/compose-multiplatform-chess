package com.example.myapplication.explorer

/**
 * The exports of the same Maia-3 5M checkpoint that the app runs, pinned by size and SHA-256.
 * Provenance, exact tool versions and the reproducible export steps are in
 * `docs/maia3-model-provenance.md`; the platform tests fail if a bundled file drifts from these values.
 *
 * - **LiteRT int8** (`.tflite`, [TFLITE_NAME]) bundled on Android (`org.tensorflow.lite.Interpreter`)
 *   and iOS (the TFLite C API). Weight-only int8 quantization of the float export
 *   (`tools/maia3/quantize_litert.py`): 5.9 MB instead of 20.8 MB, still computed in float32, and
 *   within `BundledMaiaParity.INT8_SHARE_TOLERANCE` of PyTorch. Its Elo inputs are int32.
 * - **LiteRT float** ([WEB_TFLITE_NAME]) downloaded by web (LiteRT.js) from Hugging Face and checked
 *   against [WEB_TFLITE_SHA256]. It is the file the int8 one is quantized from. Web moves to the int8
 *   file once that is uploaded to Hugging Face, with a new `MODEL_REVISION` in `MaiaRuntime.wasm.kt`.
 * - **ONNX** on desktop (ONNX Runtime), the one platform LiteRT publishes no runtime for.
 *
 * All are AGPL-3.0 (CSSLab's Maia-3), distributed with this GPL-3.0-or-later app as GPLv3 §13
 * permits. See COPYING.md.
 */
internal object MaiaModelFiles {
    const val TFLITE_NAME = "maia3-5m-int8.tflite"
    const val TFLITE_BYTES = 5_890_400L
    const val TFLITE_SHA256 = "b07703def6f7bdd1c3253884b52d6eeb2a8a4328e84dd08f64f7e9ba0311ec71"

    const val WEB_TFLITE_NAME = "maia3-5m.tflite"
    const val WEB_TFLITE_SHA256 = "f0acad2d11db917c10ecc4418a5ef5600c00e21dcac1f54b77008acfeeed998e"

    const val ONNX_NAME = "maia3-5m.onnx"
    const val ONNX_BYTES = 21_346_659L
    const val ONNX_SHA256 = "3d0b22170a965944379499737aac1f7375b93b9bca83446f91e9314443b78ab6"
}
