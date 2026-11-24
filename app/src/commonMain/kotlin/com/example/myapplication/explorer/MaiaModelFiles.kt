package com.example.myapplication.explorer

/**
 * The exports of the same Maia-3 5M checkpoint that the app runs, pinned by size and SHA-256.
 * Provenance, exact tool versions and the reproducible export steps are in
 * `docs/maia3-model-provenance.md`; the platform tests fail if a bundled file drifts from these values.
 *
 * - **LiteRT int8** (`.tflite`, [TFLITE_NAME]) bundled on Android (`org.tensorflow.lite.Interpreter`)
 *   and iOS (the TFLite C API). Weight-only int8 quantization of the float LiteRT export
 *   (`tools/maia3/quantize_litert.py`): 5.9 MB instead of 20.8 MB, still computed in float32, and
 *   within `BundledMaiaParity.INT8_SHARE_TOLERANCE` of PyTorch. Its Elo inputs are int32.
 * - **The same int8 file on web** ([WEB_TFLITE_NAME]), downloaded by LiteRT.js from Hugging Face at
 *   `MODEL_REVISION` (`MaiaRuntime.wasm.kt`) and checked against [WEB_TFLITE_SHA256]. These are
 *   separate constants because web runs whatever that upload holds: re-quantizing the bundled file
 *   changes them only once the new file is uploaded and `MODEL_REVISION` moves to it.
 * - **ONNX** on desktop (ONNX Runtime), the one platform LiteRT publishes no runtime for.
 *
 * All are AGPL-3.0 (CSSLab's Maia-3), distributed with this GPL-3.0-or-later app as GPLv3 §13
 * permits. See COPYING.md.
 */
internal object MaiaModelFiles {
    const val TFLITE_NAME = "maia3-5m-int8.tflite"
    const val TFLITE_BYTES = 5_889_056L
    const val TFLITE_SHA256 = "bb8c8247a41fb6eccbbf7b717f496d9d02e723a43c1fe484c0b0f16a63f9f122"

    const val WEB_TFLITE_NAME = "maia3-5m-int8.tflite"
    const val WEB_TFLITE_SHA256 = "bb8c8247a41fb6eccbbf7b717f496d9d02e723a43c1fe484c0b0f16a63f9f122"

    const val ONNX_NAME = "maia3-5m.onnx"
    const val ONNX_BYTES = 21_346_659L
    const val ONNX_SHA256 = "3d0b22170a965944379499737aac1f7375b93b9bca83446f91e9314443b78ab6"
}
