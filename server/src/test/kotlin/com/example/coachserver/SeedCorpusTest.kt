package com.example.coachserver

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

class SeedCorpusTest {
    @Test
    fun `checked in corpus contains all five ECO volumes and concept notes`() {
        val passages = SeedMain.loadCorpus(Path.of("corpus"))

        assertTrue(passages.size > 3_800, "Expected the complete Lichess corpus plus concept notes")
        ('a'..'e').forEach { volume ->
            assertTrue(
                passages.any { it.sourceId.startsWith("lichess-$volume-") },
                "Missing ECO volume $volume",
            )
        }
        assertTrue(passages.any { it.sourceId.startsWith("concept-") })
    }
}
