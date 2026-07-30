package com.example.myapplication.perft.mcp

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Repo-root discovery + divergence-file resolution against temp dirs (no real repo needed).
 *
 * Verifies the walk-up-to-settings-gradle logic and the divergence-file path contract:
 * `<repoRoot>/build/perft-divergence.txt`, matching what `PerftVsStockfishTest` writes and what the
 * CI nightly's `upload-artifact` step references.
 */
class PerftMcpPathsTest {

    private val tmpDirs: MutableList<Path> = mutableListOf()

    private fun makeTmpRepo(): Path {
        val dir = Files.createTempDirectory("perft-mcp-test-")
        tmpDirs.add(dir)
        // A settings.gradle.kts marker is what repoRoot() walks up to find.
        dir.resolve("settings.gradle.kts").writeText("rootProject.name = \"test\"\n")
        return dir
    }

    @AfterTest
    fun cleanup() {
        tmpDirs.forEach { runCatching { it.toFile().deleteRecursively() } }
    }

    @Test
    fun `repoRoot finds settings_gradle_kts in the start dir`() {
        val repo = makeTmpRepo()
        val root = PerftMcpPaths.repoRoot(start = repo)
        assertEquals(repo, root)
    }

    @Test
    fun `repoRoot walks up from a nested subdir`() {
        val repo = makeTmpRepo()
        val nested = repo.resolve("perft-mcp/build/install/perft-mcp/bin").createDirectories()
        val root = PerftMcpPaths.repoRoot(start = nested)
        assertEquals(repo, root, "repoRoot must walk up from a nested dir to the settings marker")
    }

    @Test
    fun `repoRoot also recognizes settings_gradle without kts`() {
        val dir = Files.createTempDirectory("perft-mcp-test-")
        tmpDirs.add(dir)
        dir.resolve("settings.gradle").writeText("rootProject.name = \"test\"\n")
        val root = PerftMcpPaths.repoRoot(start = dir)
        assertEquals(dir, root)
    }

    @Test
    fun `repoRoot throws when no settings file is found`() {
        val dir = Files.createTempDirectory("perft-mcp-test-")
        tmpDirs.add(dir)
        var threw = false
        try {
            PerftMcpPaths.repoRoot(start = dir)
        } catch (e: IllegalStateException) {
            threw = true
        }
        assertTrue(threw, "repoRoot must throw when no settings.gradle(.kts) is found walking up")
    }

    @Test
    fun `divergenceFile is repoRoot slash build slash perft-divergence_txt`() {
        val repo = makeTmpRepo()
        val divFile = PerftMcpPaths.divergenceFile(root = repo)
        assertEquals(repo.resolve("build").resolve("perft-divergence.txt"), divFile)
    }

    @Test
    fun `divergenceExists is false when the file is absent`() {
        val repo = makeTmpRepo()
        assertFalse(PerftMcpPaths.divergenceExists(root = repo))
    }

    @Test
    fun `divergenceExists is true when the file is present`() {
        val repo = makeTmpRepo()
        repo.resolve("build").createDirectories()
        PerftMcpPaths.divergenceFile(root = repo).writeText("dummy divergence")
        assertTrue(PerftMcpPaths.divergenceExists(root = repo))
    }

    @Test
    fun `DivergenceReader returns present=true and the file content when the file exists`() {
        val repo = makeTmpRepo()
        repo.resolve("build").createDirectories()
        val body = "Perft divergence detected at depth 3:\n  Root FEN: <fen>\n"
        PerftMcpPaths.divergenceFile(root = repo).writeText(body)

        val result = DivergenceReader.read(root = repo)
        assertTrue(result.present)
        assertEquals(body, result.content)
    }

    @Test
    fun `DivergenceReader returns present=false with a structured message when the file is absent`() {
        val repo = makeTmpRepo()
        val result = DivergenceReader.read(root = repo)
        assertFalse(result.present)
        assertTrue(result.content.contains("No divergence recorded"), "absent-file message must be structured, not an exception")
    }
}
