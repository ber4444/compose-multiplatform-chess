package com.example.coachserver

import com.example.coachapi.Passage
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.junit.jupiter.api.Test
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import kotlin.test.assertEquals

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
            val repository = PostgresPassageRepository(dataSource)
            repository.upsert(Passage("near", "Near", "Nearest vector"), vector(first = 1f))
            repository.upsert(Passage("far", "Far", "Farther vector"), vector(first = -1f))

            val results = repository.retrieve(vector(first = 0.9f), limit = 1)

            assertEquals(listOf("near"), results.map(Passage::sourceId))
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
