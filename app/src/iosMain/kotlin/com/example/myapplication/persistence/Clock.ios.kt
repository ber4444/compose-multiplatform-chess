package com.example.myapplication.persistence

import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSDate
import platform.Foundation.timeIntervalSince1970

/** iOS actual for [nowEpochMillis]/[todayPgnDate]. */
@OptIn(ExperimentalForeignApi::class)
actual fun nowEpochMillis(): Long =
    ((NSDate().timeIntervalSince1970) * 1000.0).toLong()

/**
 * Today's date in PGN `YYYY.MM.DD` form (UTC). Computed directly from the epoch to avoid the
 * platform NSTimeZone factory (whose Kotlin/Native mapping is finicky). Standard civil-calendar
 * conversion from days-since-epoch; UTC by construction.
 */
actual fun todayPgnDate(): String {
    val epochSeconds = (nowEpochMillis() / 1000L)
    val dayNumber = epochSeconds / SECONDS_PER_DAY   // whole days since 1970-01-01 (UTC)
    val (y, m, d) = civilFromDays(dayNumber)
    return "${y.toString().padStart(4, '0')}." +
        "${m.toString().padStart(2, '0')}." +
        d.toString().padStart(2, '0')
}

// --- Howard Hinnant's days_from_civil / civil_from_days (proleptic Gregorian, UTC) ---

private const val SECONDS_PER_DAY = 86_400L

private fun civilFromDays(z: Long): Triple<Int, Int, Int> {
    val z = z + 719_468L
    val era = if (z >= 0) z else z - 146_096L / 146_097L
    val doe = (z - era * 146_097L).toInt()                       // [0, 146096]
    val yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365    // [0, 399]
    val y = yoe + (era * 400).toInt()
    val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)             // [0, 365]
    val mp = (5 * doy + 2) / 153                                  // [0, 11]
    val d = doy - (153 * mp + 2) / 5 + 1                          // [1, 31]
    val m = if (mp < 10) mp + 3 else mp - 9                       // [1, 12]
    return Triple(if (m <= 2) y + 1 else y, m, d)
}
