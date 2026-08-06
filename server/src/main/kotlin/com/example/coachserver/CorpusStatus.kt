package com.example.coachserver

import com.example.coachapi.CorpusDiagnostics
import kotlinx.serialization.Serializable
import javax.sql.DataSource

@Serializable
data class HealthReport(
    val status: String,
    val releaseVersion: String,
    val corpus: CorpusDiagnostics,
)

fun interface CorpusStatusReader {
    fun read(): CorpusDiagnostics
}

fun CorpusStatusReader.readOrUnavailable(): CorpusDiagnostics = runCatching(::read)
    .getOrElse { CorpusDiagnostics(ready = false) }

class PostgresCorpusStatusReader(private val dataSource: DataSource) : CorpusStatusReader {
    override fun read(): CorpusDiagnostics = dataSource.connection.use { connection ->
        connection.prepareStatement(
            "SELECT seed_version, row_count, final_source_id FROM corpus_seed_state WHERE singleton = TRUE",
        ).use { statement ->
            statement.executeQuery().use { rows ->
                if (!rows.next()) return@use CorpusDiagnostics(ready = false)
                CorpusDiagnostics(
                    ready = true,
                    seedVersion = rows.getString("seed_version"),
                    rowCount = rows.getInt("row_count"),
                    finalSourceId = rows.getString("final_source_id"),
                )
            }
        }
    }
}

fun verifyCorpus(dataSource: DataSource, manifest: CorpusSeedManifest): CorpusDiagnostics {
    val actual = PostgresCorpusStatusReader(dataSource).read()
    check(actual.ready) { "Corpus seed state is absent" }
    check(actual.seedVersion == manifest.version) { "Corpus seed version mismatch" }
    check(actual.rowCount == manifest.expectedRowCount) { "Corpus row count mismatch" }
    check(actual.finalSourceId == manifest.finalSourceId) { "Corpus final source mismatch" }
    return actual
}
