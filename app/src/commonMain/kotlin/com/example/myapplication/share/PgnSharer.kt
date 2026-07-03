package com.example.myapplication.share

/**
 * Platform share/export for a PGN string (Phase 3). Mirrors the [com.example.myapplication.board3d.Board3DSupport]
 * / [com.example.myapplication.ChessEngine] injection pattern: each target's entry point constructs
 * a platform-specific implementation and passes it into `AppRoot`; targets that defer it pass `null`,
 * and the Share button is hidden when `pgnSharer == null` (just like the 3D toggle).
 *
 * The contract is fire-and-forget: implementors open the platform share sheet / file dialog /
 * download and return immediately. [suggestedFileName] is a hint (no extension enforced).
 *
 * Declared `fun interface` so tests can use SAM conversion (`PgnSharer { _, _ -> }`).
 */
fun interface PgnSharer {
    fun share(pgn: String, suggestedFileName: String)
}
