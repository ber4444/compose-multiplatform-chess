package com.example.myapplication.board3d

import android.graphics.Bitmap
import android.view.PixelCopy
import android.os.Handler
import android.os.Looper
import android.view.SurfaceView
import java.io.File
import java.io.FileOutputStream
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * Phase A.2 Android baseline capture — copies a laid-out [SurfaceView]'s GPU-rendered pixels into
 * a PNG file.
 *
 * `PixelCopy` is the only Android API that can read a SurfaceView's rendered content back to the
 * CPU (View.draw / drawing-cache returns an empty black rectangle for SurfaceView, whose buffer
 * lives in a separate compositor layer). The SurfaceView must be laid out and the Filament
 * renderer must have produced at least one frame before this is called.
 *
 * Requires API 24+ (PixelCopy was added in API 24) — matches the module's minSdk.
 */
suspend fun SurfaceView.captureToPngFile(out: File) {
    val w = width.coerceAtLeast(1)
    val h = height.coerceAtLeast(1)
    val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val result = suspendCoroutine<Int> { cont ->
        // PixelCopy must be called from the main thread; the callback fires on the supplied Handler.
        val handler = Handler(Looper.getMainLooper())
        PixelCopy.request(this, bitmap, { copyResult -> cont.resume(copyResult) }, handler)
    }
    if (result != PixelCopy.SUCCESS) {
        bitmap.recycle()
        error("PixelCopy failed with status $result on $this (${width}x${height})")
    }
    out.parentFile?.mkdirs()
    FileOutputStream(out).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    bitmap.recycle()
}
