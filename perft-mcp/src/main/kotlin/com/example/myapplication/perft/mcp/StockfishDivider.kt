package com.example.myapplication.perft.mcp

import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.concurrent.TimeUnit

/**
 * Result of a `stockfish_divide` tool call. When [oracleUnavailable] is true the other fields are
 * meaningless — the tool returns this structured result instead of throwing, so a missing Stockfish
 * binary is a clean degradation (per the plan's hard rules), never a hard failure for the caller.
 */
data class DivideResult(
    /** Map of root-move UCI string -> subtree leaf count. Promotion moves use the 5-char form. */
    val moves: Map<String, Long>,
    /** The `Nodes searched: <total>` line; should equal [moves] values summed. Null if missing. */
    val total: Long?,
    /** True iff no launchable `stockfish` binary was found. See class docs. */
    val oracleUnavailable: Boolean,
)

/**
 * Spawns the `stockfish` binary directly and parses its `go perft <depth>` divide output.
 *
 * This is a reimplementation of the ~30-line parser in
 * `chess-core/src/desktopTest/.../StockfishPerft.kt`. The plan explicitly prefers duplicating the
 * parser here over a testFixtures refactor of the shipped rig: smaller diff, no risk to the green
 * perft gate. Keep the two in sync if the output format ever changes (it won't — it's UCI-stable).
 *
 * Degradation contract: if no `stockfish` binary is on PATH, [divide] returns a [DivideResult] with
 * [DivideResult.oracleUnavailable] = true rather than throwing. The other tools never become hard
 * dependencies on Stockfish.
 *
 * Depth cap + timeout (see [Companion]) are enforced and stated in the tool description.
 */
class StockfishDivider(
    private val binaryPath: String = resolveBinaryPath(),
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
) : AutoCloseable {

    private var process: Process? = null
    private var writer: BufferedWriter? = null
    private var reader: BufferedReader? = null

    /**
     * One-shot divide: start process, run perft, parse, shut down.
     *
     * @param depth clamped to [MAX_DEPTH] to bound runtime (see tool description).
     * @return parsed result, or [DivideResult.oracleUnavailable] if the binary couldn't launch.
     */
    fun divide(fen: String, depth: Int): DivideResult {
        val safeDepth = depth.coerceAtMost(MAX_DEPTH)
        return try {
            startOnce()
            sendCommand("position fen $fen")
            sendCommand("go perft $safeDepth")
            val moves = LinkedHashMap<String, Long>()
            var total: Long? = null
            val deadline = System.currentTimeMillis() + timeoutMs
            while (true) {
                val remaining = deadline - System.currentTimeMillis()
                if (remaining <= 0) return DivideResult(moves, total, oracleUnavailable = false)
                val line = readLineWithTimeout(remaining) ?: break
                val trimmed = line.trim()
                when {
                    trimmed.startsWith("Nodes searched:") -> {
                        total = trimmed.removePrefix("Nodes searched:").trim().toLongOrNull()
                        break
                    }
                    trimmed.isEmpty() || trimmed.startsWith("info") || trimmed.startsWith("Stockfish") -> Unit
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
            DivideResult(moves, total, oracleUnavailable = false)
        } catch (e: Exception) {
            // A failed start (binary missing) or a wedged read both land here. Either way the tool
            // returns a structured "unavailable" result rather than propagating an exception.
            DivideResult(emptyMap(), null, oracleUnavailable = true)
        }
    }

    private fun startOnce() {
        if (process != null) return
        val proc = ProcessBuilder(binaryPath).redirectErrorStream(true).start()
        process = proc
        writer = BufferedWriter(OutputStreamWriter(proc.outputStream))
        reader = BufferedReader(InputStreamReader(proc.inputStream))
    }

    private fun sendCommand(command: String) {
        writer?.let { it.write("$command\n"); it.flush() }
    }

    private fun readLineWithTimeout(timeoutMs: Long): String? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (reader?.ready() == true) return reader!!.readLine()
            if (process?.isAlive != true) return reader?.readLine()
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
        /** Max depth the tool will request from Stockfish, to bound runtime. Stated in the tool description. */
        const val MAX_DEPTH: Int = 6
        const val DEFAULT_TIMEOUT_MS: Long = 120_000L
        private const val POLL_INTERVAL_MS: Long = 5L
        private val MOVE_LENGTH_RANGE = 4..5 // "e2e4" or "e7e8q"

        /**
         * Probes whether a `stockfish` binary is launchable and speaks UCI (sends `uci`, expects a
         * clean exit on `quit` within 3s). Used both by [divide]'s graceful-degradation path and by
         * the live test's assume-gate. Mirrors `StockfishPerft.isAvailable()` in the rig.
         */
        fun isAvailable(binaryPath: String = resolveBinaryPath()): Boolean = try {
            val proc = ProcessBuilder(binaryPath).redirectErrorStream(true).start()
            try {
                proc.outputStream.write("uci\nquit\n".toByteArray())
                proc.outputStream.flush()
                proc.waitFor(3L, TimeUnit.SECONDS)
            } finally {
                proc.destroy()
            }
        } catch (e: Exception) {
            false
        }

        /**
         * Resolves the binary path: `stockfish` on PATH by default. A caller can override via the
         * constructor (e.g. an env-var-driven test fixture) but the tool itself always uses this.
         */
        fun resolveBinaryPath(): String = System.getenv("PERFT_MCP_STOCKFISH") ?: "stockfish"
    }
}
