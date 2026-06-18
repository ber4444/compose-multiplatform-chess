package com.example.myapplication.perft

import com.example.myapplication.DesktopStockfishEngine
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.concurrent.TimeUnit

/**
 * Oracle 2 helper — talks directly to a `stockfish` binary over UCI and parses its
 * `go perft <n>` divide output into a `Map<uciMove, count>`.
 *
 * This is test-only code: rather than widening the protected `sendCommand` surface of
 * [BaseStockfishEngine] (which the `DO NOT TOUCH` fence in CLAUDE.md keeps frozen), it spawns
 * its own short-lived process per call with [ProcessBuilder]. The desktop engine's path
 * resolution ([DesktopStockfishEngine.resolveExecutablePath] via [resolveStockfishPath]) is
 * reused so we probe the same Homebrew/system locations.
 *
 * Output parsing: [StockfishPerft] emits info chatter, blank lines, `<move>: <count>` lines,
 * and a final `Nodes searched: <total>`. Only the `<move>: <count>` lines enter the map; the
 * total is kept separately as a free cross-check against our own perft sum. Promotion moves
 * use the 5-char form (`e7e8q`), matching our [perftDivide] keys exactly.
 */
class StockfishPerft(
    private val binaryPath: String = StockfishPerft.defaultBinaryPath(),
) : AutoCloseable {

    private var process: Process? = null
    private var writer: BufferedWriter? = null
    private var reader: BufferedReader? = null

    /** One-shot divide: start process, run perft, parse, shut down. */
    fun divide(fen: String, depth: Int): StockfishPerftResult {
        startOnce()
        sendCommand("position fen $fen")
        sendCommand("go perft $depth")
        val moves = LinkedHashMap<String, Long>()
        var total: Long? = null
        val deadline = System.currentTimeMillis() + PERFT_TIMEOUT_MS
        while (true) {
            val remaining = deadline - System.currentTimeMillis()
            if (remaining <= 0) {
                throw IllegalStateException("Stockfish perft timed out after ${PERFT_TIMEOUT_MS}ms (fen=$fen, depth=$depth)")
            }
            val line = readLineWithTimeout(remaining) ?: break
            val trimmed = line.trim()
            when {
                trimmed.startsWith("Nodes searched:") -> {
                    total = trimmed.removePrefix("Nodes searched:").trim().toLongOrNull()
                    break  // Summary is the final line of a `go perft` response.
                }
                trimmed.isEmpty() || trimmed.startsWith("info") || trimmed.startsWith("Stockfish") -> {
                    // Skip chatter.
                }
                else -> {
                    val colon = trimmed.lastIndexOf(':')
                    if (colon > 0) {
                        val move = trimmed.substring(0, colon).trim()
                        val count = trimmed.substring(colon + 1).trim().toLongOrNull()
                        if (move.length in MOVE_LENGTH_RANGE && count != null) {
                            moves[move] = count
                        }
                    }
                }
            }
        }
        return StockfishPerftResult(moves, total)
    }

    private fun startOnce() {
        if (process != null) return
        val proc = ProcessBuilder(binaryPath)
            .redirectErrorStream(true)
            .start()
        process = proc
        writer = BufferedWriter(OutputStreamWriter(proc.outputStream))
        reader = BufferedReader(InputStreamReader(proc.inputStream))
    }

    private fun sendCommand(command: String) {
        writer?.let {
            it.write("$command\n")
            it.flush()
        }
    }

    /** Polls the process's stdout with a deadline so a wedged Stockfish can't hang the test. */
    private fun readLineWithTimeout(timeoutMs: Long): String? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (reader?.ready() == true) return reader!!.readLine()
            if (process?.isAlive != true) return reader?.readLine()  // drain final line then EOF
            Thread.sleep(POLL_INTERVAL_MS)
        }
        return null
    }

    override fun close() {
        runCatching { sendCommand("quit") }
        runCatching { writer?.close() }
        runCatching { reader?.close() }
        process?.destroy()
        process = null
        writer = null
        reader = null
    }

    companion object {
        private const val PERFT_TIMEOUT_MS = 30_000L
        private const val POLL_INTERVAL_MS = 5L
        private val MOVE_LENGTH_RANGE = 4..5  // "e2e4" or "e7e8q"

        /**
         * True if a Stockfish binary can be launched and respond on this host. Used by tests to
         * assume-gate. A healthy `stockfish` reads `uci`, prints at least one line, and exits on
         * `quit` — we probe that full cycle rather than just `ProcessBuilder.start()` (which would
         * pass even if the binary then fails to initialize its NNUE networks).
         */
        fun isAvailable(): Boolean = try {
            val proc = ProcessBuilder(defaultBinaryPath())
                .redirectErrorStream(true)
                .start()
            try {
                proc.outputStream.write("uci\nquit\n".toByteArray())
                proc.outputStream.flush()
                // If the process exits cleanly within the deadline, it understood `quit` — that's
                // a real UCI engine. A missing binary would have thrown at `start()`; a hung or
                // non-UCI process won't exit in time.
                proc.waitFor(3L, TimeUnit.SECONDS)
            } finally {
                proc.destroy()
            }
        } catch (e: Exception) {
            false
        }

        fun defaultBinaryPath(): String =
            // DesktopStockfishEngine is the production resolver; resolveExecutablePath() is
            // `protected`, but the public top-level helper it delegates to does the same work.
            runCatching {
                val clazz = Class.forName("com.example.myapplication.DesktopStockfishEngine")
                val instance = clazz.getDeclaredConstructor().newInstance()
                val method = clazz.getDeclaredMethod("resolveExecutablePath")
                method.isAccessible = true
                method.invoke(instance) as? String
            }.getOrNull() ?: "stockfish"
    }
}

/** Result of a single Stockfish `go perft` call. */
data class StockfishPerftResult(
    /** Map of root-move UCI string -> subtree leaf count. Promotion moves use the 5-char form. */
    val divide: Map<String, Long>,
    /** The `Nodes searched: <total>` line; should equal [divide] values summed. Null if missing. */
    val totalSearched: Long?,
)
