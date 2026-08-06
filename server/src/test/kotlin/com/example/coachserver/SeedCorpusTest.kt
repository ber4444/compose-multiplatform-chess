package com.example.coachserver

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class SeedCorpusTest {
    @Test
    fun `seed manifest changes when a corpus record changes`() {
        val original = listOf(
            SeedMain.CorpusEntry(com.example.coachapi.Passage("a", "A", "First insight."), eco = "A00", moves = "a3"),
            SeedMain.CorpusEntry(com.example.coachapi.Passage("b", "B", "Second insight."), eco = "B00", moves = "e4"),
        )
        val changed = original.dropLast(1) +
            SeedMain.CorpusEntry(com.example.coachapi.Passage("b", "B", "Changed insight."), eco = "B00", moves = "e4")

        val manifest = CorpusSeedManifest.from(original)

        assertEquals(2, manifest.expectedRowCount)
        assertEquals("b", manifest.finalSourceId)
        assertEquals(64, manifest.version.length)
        assertNotEquals(manifest.version, CorpusSeedManifest.from(changed).version)
    }

    @Test
    fun `checked in corpus contains all five ECO volumes and concept notes`() {
        val entries = SeedMain.loadCorpus(Path.of("corpus"))

        assertTrue(entries.size > 3_800, "Expected the complete Lichess corpus plus concept notes")
        ('a'..'e').forEach { volume ->
            assertTrue(
                entries.any { it.passage.sourceId.startsWith("lichess-$volume-") },
                "Missing ECO volume $volume",
            )
        }
        assertTrue(entries.any { it.passage.sourceId.startsWith("concept-") })
    }
}
