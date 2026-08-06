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
    data class CorpusEntry(
        val passage: Passage,
        val eco: String? = null,
        val moves: String? = null,
    )

    fun loadCorpus(directory: Path): List<CorpusEntry> = Files.list(directory).use { paths ->
        paths.sorted().flatMap { path ->
            when (path.extension.lowercase()) {
                "tsv" -> loadTsv(path).stream()
                "md" -> loadMarkdown(path).stream()
                else -> emptyList<CorpusEntry>().stream()
            }
        }.toList()
    }

    private fun loadTsv(path: Path): List<CorpusEntry> {
        val rows = Files.readAllLines(path).drop(1).mapIndexedNotNull { index, line ->
            val columns = line.split('\t')
            if (columns.size < 3) return@mapIndexedNotNull null
            TsvRow(
                index = index,
                eco = columns[0].trim(),
                name = columns[1].trim(),
                pgn = columns[2].trim(),
            )
        }
        // The shortest line in each ECO is that family's base line, and for it the family claim is
        // the best available sentence ("Sicilian Defence: Black answers the king's pawn with c5…").
        // Deeper rows share that same claim, which is what made four B20 passages read identically,
        // so they lead with what distinguishes them instead. See LineNarrator.
        val basePlyByEco = rows.groupBy { it.eco.uppercase() }
            .mapValues { (_, group) -> group.minOf { MoveSequence.normalizePgn(it.pgn).plyCount() } }

        return rows.map { row ->
            val moves = MoveSequence.normalizePgn(row.pgn)
            val isBaseLine = moves.plyCount() <= (basePlyByEco[row.eco.uppercase()] ?: 0)
            val lineClaim = if (isBaseLine) null else LineNarrator.describe(moves.split(' ').filter(String::isNotBlank))
            // Order is the mechanism, not a style choice: both composers quote the *first* sentence
            // of the top passage, so whichever claim leads is the only one most users ever read.
            val body = listOfNotNull(
                lineClaim,
                EcoNarrator.characterize(row.eco),
                "${row.name} is classified as ECO ${row.eco}.",
            ).joinToString(" ")
            CorpusEntry(
                passage = Passage(
                    sourceId = "lichess-${path.nameWithoutExtension}-${row.index + 1}-${row.eco.lowercase()}",
                    title = "${row.eco} — ${row.name}",
                    text = "$body A representative move sequence is ${row.pgn}.",
                ),
                eco = row.eco.uppercase(),
                moves = moves,
            )
        }
    }

    private data class TsvRow(val index: Int, val eco: String, val name: String, val pgn: String)

    private fun String.plyCount(): Int = split(' ').count(String::isNotBlank)

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
