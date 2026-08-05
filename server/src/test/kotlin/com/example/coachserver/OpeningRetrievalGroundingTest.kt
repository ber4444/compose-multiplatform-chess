package com.example.coachserver

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import java.nio.file.Path
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The grounding-quality gate for cloud retrieval: eight real openings, asserting the passage that
 * comes back is actually about the position that was sent.
 *
 * This exists because the previous embedding-only retrieval was measured wrong roughly half the
 * time against the live deployment — 1.e4 c5 returned the English Opening and the Global Opening,
 * 1.d4 d5 2.c4 returned four Rubinstein Opening passages, and 1.e4 e6 returned four Catalan
 * passages. Every one of those answers was fluent, cited, and validator-approved, so nothing in the
 * pipeline could notice. Only an assertion that compares the *returned* ECO against the *position*
 * can.
 *
 * Embeddings are deliberately all-zero here. A zero vector makes every row equidistant, so the
 * vector tier can contribute nothing and the assertions below can only pass via the book tier —
 * exact longest-prefix match on the move list. That keeps the test about retrieval *correctness*
 * rather than about MiniLM's mood, and it means it does not need the ONNX model on the test
 * classpath.
 */
@Testcontainers(disabledWithoutDocker = true)
class OpeningRetrievalGroundingTest {

    @Test
    fun `each opening retrieves a passage from its own ECO`() {
        val failures = CASES.mapNotNull { case ->
            val result = repository.retrieve(
                embedding = ZERO_VECTOR,
                limit = 4,
                movesSan = case.movesSan,
                // null on purpose: this is what the shipping clients send. The server has to
                // identify the opening from the moves alone.
                eco = null,
            )
            val top = result.passages.firstOrNull()
            when {
                result.resolvedEco != case.expectedEco ->
                    "${case.name}: resolved ECO ${result.resolvedEco}, expected ${case.expectedEco}"
                top == null -> "${case.name}: no passages returned"
                !top.title.startsWith(case.expectedEco) ->
                    "${case.name}: top passage '${top.title}' is not in ${case.expectedEco}"
                else -> null
            }
        }

        assertTrue(failures.isEmpty(), "Retrieval returned the wrong opening for:\n${failures.joinToString("\n")}")
    }

    @Test
    fun `the longest matching line wins over its shorter prefix`() {
        // 1.e4 alone is B00 (King's Pawn Game); the Sicilian is only correct because the two-ply
        // match is longer and `ORDER BY length(moves) DESC` prefers it.
        val kingsPawn = repository.retrieve(ZERO_VECTOR, 4, listOf("e4"), null)
        val sicilian = repository.retrieve(ZERO_VECTOR, 4, listOf("e4", "c5"), null)

        assertEquals("B00", kingsPawn.resolvedEco)
        assertEquals("B20", sicilian.resolvedEco)
    }

    @Test
    fun `a line deeper than the book still resolves to the family it belongs to`() {
        // Prefix matching means leaving published theory does not lose the opening: 1.a3 h6 2.a4 h5
        // is nobody's main line, but it is still Anderssen's Opening and should be named as such.
        // (An earlier version of this test expected `null`, assuming such a position is "out of
        // book". It isn't — the corpus covers all twenty legal first moves, so a prefix match
        // essentially always succeeds. That is the design working, not a gap.)
        val result = repository.retrieve(ZERO_VECTOR, 4, listOf("a3", "h6", "a4", "h5"), null)

        assertEquals("A00", result.resolvedEco)
        assertTrue(result.passages.isNotEmpty())
    }

    @Test
    fun `a position with no move history falls back to vector search`() {
        // The genuine book-miss path: nothing to match on, so tier 1 is skipped entirely and the
        // vector tiers must still return passages rather than an empty result.
        val result = repository.retrieve(ZERO_VECTOR, 4, emptyList(), null)

        assertEquals(null, result.resolvedEco)
        assertTrue(result.passages.isNotEmpty(), "Vector fallback must still retrieve passages")
    }

    @Test
    fun `retrieved passages say something beyond restating the ECO code`() {
        // Even perfect retrieval is worthless if the passage is a tautology. The seeded text must
        // lead with a claim about the opening, because both composers quote its first sentence.
        val result = repository.retrieve(ZERO_VECTOR, 1, listOf("e4", "c5"), null)
        val text = result.passages.single().text
        val firstSentence = text.substringBefore('.')

        assertTrue(
            !firstSentence.contains("is classified as ECO"),
            "Leading sentence is the tautology the composers quote: $firstSentence",
        )
        assertTrue(firstSentence.contains("Sicilian"), "Expected Sicilian content, got: $firstSentence")
    }

    private data class Case(val name: String, val movesSan: List<String>, val expectedEco: String)

    companion object {
        private val ZERO_VECTOR = FloatArray(OpeningService.EMBEDDING_DIMENSIONS)

        private val CASES = listOf(
            Case("Sicilian Defence", listOf("e4", "c5"), "B20"),
            Case("French Defence", listOf("e4", "e6"), "C00"),
            Case("Caro-Kann Defence", listOf("e4", "c6"), "B10"),
            Case("Ruy Lopez", listOf("e4", "e5", "Nf3", "Nc6", "Bb5"), "C60"),
            Case("Italian Game", listOf("e4", "e5", "Nf3", "Nc6", "Bc4"), "C50"),
            Case("Queen's Gambit", listOf("d4", "d5", "c4"), "D06"),
            Case("King's Indian Defence", listOf("d4", "Nf6", "c4", "g6"), "E60"),
            Case("English Opening", listOf("c4"), "A10"),
        )

        @Container
        @JvmStatic
        val postgres = PostgreSQLContainer("pgvector/pgvector:0.8.2-pg16")
            .withDatabaseName("coach")
            .withUsername("coach")
            .withPassword("coach")

        private lateinit var dataSource: HikariDataSource
        lateinit var repository: PostgresPassageRepository
            private set

        @BeforeAll
        @JvmStatic
        fun seedCorpus() {
            dataSource = HikariDataSource(
                HikariConfig().apply {
                    jdbcUrl = postgres.jdbcUrl
                    username = postgres.username
                    password = postgres.password
                    maximumPoolSize = 2
                },
            )
            applySchema(dataSource)
            repository = PostgresPassageRepository(dataSource)
            bulkSeed(dataSource, SeedMain.loadCorpus(Path.of("corpus")))
        }

        /**
         * One batched INSERT for the whole corpus. `PostgresPassageRepository.upsert` opens a
         * connection per row, which is fine for the nightly seed job and far too slow for ~3,800
         * rows in a test.
         */
        private fun bulkSeed(dataSource: DataSource, entries: List<SeedMain.CorpusEntry>) {
            dataSource.connection.use { connection ->
                com.pgvector.PGvector.registerTypes(connection)
                connection.autoCommit = false
                connection.prepareStatement(
                    "INSERT INTO passages(source_id, title, text, embedding, eco, moves) VALUES (?, ?, ?, ?, ?, ?)",
                ).use { statement ->
                    entries.forEach { entry ->
                        statement.setString(1, entry.passage.sourceId)
                        statement.setString(2, entry.passage.title)
                        statement.setString(3, entry.passage.text)
                        statement.setObject(4, com.pgvector.PGvector(ZERO_VECTOR))
                        statement.setString(5, entry.eco)
                        statement.setString(6, entry.moves)
                        statement.addBatch()
                    }
                    statement.executeBatch()
                }
                connection.commit()
            }
        }
    }
}
