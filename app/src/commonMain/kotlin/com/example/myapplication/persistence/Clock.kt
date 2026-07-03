package com.example.myapplication.persistence

/**
 * Platform clock for the persistence/history layer. `commonMain` has no `kotlinx-datetime` dep (the
 * plan deliberately avoids adding one), so epoch millis and the PGN `YYYY.MM.DD` date string are
 * sourced from each platform's native clock via this tiny `expect/actual`.
 *
 *  - [nowEpochMillis]: monotonic-ish wall time; used for `SavedGame.savedAtEpochMillis` and the
 *    autosave timestamp. Sufficient for ordering/newest-first; not a monotonic clock.
 *  - [todayPgnDate]: today's date in `YYYY.MM.DD` (PGN spec §10), UTC. `?`/partial dates are not used.
 */
expect fun nowEpochMillis(): Long

/** Today's date in PGN `YYYY.MM.DD` form (UTC). */
expect fun todayPgnDate(): String
