package com.example.coachserver

import com.example.coachapi.Passage
import com.pgvector.PGvector
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import java.net.URI
import java.sql.Connection
import java.sql.ResultSet
import javax.sql.DataSource

fun createDataSource(databaseUrl: String): HikariDataSource {
    val parsed = DatabaseUrl.parse(databaseUrl)
    return HikariDataSource(
        HikariConfig().apply {
            jdbcUrl = parsed.jdbcUrl
            parsed.username?.let { username = it }
            parsed.password?.let { password = it }
            maximumPoolSize = 5
            minimumIdle = 0
            connectionTimeout = 10_000
        },
    )
}

fun applySchema(dataSource: DataSource) {
    val schema = checkNotNull(object {}.javaClass.getResource("/schema.sql")) {
        "schema.sql is missing from server resources"
    }.readText()
    dataSource.connection.use { connection ->
        splitStatements(schema).forEach { statement ->
            connection.createStatement().use { it.execute(statement) }
        }
    }
}

/**
 * Splits `schema.sql` into executable statements.
 *
 * `--` comments are stripped **before** splitting on `;`, and that ordering is the entire point: a
 * semicolon inside a prose comment used to cut the comment in half, leaving the tail as a statement
 * of its own. Since `applySchema` runs at boot, the result was a crash loop on a syntax error whose
 * message pointed at the first character of a comment fragment. Prose in this file is written by
 * humans and will contain semicolons again.
 */
internal fun splitStatements(sql: String): List<String> = sql
    .lineSequence()
    .map { line -> line.substringBefore("--").trimEnd() }
    .joinToString("\n")
    .split(';')
    .map(String::trim)
    .filter(String::isNotEmpty)

class PostgresPassageRepository(
    private val dataSource: DataSource,
) : PassageRepository {

    /**
     * Three tiers, most-precise first, deduplicated by `source_id`:
     *
     * 1. **Book** — longest exact prefix match on the normalized move list. This is what actually
     *    identifies the opening; it is arithmetic, not similarity.
     * 2. **ECO-filtered vector** — nearest neighbours *within* the resolved ECO, so the remaining
     *    slots stay on-topic instead of wandering into another volume.
     * 3. **Plain vector** — the old behaviour, reached only when the moves match no book line
     *    (an out-of-book middlegame) or the corpus predates the `moves`/`eco` columns.
     *
     * Tier 3 is why an un-reseeded database degrades to exactly today's behaviour rather than
     * returning nothing: the new columns are `NULL`, tiers 1 and 2 match no rows, and tier 3 runs.
     */
    override fun retrieve(
        embedding: FloatArray,
        limit: Int,
        movesSan: List<String>,
        eco: String?,
    ): RetrievalResult {
        require(embedding.size == OpeningService.EMBEDDING_DIMENSIONS)
        val normalizedMoves = MoveSequence.normalizeSan(movesSan)
        dataSource.connection.use { connection ->
            PGvector.registerTypes(connection)
            val book = if (normalizedMoves.isEmpty()) {
                emptyList()
            } else {
                queryBook(connection, normalizedMoves, minOf(BOOK_LIMIT, limit))
            }
            val resolvedEco = book.firstOrNull()?.second ?: eco
            val collected = LinkedHashMap<String, Passage>()
            book.forEach { (passage, _) -> collected[passage.sourceId] = passage }
            if (collected.size < limit && resolvedEco != null) {
                queryVector(connection, embedding, limit, resolvedEco)
                    .forEach { collected.putIfAbsent(it.sourceId, it) }
            }
            if (collected.size < limit) {
                queryVector(connection, embedding, limit, null)
                    .forEach { collected.putIfAbsent(it.sourceId, it) }
            }
            return RetrievalResult(collected.values.take(limit), resolvedEco)
        }
    }

    /**
     * Longest-prefix match over the corpus move index. `moves` never contains `%` or `_` (it is
     * SAN), so it is safe to use the stored value as the LIKE pattern rather than the parameter.
     */
    private fun queryBook(
        connection: Connection,
        normalizedMoves: String,
        limit: Int,
    ): List<Pair<Passage, String?>> = connection.prepareStatement(
        """
        SELECT source_id, title, text, eco FROM passages
        WHERE moves = (
            SELECT moves FROM passages
            WHERE moves IS NOT NULL AND moves <> ''
              AND (moves = ? OR ? LIKE moves || ' %')
            ORDER BY length(moves) DESC
            LIMIT 1
        )
        LIMIT ?
        """.trimIndent(),
    ).use { statement ->
        statement.setString(1, normalizedMoves)
        statement.setString(2, normalizedMoves)
        statement.setInt(3, limit)
        statement.executeQuery().use { results ->
            buildList {
                while (results.next()) {
                    add(results.toPassage() to results.getString("eco"))
                }
            }
        }
    }

    private fun queryVector(
        connection: Connection,
        embedding: FloatArray,
        limit: Int,
        eco: String?,
    ): List<Passage> {
        val sql = if (eco == null) {
            "SELECT source_id, title, text FROM passages ORDER BY embedding <=> ? LIMIT ?"
        } else {
            "SELECT source_id, title, text FROM passages WHERE eco = ? ORDER BY embedding <=> ? LIMIT ?"
        }
        return connection.prepareStatement(sql).use { statement ->
            var index = 1
            if (eco != null) statement.setString(index++, eco.uppercase())
            statement.setObject(index++, PGvector(embedding))
            statement.setInt(index, limit)
            statement.executeQuery().use { results ->
                buildList {
                    while (results.next()) add(results.toPassage())
                }
            }
        }
    }

    private fun ResultSet.toPassage() = Passage(
        sourceId = getString("source_id"),
        title = getString("title"),
        text = getString("text"),
    )

    override fun upsert(passage: Passage, embedding: FloatArray, eco: String?, moves: String?) {
        require(embedding.size == OpeningService.EMBEDDING_DIMENSIONS)
        dataSource.connection.use { connection ->
            PGvector.registerTypes(connection)
            connection.prepareStatement(
                """
                INSERT INTO passages(source_id, title, text, embedding, eco, moves)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT (source_id) DO UPDATE SET
                    title = EXCLUDED.title,
                    text = EXCLUDED.text,
                    embedding = EXCLUDED.embedding,
                    eco = EXCLUDED.eco,
                    moves = EXCLUDED.moves
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, passage.sourceId)
                statement.setString(2, passage.title)
                statement.setString(3, passage.text)
                statement.setObject(4, PGvector(embedding))
                statement.setString(5, eco?.uppercase())
                statement.setString(6, moves)
                statement.executeUpdate()
            }
        }
    }

    /**
     * Replaces the searchable corpus atomically using bounded JDBC batches on one connection.
     *
     * The final count, final sorted source id, and manifest version are checked before commit.
     * A failed embedding, write, or check rolls the transaction back, leaving the last known-good
     * corpus and its `corpus_seed_state` row intact.
     */
    fun replaceCorpus(
        rows: List<SeededPassage>,
        manifest: CorpusSeedManifest,
        batchSize: Int = DEFAULT_SEED_BATCH_SIZE,
        onProgress: (completed: Int, total: Int) -> Unit = { _, _ -> },
    ) {
        require(rows.size == manifest.expectedRowCount) { "Seed row count does not match manifest" }
        require(rows.isNotEmpty()) { "Cannot seed an empty corpus" }
        require(batchSize > 0) { "batchSize must be positive" }
        require(rows.last().passage.sourceId == manifest.finalSourceId) { "Seed order does not match manifest" }
        rows.forEach { require(it.embedding.size == OpeningService.EMBEDDING_DIMENSIONS) }

        dataSource.connection.use { connection ->
            PGvector.registerTypes(connection)
            val originalAutoCommit = connection.autoCommit
            connection.autoCommit = false
            try {
                connection.prepareStatement(UPSERT_SEEDED_PASSAGE).use { statement ->
                    rows.forEachIndexed { index, row ->
                        statement.setString(1, row.passage.sourceId)
                        statement.setString(2, row.passage.title)
                        statement.setString(3, row.passage.text)
                        statement.setObject(4, PGvector(row.embedding))
                        statement.setString(5, row.eco?.uppercase())
                        statement.setString(6, row.moves)
                        statement.setString(7, manifest.version)
                        statement.addBatch()
                        if ((index + 1) % batchSize == 0 || index == rows.lastIndex) {
                            statement.executeBatch()
                            onProgress(index + 1, rows.size)
                        }
                    }
                }
                // A replacement seed must not leave deleted or renamed old passages searchable.
                connection.prepareStatement("DELETE FROM passages WHERE seed_version IS DISTINCT FROM ?").use { statement ->
                    statement.setString(1, manifest.version)
                    statement.executeUpdate()
                }
                val insertedCount = connection.prepareStatement(
                    "SELECT COUNT(*) FROM passages WHERE seed_version = ?",
                ).use { statement ->
                    statement.setString(1, manifest.version)
                    statement.executeQuery().use { results -> results.next(); results.getInt(1) }
                }
                check(insertedCount == manifest.expectedRowCount) {
                    "Seed row count mismatch: expected ${manifest.expectedRowCount}, found $insertedCount"
                }
                val containsFinalSource = connection.prepareStatement(
                    "SELECT EXISTS(SELECT 1 FROM passages WHERE seed_version = ? AND source_id = ?)",
                ).use { statement ->
                    statement.setString(1, manifest.version)
                    statement.setString(2, manifest.finalSourceId)
                    statement.executeQuery().use { results -> results.next(); results.getBoolean(1) }
                }
                check(containsFinalSource) {
                    "Seed is missing final source ${manifest.finalSourceId}"
                }
                connection.prepareStatement(
                    """
                    INSERT INTO corpus_seed_state(singleton, seed_version, row_count, final_source_id)
                    VALUES (TRUE, ?, ?, ?)
                    ON CONFLICT (singleton) DO UPDATE SET
                        seed_version = EXCLUDED.seed_version,
                        row_count = EXCLUDED.row_count,
                        final_source_id = EXCLUDED.final_source_id,
                        seeded_at = NOW()
                    """.trimIndent(),
                ).use { statement ->
                    statement.setString(1, manifest.version)
                    statement.setInt(2, manifest.expectedRowCount)
                    statement.setString(3, manifest.finalSourceId)
                    statement.executeUpdate()
                }
                connection.commit()
            } catch (error: Throwable) {
                connection.rollback()
                throw error
            } finally {
                connection.autoCommit = originalAutoCommit
            }
        }
    }

    private companion object {
        /** Book hits are capped so the vector tiers still contribute related material. */
        const val BOOK_LIMIT = 2
        const val DEFAULT_SEED_BATCH_SIZE = 200
        val UPSERT_SEEDED_PASSAGE =
            """
            INSERT INTO passages(source_id, title, text, embedding, eco, moves, seed_version)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (source_id) DO UPDATE SET
                title = EXCLUDED.title,
                text = EXCLUDED.text,
                embedding = EXCLUDED.embedding,
                eco = EXCLUDED.eco,
                moves = EXCLUDED.moves,
                seed_version = EXCLUDED.seed_version
            """.trimIndent()
    }
}

data class SeededPassage(
    val passage: Passage,
    val embedding: FloatArray,
    val eco: String?,
    val moves: String?,
)

private data class DatabaseUrl(
    val jdbcUrl: String,
    val username: String?,
    val password: String?,
) {
    companion object {
        fun parse(value: String): DatabaseUrl {
            if (value.startsWith("jdbc:")) return DatabaseUrl(value, null, null)
            val uri = URI(value.replaceFirst("postgres://", "postgresql://"))
            require(uri.scheme == "postgresql") { "DATABASE_URL must use postgres, postgresql, or jdbc" }
            val credentials = uri.userInfo?.split(':', limit = 2)
            val port = if (uri.port == -1) "" else ":${uri.port}"
            val query = uri.rawQuery?.let { "?$it" }.orEmpty()
            return DatabaseUrl(
                jdbcUrl = "jdbc:postgresql://${uri.host}$port${uri.rawPath}$query",
                username = credentials?.getOrNull(0),
                password = credentials?.getOrNull(1),
            )
        }
    }
}
