package com.example.myapplication

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DesktopStockfishEngineTest {

    @Test
    fun returnsFirstExecutableCandidate() {
        val candidates = listOf("first", "second", "third")
        val path = resolveStockfishPath(candidates) { it == "second" }
        assertEquals("second", path)
    }

    @Test
    fun prefersEarlierCandidateWhenSeveralExist() {
        val candidates = listOf("first", "second", "third")
        val path = resolveStockfishPath(candidates) { it == "first" || it == "third" }
        assertEquals("first", path)
    }

    @Test
    fun fallsBackToPathLookupWhenNoCandidateMatches() {
        val candidates = listOf("first", "second")
        val path = resolveStockfishPath(candidates) { false }
        assertEquals("stockfish", path)
    }

    @Test
    fun defaultPredicateRejectsNonExecutableAndMissingFiles() {
        val tempDir = File.createTempFile("test", "dir").apply {
            delete()
            mkdir()
        }
        val execFile = File(tempDir, "exec").apply {
            createNewFile()
            setExecutable(true)
        }
        val nonExecFile = File(tempDir, "nonexec").apply {
            createNewFile()
            setExecutable(false)
        }
        val missingFile = File(tempDir, "missing")

        try {
            if (!execFile.canExecute()) return // guard for exotic filesystems

            val candidates = listOf(missingFile.absolutePath, nonExecFile.absolutePath, execFile.absolutePath)
            val path = resolveStockfishPath(candidates)
            assertEquals(execFile.absolutePath, path)
        } finally {
            execFile.delete()
            nonExecFile.delete()
            tempDir.delete()
        }
    }

    @Test
    fun defaultCandidateListIsOrderedHomebrewFirst() {
        assertTrue(STOCKFISH_CANDIDATE_PATHS[0].contains("homebrew"))
        assertTrue(STOCKFISH_CANDIDATE_PATHS[1].contains("local"))
        assertTrue(STOCKFISH_CANDIDATE_PATHS[2].contains("games"))
        assertTrue(STOCKFISH_CANDIDATE_PATHS[3].contains("bin/stockfish"))
    }
}
