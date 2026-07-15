package com.example.myapplication.perft.mcp

import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Result of a `run_perft_gate` tool call.
 *
 * @property passed true iff the gradle invocation exited 0.
 * @property summary trimmed tail of the gradle output (last ~50 lines), not the full log — enough
 *   for an agent to see which test failed without a multi-MB blob.
 * @property divergenceFileExists true iff `build/perft-divergence.txt` was produced (only happens on
 *   a PerftVsStockfishTest failure; a canonical-gate-only failure won't write it).
 */
data class GateResult(
    val passed: Boolean,
    val summary: String,
    val divergenceFileExists: Boolean,
)

/**
 * Runs the perft gate by shelling out to gradle, exactly as a human following
 * `docs/plans/perft-loop-brief.md` would. No dependency on `:app` or `:chess-core` — the adapter
 * invokes gradle as a process.
 *
 * Target: `:chess-core:desktopTest` (NOT `:app:desktopTest` — the perft tests moved to :chess-core,
 * and a `--tests "*Perft*"` filter on :app:desktopTest matches zero tests post-move; see
 * docs/plans/perft-ci-completion.md).
 */
object PerftGateRunner {

    /** Gradle command tail for the perft gate. */
    private const val GATE_TASK = ":chess-core:desktopTest"
    private const val GATE_TEST_PATTERN = "*Perft*"
    private const val DEEP_FLAG = "-Dperft.deep=true"
    private const val SUMMARY_TAIL_LINES = 50
    // Bound the subprocess so a wedged gradle can't hang the MCP tool call (and thus the agent)
    // indefinitely. The deep tier legitimately runs for minutes, so it gets a wider ceiling.
    private const val DEFAULT_TIMEOUT_MINUTES = 15L
    private const val DEEP_TIMEOUT_MINUTES = 45L
    private const val DRAIN_JOIN_MS = 2_000L

    /**
     * @param deep when true, appends [DEEP_FLAG] so [PerftDeepTest] runs (nightly-tier depths; slow).
     */
    fun run(root: Path, deep: Boolean = false): GateResult {
        val gradlew = root.resolve(gradleWrapper()).toString()
        // ProcessBuilder passes each arg as a distinct argv entry — NO shell quoting needed (and
        // literal quotes would be passed through and break gradle's --tests filter).
        val command = mutableListOf(gradlew, GATE_TASK, "--tests", GATE_TEST_PATTERN)
        if (deep) command += DEEP_FLAG

        val builder = ProcessBuilder(command).directory(root.toFile()).redirectErrorStream(true)
        val proc = builder.start()

        // Drain stdout on a daemon thread: this both keeps a chatty gradle from filling the pipe
        // buffer and wedging, and lets us bound the whole run with waitFor(timeout) instead of
        // blocking forever inside readText() when the build hangs.
        val output = AtomicReference("")
        val drainer = Thread { output.set(proc.inputStream.bufferedReader().readText()) }
            .apply { isDaemon = true; name = "perft-gate-stdout"; start() }

        val timeoutMinutes = if (deep) DEEP_TIMEOUT_MINUTES else DEFAULT_TIMEOUT_MINUTES
        if (!proc.waitFor(timeoutMinutes, TimeUnit.MINUTES)) {
            proc.destroyForcibly()
            drainer.join(DRAIN_JOIN_MS)
            return GateResult(
                passed = false,
                summary = "perft gate TIMED OUT after ${timeoutMinutes}m and was killed.\n" + tailOf(output.get()),
                divergenceFileExists = PerftMcpPaths.divergenceExists(root),
            )
        }
        drainer.join(DRAIN_JOIN_MS)
        return GateResult(
            passed = proc.exitValue() == 0,
            summary = tailOf(output.get()),
            divergenceFileExists = PerftMcpPaths.divergenceExists(root),
        )
    }

    /** Last [SUMMARY_TAIL_LINES] lines of [output], trimmed — enough to see which test failed. */
    private fun tailOf(output: String): String =
        output.lineSequence().toList().let { lines ->
            lines.takeLast(minOf(SUMMARY_TAIL_LINES, lines.size)).joinToString("\n").trim()
        }

    /** `./gradlew` on unix, `gradlew.bat` on windows. The MCP host's platform decides. */
    private fun gradleWrapper(): String =
        if (System.getProperty("os.name").startsWith("Windows")) "gradlew.bat" else "gradlew"
}
