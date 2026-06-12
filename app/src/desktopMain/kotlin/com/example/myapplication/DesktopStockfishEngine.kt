package com.example.myapplication

import java.io.File

/** Common absolute install locations, probed in order before falling back to a PATH lookup. */
internal val STOCKFISH_CANDIDATE_PATHS = listOf(
    "/opt/homebrew/bin/stockfish", // macOS Apple Silicon (Homebrew)
    "/usr/local/bin/stockfish",    // macOS Intel (Homebrew)
    "/usr/games/stockfish",        // Debian/Ubuntu
    "/usr/bin/stockfish",          // Fedora/Arch
)
internal const val STOCKFISH_PATH_FALLBACK = "stockfish"

internal fun resolveStockfishPath(
    candidates: List<String> = STOCKFISH_CANDIDATE_PATHS,
    isExecutableFile: (String) -> Boolean = { p -> File(p).let { it.isFile && it.canExecute() } },
): String = candidates.firstOrNull(isExecutableFile) ?: STOCKFISH_PATH_FALLBACK

class DesktopStockfishEngine : BaseStockfishEngine() {
    override fun resolveExecutablePath(): String = resolveStockfishPath()
}
