package com.example.evals

import com.example.ondeviceai.MoveCoachRequest
import com.example.ondeviceai.MoveCoachResponseValidator
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path

/**
 * Scores a device bench run — `AndroidBenchRunner`'s `results.jsonl` — with the scorer CI already
 * uses, instead of a second scorer written next to the device data.
 *
 * **Why this exists.** `EvalMain` runs composers in-process on the JVM, so there was no path from a
 * phone's JSONL into [EvalScorer] at all. The gap got filled the obvious wrong way first: a throwaway
 * script that re-implemented [ConceptVocabulary] beside the results file. Two scorers that disagree
 * is the failure `CorpusBookIndex` carries a cross-check to prevent, and a device run is exactly
 * where it would go unnoticed — nobody re-runs a 13-minute phone benchmark to check a metric.
 *
 * **The device already scored grounding, and this must agree with it.** `EvalScorer.scoreMove`'s
 * grounding column *is* `MoveCoachResponseValidator`, which the orchestrator ran on-device per row;
 * a veto is recorded as `fallbackTriggered` with a `model output failed validation` reason. So this
 * scorer's job is not to recompute that verdict but to reproduce it — [DeviceRunReport.disagreements]
 * is empty when it does. A non-empty list has two possible causes and they want opposite responses:
 * either the row's recorded facts no longer rebuild the request the device validated against (drift
 * — the numbers are not the device's and the run should be repeated), or a validator rule has been
 * added since the run, in which case the disagreement is the measurement of that rule against real
 * recorded output and no re-run is needed to read it.
 *
 * What it adds on top is the column the device cannot produce: the same score for
 * `deterministicExplanation`, so "does the model beat the deterministic layer" is one file and one
 * scorer rather than two runs.
 *
 * **Not wired into `evals/scorecard.md` on purpose.** Every row there is reproducible by
 * `./gradlew :evals:run` in CI. This one needs a specific phone, a foregrounded app and ~13 minutes;
 * pasting it into the gated scorecard would put an unreproducible number under a gate that claims
 * everything in it re-runs. Run it by hand and quote the output.
 */
@Serializable
data class DeviceBenchRow(
    val caseId: String,
    val isFallbackGolden: Boolean = false,
    val factsPopulated: Boolean = false,
    val tags: List<String> = emptyList(),
    val moveDisplay: String? = null,
    val deterministicExplanation: String? = null,
    val moveClassName: String? = null,
    val motifs: List<String> = emptyList(),
    val winPercentLost: Double? = null,
    val betterMoveDisplay: String? = null,
    val modelIdentifier: String = "",
    val deviceModel: String = "",
    val osVersion: String = "",
    val fallbackTriggered: Boolean = false,
    val fallbackReason: String? = null,
    val rawOutput: String? = null,
) {
    /** The device's own validator verdict, or null when it never got a text to validate. */
    val deviceAcceptedText: Boolean?
        get() = when {
            rawOutput.isNullOrBlank() -> null
            fallbackTriggered && fallbackReason?.contains("validation") == true -> false
            fallbackTriggered -> null
            else -> true
        }
}

object DeviceRunLoader {
    // Lenient, unlike GoldenCaseLoader: the file is produced by whatever build is on the phone,
    // which is routinely newer than the checkout scoring it. An unknown column is drift to notice
    // later, not a reason to refuse the run's only copy of the data.
    private val json = Json { ignoreUnknownKeys = true }

    fun load(path: Path): List<DeviceBenchRow> = Files.readAllLines(path)
        .filter { it.isNotBlank() }
        .map { json.decodeFromString(DeviceBenchRow.serializer(), it) }
}

/**
 * Rebuilds the request the device validated against from the facts the row carries.
 *
 * `moveUci` comes from the golden case because the row records only the display form; everything
 * else is the row's, not the case's — that is the whole point (see [EvalScorer.scoreMove]).
 */
internal fun DeviceBenchRow.toMoveCoachRequest(case: GoldenCase): MoveCoachRequest {
    val display = moveDisplay ?: case.movesSan.lastOrNull() ?: case.bestMoveUci
    return MoveCoachRequest(
        moveUci = case.bestMoveUci,
        moveDisplay = display,
        deterministicHeadline = "You played $display.",
        deterministicExplanation = deterministicExplanation ?: "",
        engineDifficultyName = "Hard",
        moveClassName = moveClassName,
        motifs = motifs,
        winPercentLost = winPercentLost,
        betterMoveDisplay = betterMoveDisplay,
    )
}

data class DeviceRunReport(
    val file: String,
    val device: String,
    val model: String,
    val rows: Int,
    /** Rows carrying a model answer to score; the rest never produced text. */
    val scored: Int,
    val unusable: Map<String, Int>,
    val modelGrounded: Int,
    val baselineGrounded: Int,
    val baselineScored: Int,
    val modelLengthViolations: Int,
    val modelFluencyViolations: Int,
    val moveClasses: Map<String, Int>,
    val withBetterMove: Int,
    /** Cases where this scorer's verdict differs from the device's. Empty means no drift. */
    val disagreements: List<String>,
    /** Rows excluded from every quality column because the run was not a measurement. */
    val placeholderRows: Int,
    /**
     * Which rule rejected, per column. A bare rejection *rate* conflates "said something false"
     * with "tripped a rule that cannot apply to this column" — the deterministic line is the
     * prompt's own baseline sentence, so it trips the echoed-prompt rule by construction and that
     * number says nothing about its quality.
     */
    val modelRejections: Map<String, Int>,
    val baselineRejections: Map<String, Int>,
) {
    fun render(): String = buildString {
        appendLine("device run: $file")
        appendLine("  device/model:      $device / $model")
        appendLine("  rows:              $rows (scored $scored, placeholder $placeholderRows)")
        unusable.forEach { (reason, n) -> appendLine("  no answer:         $n × $reason") }
        appendLine("  grounded (model):  $modelGrounded/$scored $modelRejections")
        appendLine("  grounded (determ): $baselineGrounded/$baselineScored $baselineRejections")
        appendLine("  length violations: $modelLengthViolations")
        appendLine("  fluency violations:$modelFluencyViolations")
        appendLine("  move classes:      $moveClasses")
        appendLine("  rows citing a better move: $withBetterMove")
        if (disagreements.isEmpty()) {
            appendLine("  agrees with the device's own validator on all $scored scored rows")
        } else {
            appendLine("  DISAGREES with the device on ${disagreements.size} row(s) — the row's facts no")
            appendLine("  longer rebuild the request it validated against: ${disagreements.take(8)}")
        }
    }
}

fun scoreDeviceRun(rows: List<DeviceBenchRow>, cases: List<GoldenCase>): DeviceRunReport {
    val byId = cases.associateBy { it.id }
    val unusable = mutableMapOf<String, Int>()
    val disagreements = mutableListOf<String>()
    var scored = 0
    var modelGrounded = 0
    var baselineScored = 0
    var baselineGrounded = 0
    var lengthViolations = 0
    var fluencyViolations = 0
    val modelRejections = mutableMapOf<String, Int>()
    val baselineRejections = mutableMapOf<String, Int>()

    // The validator is called a second time purely to recover *which* rule fired; EvalScorer keeps
    // returning the boolean, so there is still one grounding verdict and not two.
    fun rejectionOf(text: String, request: MoveCoachRequest): String? =
        (MoveCoachResponseValidator.validate(text, request) as? MoveCoachResponseValidator.Result.Invalid)
            ?.reason?.substringBefore(":")

    // A placeholder run is the failure this whole column exists to make visible: unassessed
    // fixtures produce fluent text about a position the model was told nothing about, and the rows
    // look identical to real ones. Never score them.
    val (measurable, placeholder) = rows.partition { it.factsPopulated && !it.isFallbackGolden }

    for (row in measurable) {
        val case = byId[row.caseId] ?: continue
        val request = row.toMoveCoachRequest(case)

        row.deterministicExplanation?.takeIf { it.isNotBlank() }?.let { baseline ->
            baselineScored++
            if (EvalScorer.scoreMove(request, baseline).grounded) baselineGrounded++
            rejectionOf(baseline, request)?.let { baselineRejections[it] = (baselineRejections[it] ?: 0) + 1 }
        }

        val text = row.rawOutput
        if (text.isNullOrBlank()) {
            val reason = row.fallbackReason?.substringBefore(".")?.take(60) ?: "empty output"
            unusable[reason] = (unusable[reason] ?: 0) + 1
            continue
        }

        scored++
        val score = EvalScorer.scoreMove(request, text)
        if (score.grounded) modelGrounded++
        rejectionOf(text, request)?.let { modelRejections[it] = (modelRejections[it] ?: 0) + 1 }
        if (score.lengthViolation) lengthViolations++
        if (!score.fluencyCompliant) fluencyViolations++

        val deviceVerdict = row.deviceAcceptedText
        if (deviceVerdict != null && deviceVerdict != score.grounded) disagreements += row.caseId
    }

    return DeviceRunReport(
        file = "",
        device = rows.firstOrNull()?.let { "${it.deviceModel} (Android ${it.osVersion})" } ?: "unknown",
        model = rows.firstOrNull()?.modelIdentifier ?: "unknown",
        rows = rows.size,
        scored = scored,
        unusable = unusable,
        modelGrounded = modelGrounded,
        baselineGrounded = baselineGrounded,
        baselineScored = baselineScored,
        modelLengthViolations = lengthViolations,
        modelFluencyViolations = fluencyViolations,
        moveClasses = measurable.mapNotNull { it.moveClassName }.groupingBy { it }.eachCount(),
        withBetterMove = measurable.count { !it.betterMoveDisplay.isNullOrBlank() },
        disagreements = disagreements,
        placeholderRows = placeholder.size,
        modelRejections = modelRejections,
        baselineRejections = baselineRejections,
    )
}

/**
 * `./gradlew :evals:scoreDeviceRun -Pfile=/path/to/results.jsonl`
 *
 * Paths are resolved against the `:evals` project directory, like `EvalMain`'s golden set.
 */
fun main(args: Array<String>) {
    val file = args.firstOrNull() ?: error(
        "usage: scoreDeviceRun <results.jsonl> [golden/candidates.json]",
    )
    val goldenPath = Path.of(args.getOrNull(1) ?: "golden/candidates.json")
    val rows = DeviceRunLoader.load(Path.of(file))
    val report = scoreDeviceRun(rows, GoldenCaseLoader.load(goldenPath)).copy(file = file)
    print(report.render())
}
