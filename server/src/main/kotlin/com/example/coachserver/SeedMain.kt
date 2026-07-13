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
        embedder.use {
            passages.forEach { passage -> repository.upsert(passage, it.embed("${passage.title}. ${passage.text}")) }
        }
        dataSource.close()
        println("Seeded ${passages.size} opening passages from $corpusDirectory")
    }

    internal fun loadCorpus(directory: Path): List<Passage> = Files.list(directory).use { paths ->
        paths.sorted().flatMap { path ->
            when (path.extension.lowercase()) {
                "tsv" -> loadTsv(path).stream()
                "md" -> loadMarkdown(path).stream()
                else -> emptyList<Passage>().stream()
            }
        }.toList()
    }

    private fun loadTsv(path: Path): List<Passage> = Files.readAllLines(path)
        .drop(1)
        .mapIndexedNotNull { index, line ->
            val columns = line.split('\t')
            if (columns.size < 3) return@mapIndexedNotNull null
            val eco = columns[0].trim()
            val name = columns[1].trim()
            val pgn = columns[2].trim()
            Passage(
                sourceId = "lichess-${path.nameWithoutExtension}-${index + 1}-${eco.lowercase()}",
                title = "$eco — $name",
                text = "$name is classified as ECO $eco. A representative move sequence is $pgn.",
            )
        }

    private fun loadMarkdown(path: Path): List<Passage> {
        val chunks = Files.readString(path).split(Regex("\\n(?=## )"))
        return chunks.mapIndexedNotNull { index, chunk ->
            val lines = chunk.lines().filter(String::isNotBlank)
            if (lines.isEmpty()) return@mapIndexedNotNull null
            val title = lines.first().removePrefix("#").trim()
            val text = lines.drop(1).joinToString(" ").replace(Regex("\\s+"), " ").trim()
            if (text.isBlank()) return@mapIndexedNotNull null
            Passage("concept-${path.nameWithoutExtension}-$index", title, text.take(600))
        }
    }

    private fun requireEnvironment(environment: Map<String, String>, name: String): String =
        requireNotNull(environment[name]?.takeIf(String::isNotBlank)) { "$name must be set" }
}
