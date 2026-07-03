package com.example.myapplication.persistence

/** Wasm/JS actual for [nowEpochMillis]/[todayPgnDate] backed by the browser `Date`. */

@JsFun("() => Date.now()")
private external fun jsNow(): Double

actual fun nowEpochMillis(): Long = jsNow().toLong()

@JsFun("() => { const d = new Date(); const y = d.getUTCFullYear(); const m = String(d.getUTCMonth() + 1).padStart(2, '0'); const day = String(d.getUTCDate()).padStart(2, '0'); return y + '.' + m + '.' + day; }")
private external fun jsTodayPgnDate(): String

actual fun todayPgnDate(): String = jsTodayPgnDate()
