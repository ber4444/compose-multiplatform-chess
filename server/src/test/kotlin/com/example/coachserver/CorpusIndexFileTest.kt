package com.example.coachserver

import com.example.coachapi.Passage
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The baked index is the only copy of the corpus the deployed server has, so a format that silently
 * loses or reorders a row would be undetectable at runtime — retrieval would just get quieter and
 * wronger. These are the checks that the file survives the round trip intact.
 */
class CorpusIndexFileTest {

    @Test
    fun `round trips rows, vectors and manifest`(@TempDir directory: Path) {
        val index = sampleIndex()
        val file = directory.resolve("corpus-index.bin")

        CorpusIndexFile.write(file, index)
        val decoded = CorpusIndexFile.read(file)

        assertEquals(index.manifest, decoded.manifest)
        assertEquals(index.rows, decoded.rows)
    }

    @Test
    fun `round trips the real corpus shape without an embedder`(@TempDir directory: Path) {
        // Every field the corpus actually produces — concept rows with null eco/moves, TSV rows with
        // both, text containing tabs and non-ASCII — through the real loader rather than a fixture.
        val entries = SeedMain.loadCorpus(Path.of("corpus"))
        val manifest = CorpusSeedManifest.from(entries)
        val rows = entries.map { entry ->
            IndexedPassage(
                passage = entry.passage,
                embedding = FloatArray(OpeningService.EMBEDDING_DIMENSIONS),
                eco = entry.eco?.uppercase(),
                moves = entry.moves,
            )
        }
        val file = directory.resolve("corpus-index.bin")

        CorpusIndexFile.write(file, CorpusIndex(manifest, rows))
        val decoded = CorpusIndexFile.read(file)

        assertEquals(rows.size, decoded.rows.size)
        assertEquals(rows, decoded.rows)
        assertEquals(manifest.version, decoded.manifest.version)
        assertTrue(decoded.rows.any { it.moves == null }, "Expected concept rows with no move sequence")
        assertTrue(decoded.rows.any { it.moves != null }, "Expected book rows with a move sequence")
    }

    @Test
    fun `a missing index names the task that builds it`(@TempDir directory: Path) {
        val error = assertFailsWith<IllegalArgumentException> {
            CorpusIndexFile.read(directory.resolve("absent.bin"))
        }

        assertTrue(
            error.message.orEmpty().contains("buildCorpusIndex"),
            "A missing index must say how to produce one, got: ${error.message}",
        )
    }

    @Test
    fun `a file that is not an index is rejected rather than parsed`(@TempDir directory: Path) {
        val file = directory.resolve("not-an-index.bin")
        file.toFile().writeText("this is not a corpus index, it is a text file")

        val error = assertFailsWith<IllegalStateException> { CorpusIndexFile.read(file) }

        assertTrue(error.message.orEmpty().contains("bad magic"), "got: ${error.message}")
    }

    @Test
    fun `a manifest disagreeing with the row count fails the read`(@TempDir directory: Path) {
        // The failure mode this guards is a truncated copy into the image: the rows stop early, the
        // manifest still claims the full count, and retrieval quietly serves a partial corpus.
        val index = sampleIndex()
        val file = directory.resolve("corpus-index.bin")
        CorpusIndexFile.write(file, index.copy(manifest = index.manifest.copy(expectedRowCount = 99)))

        assertFailsWith<IllegalStateException> { CorpusIndexFile.read(file) }
    }

    private fun sampleIndex(): CorpusIndex {
        val rows = listOf(
            IndexedPassage(
                passage = Passage("lichess-b-1-b20", "B20 — Sicilian Defence", "Black answers 1.e4 with c5."),
                embedding = FloatArray(OpeningService.EMBEDDING_DIMENSIONS) { it * 0.001f },
                eco = "B20",
                moves = "e4 c5",
            ),
            IndexedPassage(
                passage = Passage("concept-concepts-0", "Development", "Bring pieces out early — ünïcödé and\ttabs."),
                embedding = FloatArray(OpeningService.EMBEDDING_DIMENSIONS) { -it * 0.002f },
                eco = null,
                moves = null,
            ),
        )
        return CorpusIndex(
            manifest = CorpusSeedManifest("deadbeef", rows.size, rows.last().passage.sourceId),
            rows = rows,
        )
    }
}
