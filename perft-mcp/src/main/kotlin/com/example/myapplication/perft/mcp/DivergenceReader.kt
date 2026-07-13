package com.example.myapplication.perft.mcp

import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.isReadable
import kotlin.io.path.pathString
import kotlin.io.path.readText

/**
 * Result of a `read_divergence` tool call.
 *
 * @property present true iff the divergence file exists and was read.
 * @property content the file's text (the full divergence trail), or a structured message when absent.
 */
data class DivergenceReadResult(
    val present: Boolean,
    val content: String,
)

/**
 * Reads `build/perft-divergence.txt` (written by [PerftVsStockfishTest] on failure), or returns a
 * structured "no divergence recorded" result when the file is absent — e.g. when the gate is green,
 * or when the canonical gate failed before the Stockfish localizer got to run.
 *
 * Never throws: a missing/unreadable file is a normal state, not an error.
 */
object DivergenceReader {
    fun read(root: Path = PerftMcpPaths.repoRoot()): DivergenceReadResult {
        val file = PerftMcpPaths.divergenceFile(root)
        return when {
            file.exists() && file.isReadable() -> DivergenceReadResult(true, file.readText())
            else -> DivergenceReadResult(
                present = false,
                content = "No divergence recorded at ${file.pathString}. " +
                    "This means either the perft gate is green, or the canonical gate failed before " +
                    "the Stockfish localizer (PerftVsStockfishTest) could run. Run run_perft_gate " +
                    "first; if it fails without producing this file, fix the canonical-gate failure " +
                    "before the localizer will write its trail.",
            )
        }
    }
}
