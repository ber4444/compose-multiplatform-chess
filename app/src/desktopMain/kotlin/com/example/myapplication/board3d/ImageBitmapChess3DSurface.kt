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

/**
 * Wraps raw `width*height*4` RGBA8888 bytes (the layout the wgpu4k renderer reads back from its
 * `RGBA8Unorm` color target) into a Compose [ImageBitmap] via Skia, with no channel swizzling.
 */
internal fun rgbaBytesToImageBitmap(rgba: ByteArray, w: Int, h: Int, bytesPerRow: Int = w * 4): ImageBitmap {
    val bitmap = Bitmap()
    bitmap.allocPixels(ImageInfo(w, h, ColorType.RGBA_8888, ColorAlphaType.UNPREMUL))
    if (bytesPerRow == w * 4) {
        bitmap.installPixels(rgba)
    } else {
        val packed = ByteArray(w * h * 4)
        for (y in 0 until h) {
            rgba.copyInto(
                destination = packed,
                destinationOffset = y * w * 4,
                startIndex = y * bytesPerRow,
                endIndex = y * bytesPerRow + w * 4
            )
        }
        bitmap.installPixels(packed)
    }
    return bitmap.asComposeImageBitmap()
}
