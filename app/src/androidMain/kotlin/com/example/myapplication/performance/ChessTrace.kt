package com.example.myapplication.performance

import android.os.Trace

/**
 * Low-frequency launch boundaries, recorded as atrace sections: visible in any system trace of a
 * profileable or debuggable build, and what `StartupBenchmark`'s `TraceSectionMetric`s read. No
 * game or position data is recorded.
 *
 * Plain `android.os.Trace` on purpose. `androidx.tracing:tracing-wire` was tried here and removed
 * (2026-11-01): its startup initializer pre-allocated ~4 MiB of pooled trace objects in every
 * process, profiler attached or not, and cost 5–17 ms of `bindApplication`.
 */
internal inline fun <T> chessStartupTrace(name: String, block: () -> T): T {
    Trace.beginSection(name)
    try {
        return block()
    } finally {
        Trace.endSection()
    }
}
