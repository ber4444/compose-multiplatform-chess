package com.example.myapplication.performance

import android.os.Trace
import androidx.tracing.Tracer

/**
 * Low-frequency launch boundaries visible in both CLI system traces and AndroidX in-process
 * traces. tracing-wire supplies the global tracer; its default initializer enables recording
 * only for a connected profiling session. No game/position data is recorded.
 */
internal inline fun <T> chessStartupTrace(name: String, crossinline block: () -> T): T =
    Tracer.global.trace(
        category = "com.example.myapplication.startup",
        name = name,
        metadataBlock = { addMetadataEntry("sourceFile", "MainActivity.kt") },
    ) {
        Trace.beginSection(name)
        try {
            block()
        } finally {
            Trace.endSection()
        }
    }
