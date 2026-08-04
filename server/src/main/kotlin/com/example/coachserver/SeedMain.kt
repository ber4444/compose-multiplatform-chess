package com.example.coachserver

import com.example.coachapi.Passage
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.nameWithoutExtension

object SeedMain {
    @JvmStatic
    fun main(args: Array<String>) {
        val environment = System.getenv()
        val dataSource = createDataSource(requireEnvironment(environment, "DATABASE_URL"))
        applySchema(dataSource)
        val embedder = OnnxMiniLmEmbedder(
            Path.of(requireEnvironment(environment, "COACH_EMBEDDING_MODEL")),
            Path.of(requireEnvironment(environment, "COACH_EMBEDDING_VOCAB")),
        )
        val repository = PostgresPassageRepository(dataSource)
        val corpusDirectory = Path.of(environment["COACH_CORPUS_DIR"] ?: "corpus")
        val passages = loadCorpus(corpusDirectory)
        embedder.use { embedding ->
            passages.forEach { entry ->
                repository.upsert(
                    passage = entry.passage,
                    embedding = embedding.embed("${entry.passage.title}. ${entry.passage.text}"),
                    eco = entry.eco,
                    moves = entry.moves,
                )
            }
        }
        dataSource.close()
        println("Seeded ${passages.size} opening passages from $corpusDirectory")
    }

    /**
     * A corpus row plus its structured retrieval keys. [moves] is the normalized SAN prefix that
     * makes opening identification an exact lookup; `null` for concept passages, which have no
     * move sequence and are only ever reachable through the vector tier.
     */
    internal data class CorpusEntry(
        val passage: Passage,
        val eco: String? = null,
        val moves: String? = null,
    )

    internal fun loadCorpus(directory: Path): List<CorpusEntry> = Files.list(directory).use { paths ->
        paths.sorted().flatMap { path ->
            when (path.extension.lowercase()) {
                "tsv" -> loadTsv(path).stream()
                "md" -> loadMarkdown(path).stream()
                else -> emptyList<CorpusEntry>().stream()
            }
        }.toList()
    }

    private fun loadTsv(path: Path): List<CorpusEntry> = Files.readAllLines(path)
        .drop(1)
        .mapIndexedNotNull { index, line ->
            val columns = line.split('\t')
            if (columns.size < 3) return@mapIndexedNotNull null
            val eco = columns[0].trim()
            val name = columns[1].trim()
            val pgn = columns[2].trim()
            // The ECO characterization leads because both composers quote the *first* sentence of
            // the top passage. Without it that sentence is "X is classified as ECO Y", which tells
            // the reader nothing they cannot see on the board.
            val body = EcoNarrator.characterize(eco)
                ?.let { "$it $name is classified as ECO $eco." }
                ?: "$name is classified as ECO $eco."
            CorpusEntry(
                passage = Passage(
                    sourceId = "lichess-${path.nameWithoutExtension}-${index + 1}-${eco.lowercase()}",
                    title = "$eco — $name",
                    text = "$body A representative move sequence is $pgn.",
                ),
                eco = eco.uppercase(),
                moves = MoveSequence.normalizePgn(pgn),
            )
        }

    private fun loadMarkdown(path: Path): List<CorpusEntry> {
        val chunks = Files.readString(path).split(Regex("\\n(?=## )"))
        return chunks.mapIndexedNotNull { index, chunk ->
            val lines = chunk.lines().filter(String::isNotBlank)
            if (lines.isEmpty()) return@mapIndexedNotNull null
            val title = lines.first().removePrefix("#").trim()
            val text = lines.drop(1).joinToString(" ").replace(Regex("\\s+"), " ").trim()
            if (text.isBlank()) return@mapIndexedNotNull null
            CorpusEntry(Passage("concept-${path.nameWithoutExtension}-$index", title, text.take(600)))
        }
    }

    private fun requireEnvironment(environment: Map<String, String>, name: String): String =
        requireNotNull(environment[name]?.takeIf(String::isNotBlank)) { "$name must be set" }
}
