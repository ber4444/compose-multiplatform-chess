package com.example.myapplication.perft.mcp

import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.isReadable

/**
 * Repo-root discovery + divergence-file path resolution for the perft MCP tools.
 *
 * These helpers deliberately mirror [com.example.myapplication.perft.projectRoot] in
 * `chess-core/src/desktopTest/.../PerftVsStockfishTest.kt` (which walks up to
 * `settings.gradle.kts`), so this adapter and the rig agree on where `build/` lives without the
 * adapter depending on the test sources. Duplicating ~15 lines of path logic here beats a
 * testFixtures refactor of the shipped rig — smaller diff, zero risk to the green perft gate.
 */
object PerftMcpPaths {

    /**
     * The repository root: the nearest enclosing directory containing `settings.gradle.kts`.
     * Walks up from [start] (default: the process's working directory) so the server works no matter
     * where the MCP host spawns it. Throws if no root is found — that's a genuine misconfiguration,
     * not a degradation case to swallow.
     */
    fun repoRoot(start: Path = Paths.get(System.getProperty("user.dir"))): Path {
        var dir = start.toAbsolutePath().normalize()
        while (dir != null && dir.isDirectory()) {
            if (dir.resolve("settings.gradle.kts").exists()) return dir
            if (dir.resolve("settings.gradle").exists()) return dir
            dir = dir.parent
        }
        throw IllegalStateException(
            "No settings.gradle.kts found walking up from $start — perft-mcp must run inside the repo."
        )
    }

    /**
     * `<repo-root>/build/perft-divergence.txt` — the file [PerftVsStockfishTest] writes on failure.
     * Same root-relative path the CI nightly's `upload-artifact` step references.
     */
    fun divergenceFile(root: Path = repoRoot()): Path = root.resolve("build").resolve("perft-divergence.txt")

    /** True iff the divergence file exists and is readable. */
    fun divergenceExists(root: Path = repoRoot()): Boolean = divergenceFile(root).let {
        it.exists() && it.isReadable()
    }
}
