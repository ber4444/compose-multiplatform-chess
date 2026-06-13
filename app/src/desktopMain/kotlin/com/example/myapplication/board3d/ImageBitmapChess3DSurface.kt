package com.example.myapplication.board3d

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo

class ImageBitmapChess3DSurface(
    override val widthPx: Int,
    override val heightPx: Int,
    val onFrame: (ImageBitmap) -> Unit
) : Chess3DSurface

internal fun IntArray.toImageBitmap(w: Int, h: Int): ImageBitmap {
    val bitmap = Bitmap()
    bitmap.allocPixels(ImageInfo.makeN32Premul(w, h))
    val bytes = ByteArray(this.size * 4)
    for (i in this.indices) {
        val x = i % w
        val y = i / w
        val isDark = ((x / (w / 8.0)).toInt() + (y / (h / 8.0)).toInt()) % 2 == 0
        val color = if (isDark) 0xFF444444.toInt() else 0xFF888888.toInt()
        
        bytes[i * 4] = (color and 0xFF).toByte()
        bytes[i * 4 + 1] = ((color shr 8) and 0xFF).toByte()
        bytes[i * 4 + 2] = ((color shr 16) and 0xFF).toByte()
        bytes[i * 4 + 3] = (0xFF).toByte()
    }
    bitmap.installPixels(bytes)
    return bitmap.asComposeImageBitmap()
}
