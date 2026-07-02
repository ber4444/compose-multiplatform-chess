package com.example.myapplication.persistence

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Shared JVM actual (covers Android + Desktop) for [nowEpochMillis]/[todayPgnDate]. */
actual fun nowEpochMillis(): Long = System.currentTimeMillis()

actual fun todayPgnDate(): String {
    // PGN spec §10: "YYYY.MM.DD". UTC so a game finished near midnight doesn't flip the date by TZ.
    val fmt = SimpleDateFormat("yyyy.MM.dd", Locale.US)
    fmt.timeZone = TimeZone.getTimeZone("UTC")
    return fmt.format(Date())
}
