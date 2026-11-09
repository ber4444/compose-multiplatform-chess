package com.example.evals

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.ceil

@Serializable
private data class RulesQaDeviceRow(
    val question: String,
    val expectedPassageId: String?,
    val retrievedPassageId: String?,
    val modelAnswer: String?,
    val modelPassageIds: List<String>,
    val citedPassageIds: List<String>,
    val kind: String,
    val elapsedMs: Long,
    val fallbackReason: String? = null,
    val modelIdentifier: String = "",
    val deviceModel: String = "",
    val osVersion: String = "",
)

internal data class RulesQaDeviceRunReport(
    val rows: Int,
    val device: String,
    val model: String,
    val inCorpusHits: Int,
    val retrievalMisses: List<String>,
    val outOfCorpus: Int,
    val outOfCorpusHits: List<String>,
    val modelOutputs: Int,
    val surfaced: Int,
    val rescued: Int,
    val withoutCitations: Int,
    val inventedCitations: List<String>,
    val fallbackReasons: Map<String, Int>,
    val p95Ms: Long,
) {
    fun render(file: String): String = buildString {
        appendLine("Rules Q&A device run: $file")
        appendLine("  device/model:     $device / $model")
        appendLine("  BM25 in corpus:   $inCorpusHits/${rows - outOfCorpus} top hits")
        retrievalMisses.forEach { appendLine("    $it") }
        appendLine("  BM25 out of corpus: ${outOfCorpusHits.size}/$outOfCorpus returned a passage")
        outOfCorpusHits.forEach { appendLine("    $it") }
        appendLine("  model outputs:    $modelOutputs/$rows ($surfaced surfaced, $rescued rescued)")
        appendLine("  missing tags:     $withoutCitations/$modelOutputs")
        appendLine("  invented tags:    ${inventedCitations.size}/$modelOutputs")
        inventedCitations.forEach { appendLine("    $it") }
        appendLine("  fallback reasons: $fallbackReasons")
        appendLine("  p95 turn latency: $p95Ms ms")
    }
}

internal fun scoreRulesQaDeviceRun(path: Path): RulesQaDeviceRunReport {
    val json = Json { ignoreUnknownKeys = true }
    val rows = Files.readAllLines(path).filter { it.isNotBlank() }.map { json.decodeFromString<RulesQaDeviceRow>(it) }
    require(rows.isNotEmpty()) { "$path contains no Rules Q&A rows" }

    val first = rows.first()
    val inCorpus = rows.filter { it.expectedPassageId != null }
    val outOfCorpus = rows.filter { it.expectedPassageId == null }
    val outputs = rows.filter { it.modelAnswer != null }
    val elapsed = rows.map { it.elapsedMs }.sorted()

    return RulesQaDeviceRunReport(
        rows = rows.size,
        device = "${first.deviceModel} (${first.osVersion})",
        model = first.modelIdentifier,
        inCorpusHits = inCorpus.count { it.expectedPassageId == it.retrievedPassageId },
        retrievalMisses = inCorpus.filter { it.expectedPassageId != it.retrievedPassageId }.map {
            "${it.question} [expected ${it.expectedPassageId}, found ${it.retrievedPassageId ?: "none"}]"
        },
        outOfCorpus = outOfCorpus.size,
        outOfCorpusHits = outOfCorpus.filter { it.retrievedPassageId != null }.map { it.question },
        modelOutputs = outputs.size,
        surfaced = outputs.count { it.kind == "Model" },
        rescued = outputs.count { it.kind == "Grounded" },
        withoutCitations = outputs.count { it.citedPassageIds.isEmpty() },
        inventedCitations = outputs.filter { row -> row.citedPassageIds.any { it !in row.modelPassageIds } }
            .map { it.question },
        fallbackReasons = rows.mapNotNull { it.fallbackReason }.groupingBy { it }.eachCount(),
        p95Ms = elapsed[ceil(elapsed.size * 0.95).toInt() - 1],
    )
}
