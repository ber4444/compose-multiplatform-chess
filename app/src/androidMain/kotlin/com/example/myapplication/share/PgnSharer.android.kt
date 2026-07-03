package com.example.myapplication.share

import android.app.Activity
import android.content.Intent

/**
 * Android [PgnSharer]. Uses `ACTION_SEND` with `EXTRA_TEXT = pgn` and `type = "text/plain"` — the
 * no-manifest-changes fallback the plan allows (FileProvider for a real `.pgn` attachment is a
 * future enhancement; the PGN as text is universally pasteable into lichess/etc.). Must be given the
 * host [Activity] (so `startActivity` runs on the right task), constructed at `MainActivity.onCreate`.
 */
class AndroidPgnSharer(private val activity: Activity) : PgnSharer {
    override fun share(pgn: String, suggestedFileName: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, suggestedFileName)
            putExtra(Intent.EXTRA_TEXT, pgn)
        }
        activity.startActivity(Intent.createChooser(intent, "Share PGN"))
    }
}

/** Factory mirroring `androidBoard3DSupport()` — constructed at the Android entry point. */
fun androidPgnSharer(activity: Activity): PgnSharer = AndroidPgnSharer(activity)
