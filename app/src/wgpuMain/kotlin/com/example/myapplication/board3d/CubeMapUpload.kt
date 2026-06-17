package com.example.myapplication.board3d

/**
 * The generated KTX cube faces are stored with image rows in the opposite vertical origin from
 * WebGPU cube sampling. Flip each face on upload so the visible skybox and IBL agree with the
 * real-world orientation of the papermill environment.
 */
internal fun flipRgba16FloatRows(bytes: ByteArray, width: Int, height: Int): ByteArray {
    val rowBytes = width * 8 // RGBA16F: 4 channels * 2 bytes
    if (height <= 1 || rowBytes <= 0) return bytes

    val flipped = ByteArray(bytes.size)
    for (y in 0 until height) {
        val src = y * rowBytes
        val dst = (height - 1 - y) * rowBytes
        bytes.copyInto(flipped, destinationOffset = dst, startIndex = src, endIndex = src + rowBytes)
    }
    return flipped
}
