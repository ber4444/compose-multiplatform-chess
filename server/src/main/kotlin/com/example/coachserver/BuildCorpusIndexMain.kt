package com.example.coachserver

import java.nio.file.Path
import kotlin.io.path.fileSize

/**
 * Embeds the checked-in corpus and writes the baked retrieval index consumed at boot.
 *
 * Runs once per image build (see `server/Dockerfile`), never at runtime — this is the step that
 * lets the deployed app hold its whole corpus in memory and therefore need no database machine.
 * It is the build-time twin of [SeedMain], which does the same chunking and embedding against
 * Postgres for the reference implementation and its tests.
 *
 * Both derive their rows from [SeedMain.loadCorpus] and their identity from [CorpusSeedManifest],
 * so an index and a seeded database built from the same corpus carry the same `seedVersion`.
 */
object BuildCorpusIndexMain {
    @JvmStatic
    fun main(args: Array<String>) {
        val environment = System.getenv()
        val corpusDirectory = Path.of(environment["COACH_CORPUS_DIR"] ?: "corpus")
        val output = Path.of(environment["COACH_CORPUS_INDEX"] ?: DEFAULT_INDEX_PATH)
        val entries = SeedMain.loadCorpus(corpusDirectory)
        val manifest = CorpusSeedManifest.from(entries)

        val rows = OnnxMiniLmEmbedder(
            Path.of(requireEnvironment(environment, "COACH_EMBEDDING_MODEL")),
            Path.of(requireEnvironment(environment, "COACH_EMBEDDING_VOCAB")),
        ).use { embedder ->
            entries.mapIndexed { index, entry ->
                if ((index + 1) % PROGRESS_INTERVAL == 0 || index == entries.lastIndex) {
                    println("Embedded ${index + 1}/${entries.size} passages")
                }
                IndexedPassage(
                    passage = entry.passage,
                    // Identical to SeedMain's embedding input. If these ever diverge, the index and
                    // the database stop being interchangeable and the parity test goes red.
                    embedding = embedder.embed("${entry.passage.title}. ${entry.passage.text}"),
                    eco = entry.eco?.uppercase(),
                    moves = entry.moves,
                )
            }
        }

        CorpusIndexFile.write(output, CorpusIndex(manifest, rows))
        println(
            "Corpus index written: path=$output bytes=${output.fileSize()} rows=${manifest.expectedRowCount} " +
                "version=${manifest.version} corpus=$corpusDirectory",
        )
    }

    const val DEFAULT_INDEX_PATH = "corpus-index.bin"
    private const val PROGRESS_INTERVAL = 500

    private fun requireEnvironment(environment: Map<String, String>, name: String): String =
        requireNotNull(environment[name]?.takeIf(String::isNotBlank)) { "$name must be set" }
}
