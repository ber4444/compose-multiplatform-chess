package com.example.evals

import com.example.myapplication.MoveRecord
import com.example.myapplication.Set
import com.example.ondeviceai.GameSummaryRequest
import com.example.ondeviceai.GameSummaryResponseValidator
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.ceil

@Serializable
private data class SummaryDeviceRow(
    val gameId: String,
    val pgn: String,
    val moveHistory: List<MoveRecord>,
    val playerSide: Set,
    val engineDifficultyName: String,
    val deterministicSummary: String,
    val elapsedMs: Long,
    val kind: String,
    val validation: Validation?,
    val fallbackReason: String? = null,
    val modelIdentifier: String = "",
    val deviceModel: String = "",
    val osVersion: String = "",
) {
    fun request() = GameSummaryRequest(pgn, moveHistory, playerSide, engineDifficultyName)

    @Serializable
    data class Validation(val rawText: String, val validatorInput: String, val accepted: Boolean)
}

internal data class SummaryDeviceRunReport(
    val rows: Int,
    val device: String,
    val model: String,
    val scored: Int,
    val accepted: Int,
    val baselineAccepted: Int,
    val inventedCitationRows: Int,
    val p95Ms: Long?,
    val disagreements: List<String>,
    val rejections: Map<String, Int>,
    val missingOutput: Map<String, Int>,
) {
    fun render(file: String): String = buildString {
        appendLine("Game Summary device run: $file")
        appendLine("  device/model:        $device / $model")
        appendLine("  rows:                $rows")
        appendLine("  model accepted:      $accepted/$scored evaluated ($accepted/$rows total)")
        appendLine("  baseline accepted:   $baselineAccepted/$rows")
        appendLine("  invented citations:  $inventedCitationRows row(s)")
        appendLine("  p95 turn latency:    ${p95Ms?.let { "$it ms" } ?: "n/a"}")
        appendLine("  model rejections:    $rejections")
        appendLine("  no completed output: $missingOutput")
        appendLine("  device verdict disagreements: ${disagreements.size} $disagreements")
    }
}

internal fun scoreSummaryDeviceRun(path: Path): SummaryDeviceRunReport {
    val json = Json { ignoreUnknownKeys = true }
    val rows = Files.readAllLines(path).mapIndexedNotNull { index, line ->
        if (line.isBlank()) return@mapIndexedNotNull null
        try {
            json.decodeFromString<SummaryDeviceRow>(line)
        } catch (e: SerializationException) {
            throw IllegalArgumentException(
                "$path:${index + 1}: expected a current Game Summary row with assessed move history " +
                    "and validation data. Repeat older runs using the updated benchmark.",
                e,
            )
        }
    }
    require(rows.isNotEmpty()) { "$path contains no Game Summary rows" }

    val rejections = mutableMapOf<String, Int>()
    val missingOutput = mutableMapOf<String, Int>()
    val disagreements = mutableListOf<String>()
    var scored = 0
    var accepted = 0
    var baselineAccepted = 0
    var inventedCitationRows = 0

    for (row in rows) {
        val request = row.request()
        if (GameSummaryResponseValidator.validate(row.deterministicSummary, request) is
            GameSummaryResponseValidator.Result.Valid
        ) baselineAccepted++

        val sample = row.validation
        if (sample == null) {
            val reason = row.fallbackReason ?: row.kind
            missingOutput[reason] = missingOutput.getOrDefault(reason, 0) + 1
            continue
        }
        scored++
        if (GameSummaryResponseValidator.inventedCitations(sample.rawText, request).isNotEmpty()) {
            inventedCitationRows++
        }
        val verdict = GameSummaryResponseValidator.validate(sample.validatorInput, request)
        when (verdict) {
            is GameSummaryResponseValidator.Result.Valid -> accepted++
            is GameSummaryResponseValidator.Result.Invalid -> {
                val rule = verdict.reason.substringBefore(":")
                rejections[rule] = rejections.getOrDefault(rule, 0) + 1
            }
        }
        if (sample.accepted != (verdict is GameSummaryResponseValidator.Result.Valid)) {
            disagreements += row.gameId
        }
    }

    val first = rows.first()
    val elapsed = rows.map { it.elapsedMs }.sorted()
    return SummaryDeviceRunReport(
        rows = rows.size,
        device = "${first.deviceModel} (${first.osVersion})",
        model = first.modelIdentifier,
        scored = scored,
        accepted = accepted,
        baselineAccepted = baselineAccepted,
        inventedCitationRows = inventedCitationRows,
        p95Ms = elapsed[ceil(elapsed.size * 0.95).toInt() - 1],
        disagreements = disagreements,
        rejections = rejections,
        missingOutput = missingOutput,
    )
}
