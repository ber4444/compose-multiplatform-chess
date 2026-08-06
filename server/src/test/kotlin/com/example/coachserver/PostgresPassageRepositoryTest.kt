package com.example.coachserver

import com.example.coachapi.Passage
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.junit.jupiter.api.Test
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@Testcontainers(disabledWithoutDocker = true)
class PostgresPassageRepositoryTest {
    @Test
    fun `retrieves nearest passage by cosine distance`() {
        HikariDataSource(
            HikariConfig().apply {
                jdbcUrl = postgres.jdbcUrl
                username = postgres.username
                password = postgres.password
                maximumPoolSize = 2
            },
        ).use { dataSource ->
            applySchema(dataSource)
            dataSource.connection.use { it.prepareStatement("TRUNCATE passages, corpus_seed_state").execute() }
            val repository = PostgresPassageRepository(dataSource)
            repository.upsert(Passage("near", "Near", "Nearest vector"), vector(first = 1f))
            repository.upsert(Passage("far", "Far", "Farther vector"), vector(first = -1f))

            val results = repository.retrieve(vector(first = 0.9f), limit = 1)

            assertEquals(listOf("near"), results.passages.map(Passage::sourceId))
        }
    }

    @Test
    fun `replaceCorpus batches rows records a manifest and removes stale passages`() {
        HikariDataSource(
            HikariConfig().apply {
                jdbcUrl = postgres.jdbcUrl
                username = postgres.username
                password = postgres.password
                maximumPoolSize = 2
            },
        ).use { dataSource ->
            applySchema(dataSource)
            dataSource.connection.use { it.prepareStatement("TRUNCATE passages, corpus_seed_state").execute() }
            val repository = PostgresPassageRepository(dataSource)
            repository.upsert(Passage("stale", "Stale", "Old corpus row"), vector(first = -1f))
            val entries = listOf(
                SeedMain.CorpusEntry(Passage("z-first", "First", "First current row"), "A00", "a3"),
                SeedMain.CorpusEntry(Passage("a-last", "Last", "Last current row"), "B00", "e4"),
            )
            val manifest = CorpusSeedManifest.from(entries)
            val progress = mutableListOf<Int>()

            repository.replaceCorpus(
                rows = entries.mapIndexed { index, entry ->
                    SeededPassage(entry.passage, vector(first = (index + 1).toFloat()), entry.eco, entry.moves)
                },
                manifest = manifest,
                batchSize = 1,
            ) { completed, total ->
                assertEquals(2, total)
                progress += completed
            }

            dataSource.connection.use { connection ->
                connection.prepareStatement("SELECT source_id FROM passages ORDER BY source_id").use { statement ->
                    statement.executeQuery().use { rows ->
                        val ids = buildList { while (rows.next()) add(rows.getString(1)) }
                        assertEquals(listOf("a-last", "z-first"), ids)
                    }
                }
                connection.prepareStatement(
                    "SELECT seed_version, row_count, final_source_id FROM corpus_seed_state WHERE singleton = TRUE",
                ).use { statement ->
                    statement.executeQuery().use { state ->
                        assertTrue(state.next())
                        assertEquals(manifest.version, state.getString(1))
                        assertEquals(2, state.getInt(2))
                        assertEquals("a-last", state.getString(3))
                    }
                }
            }
            assertEquals(listOf(1, 2), progress)
        }
    }

    private fun vector(first: Float): FloatArray = FloatArray(384).also { it[0] = first }

    companion object {
        @Container
        @JvmStatic
        val postgres = PostgreSQLContainer("pgvector/pgvector:0.8.2-pg16")
            .withDatabaseName("coach")
            .withUsername("coach")
            .withPassword("coach")
    }
}
