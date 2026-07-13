package com.example.coachserver

import com.example.coachapi.Passage
import com.pgvector.PGvector
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import java.net.URI
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
        schema.split(';').map(String::trim).filter(String::isNotEmpty).forEach { statement ->
            connection.createStatement().use { it.execute(statement) }
        }
    }
}

class PostgresPassageRepository(
    private val dataSource: DataSource,
) : PassageRepository {
    override fun retrieve(embedding: FloatArray, limit: Int): List<Passage> {
        require(embedding.size == OpeningService.EMBEDDING_DIMENSIONS)
        dataSource.connection.use { connection ->
            PGvector.registerTypes(connection)
            connection.prepareStatement(
                "SELECT source_id, title, text FROM passages ORDER BY embedding <=> ? LIMIT ?",
            ).use { statement ->
                statement.setObject(1, PGvector(embedding))
                statement.setInt(2, limit)
                statement.executeQuery().use { results ->
                    return buildList {
                        while (results.next()) {
                            add(
                                Passage(
                                    sourceId = results.getString("source_id"),
                                    title = results.getString("title"),
                                    text = results.getString("text"),
                                ),
                            )
                        }
                    }
                }
            }
        }
    }

    override fun upsert(passage: Passage, embedding: FloatArray) {
        require(embedding.size == OpeningService.EMBEDDING_DIMENSIONS)
        dataSource.connection.use { connection ->
            PGvector.registerTypes(connection)
            connection.prepareStatement(
                """
                INSERT INTO passages(source_id, title, text, embedding)
                VALUES (?, ?, ?, ?)
                ON CONFLICT (source_id) DO UPDATE SET
                    title = EXCLUDED.title,
                    text = EXCLUDED.text,
                    embedding = EXCLUDED.embedding
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, passage.sourceId)
                statement.setString(2, passage.title)
                statement.setString(3, passage.text)
                statement.setObject(4, PGvector(embedding))
                statement.executeUpdate()
            }
        }
    }
}

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
