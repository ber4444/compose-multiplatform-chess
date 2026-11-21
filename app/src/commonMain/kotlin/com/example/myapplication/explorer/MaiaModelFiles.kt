package com.example.myapplication.explorer

/**
 * The two bundled exports of the same Maia-3 5M checkpoint, pinned by size and SHA-256. Provenance,
 * exact tool versions and the reproducible export steps are in `docs/maia3-model-provenance.md`;
 * the platform tests fail if a bundled file drifts from these values.
 *
 * - **LiteRT** (`.tflite`) on Android (`org.tensorflow.lite.Interpreter`), iOS (the TFLite C API) and
 *   web (LiteRT.js, downloaded from Hugging Face and checked against [TFLITE_SHA256]). Its Elo inputs
 *   are int32.
 * - **ONNX** on desktop (ONNX Runtime), the one platform LiteRT publishes no runtime for.
 *
 * Both files are AGPL-3.0 (CSSLab's Maia-3), distributed with this GPL-3.0-or-later app as GPLv3
 * §13 permits. See COPYING.md.
 */
internal object MaiaModelFiles {
    const val TFLITE_NAME = "maia3-5m.tflite"
    const val TFLITE_BYTES = 20_813_796L
    const val TFLITE_SHA256 = "f0acad2d11db917c10ecc4418a5ef5600c00e21dcac1f54b77008acfeeed998e"

    const val ONNX_NAME = "maia3-5m.onnx"
    const val ONNX_BYTES = 21_346_659L
    const val ONNX_SHA256 = "3d0b22170a965944379499737aac1f7375b93b9bca83446f91e9314443b78ab6"
}
