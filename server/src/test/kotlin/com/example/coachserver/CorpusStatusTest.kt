package com.example.coachserver

import com.example.coachapi.CorpusDiagnostics
import com.example.coachapi.Passage
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.junit.jupiter.api.Test
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

@Testcontainers(disabledWithoutDocker = true)
class CorpusStatusTest {
    @Test
    fun matchingSeededManifestVerifies() {
        withDatabase { dataSource ->
            applySchema(dataSource)
            val repository = PostgresPassageRepository(dataSource)
            val entries = listOf(
                SeedMain.CorpusEntry(Passage("id1", "Title", "Text"), "A00", "a3"),
            )
            val manifest = CorpusSeedManifest.from(entries)
            val rows = entries.map {
                SeededPassage(it.passage, FloatArray(384), it.eco, it.moves)
            }
            repository.replaceCorpus(rows, manifest)

            assertEquals(
                CorpusDiagnostics(true, manifest.version, manifest.expectedRowCount, manifest.finalSourceId),
                verifyCorpus(dataSource, manifest)
            )
        }
    }

    @Test
    fun differentFinalSourceFailsVerification() {
        withDatabase { dataSource ->
            applySchema(dataSource)
            val entries = listOf(
                SeedMain.CorpusEntry(Passage("id1", "Title", "Text"), "A00", "a3"),
            )
            val manifest = CorpusSeedManifest.from(entries)
            
            insertSeedState(dataSource, manifest.version, manifest.expectedRowCount, "wrong-source")
            
            assertFailsWith<IllegalStateException> { verifyCorpus(dataSource, manifest) }
        }
    }

    private fun insertSeedState(dataSource: DataSource, version: String, count: Int, finalSourceId: String) {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                "INSERT INTO corpus_seed_state (singleton, seed_version, row_count, final_source_id) VALUES (TRUE, ?, ?, ?)"
            ).use { statement ->
                statement.setString(1, version)
                statement.setInt(2, count)
                statement.setString(3, finalSourceId)
                statement.execute()
            }
        }
    }

    private fun withDatabase(block: (DataSource) -> Unit) {
        HikariDataSource(
            HikariConfig().apply {
                jdbcUrl = postgres.jdbcUrl
                username = postgres.username
                password = postgres.password
                maximumPoolSize = 2
            }
        ).use { dataSource ->
            applySchema(dataSource)
            dataSource.connection.use { it.prepareStatement("TRUNCATE passages, corpus_seed_state").execute() }
            block(dataSource)
        }
    }

    companion object {
        @Container
        @JvmStatic
        val postgres = PostgreSQLContainer("pgvector/pgvector:0.8.2-pg16")
            .withDatabaseName("coach")
            .withUsername("coach")
            .withPassword("coach")
    }
}
