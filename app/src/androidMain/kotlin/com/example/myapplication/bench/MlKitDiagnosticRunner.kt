package com.example.myapplication.bench

import android.content.Context
import android.util.Log
import com.example.ondeviceai.renderMlKitAvailabilityDiagnostic
import com.example.ondeviceai.runMlKitAvailabilityDiagnostic
import java.io.File

private const val TAG = "MlKitDiag"

/**
 * Drives [runMlKitAvailabilityDiagnostic] from the debug-only `mlkit_diagnostic` intent extra on
 * `MainActivity`, mirroring how `bench_iterations` drives [runAndroidBench].
 *
 * The report goes to two places on purpose. Logcat is what you read while the phone is plugged in;
 * the file survives the `finish()` and can be pulled afterwards, which matters because the
 * interesting run is the *first* one on a freshly-provisioned device and it is easy to miss in a
 * scrolling buffer.
 */
suspend fun runMlKitDiagnostic(context: Context) {
    val text = try {
        renderMlKitAvailabilityDiagnostic(runMlKitAvailabilityDiagnostic())
    } catch (t: Throwable) {
        "ML Kit diagnostic failed outright: ${t::class.java.simpleName}: ${t.message}"
    }

    // One Log call per line. A single multi-line Log payload is truncated at ~4 KB by logcat, and
    // the truncation lands mid-report with no marker — you would read a short report as a complete
    // one.
    text.lineSequence().forEach { Log.i(TAG, it) }

    runCatching {
        val outputFile = File(context.filesDir, "bench/mlkit-availability.txt")
        outputFile.parentFile?.mkdirs()
        outputFile.writeText(text)
        Log.i(TAG, "report written to ${outputFile.path}")
    }.onFailure { Log.w(TAG, "could not write report file", it) }
}
