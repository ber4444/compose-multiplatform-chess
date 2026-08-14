package com.example.ondeviceai

import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.GenerativeModel
import com.google.mlkit.genai.prompt.ModelReleaseStage
import com.google.mlkit.genai.prompt.generationConfig
import com.google.mlkit.genai.prompt.modelConfig
import com.google.mlkit.genai.prompt.ModelPreference as MlKitModelPreference

/**
 * A one-off measurement rig, not a feature. Delete it once the answer is written down.
 *
 * PR #131 concluded that ML Kit / AICore is unusable on Android, and the evidence was a
 * `FEATURE_NOT_FOUND: Feature 645` from a Pixel 10 Pro XL. That measurement was taken through
 * exactly one client configuration — the one [MlKitPromptGenerator] builds:
 *
 * ```
 * modelConfig { releaseStage = ModelReleaseStage.STABLE; preference = ModelPreference.FAST }
 * ```
 *
 * Google's own quickstart never builds that. `OpenPromptActivity.initGenerator()` in
 * `googlesamples/mlkit@master:android/genai` is:
 *
 * ```
 * val generationConfig = generationConfig { }
 * generativeModel = Generation.getClient(generationConfig)
 * ```
 *
 * — an empty config. Grepping the whole sample for `modelConfig`, `ModelPreference`,
 * `ModelReleaseStage` or `releaseStage` returns zero hits. `STABLE` is the documented default, so
 * that half is a no-op; `preference = FAST` is not, because FAST and FULL select different base
 * models and therefore different AICore feature ids. The sample's own bug report for a
 * default-config client (googlesamples/mlkit#985) shows a *different* id — 636, not 645.
 *
 * So the question this rig answers is narrow and falsifiable: **does the default configuration
 * report AVAILABLE on a device where `preference = FAST` reports FEATURE_NOT_FOUND?** If it does,
 * the availability half of #131 is a client-config bug rather than a device verdict, and
 * [MlKitPromptGenerator] should drop its `modelConfig`. If every variant reports UNAVAILABLE, #131
 * stands on stronger evidence than it had.
 *
 * Each variant runs the sample's sequence verbatim, in the sample's order
 * (`BaseActivity.checkFeatureStatus` + `OpenPromptActivity.downloadFeature`):
 *
 *  1. build the client,
 *  2. `getBaseModelName()` — the sample shows this in its debug bar,
 *  3. `checkStatus()`,
 *  4. AVAILABLE → done. UNAVAILABLE → done. **Anything else** — DOWNLOADABLE *and* DOWNLOADING —
 *     → `download()`, collected to termination so `DownloadCompleted`/`DownloadFailed` is actually
 *     awaited rather than fired and forgotten,
 *  5. `checkStatus()` again, and `getTokenLimit()` if it came back AVAILABLE.
 *
 * Nothing here reads an [AiRoutePolicy] or touches [VendorRouteExecutor]: this measures the vendor
 * SDK, not our routing.
 */
enum class MlKitProbeVariant(val label: String) {
    /**
     * The Google sample, verbatim: `Generation.getClient(generationConfig { })`. No `modelConfig`,
     * so no `releaseStage` and no `preference` — AICore picks whatever the device is provisioned
     * for. This is the variant #131 never tried.
     */
    SAMPLE_DEFAULT("sample-default (no modelConfig)"),

    /**
     * What [MlKitPromptGenerator] ships today, and the only configuration #131 measured. Present so
     * the run is an A/B on one device in one session rather than a comparison across write-ups.
     */
    PREFERENCE_FAST("preference=FAST releaseStage=STABLE"),
    ;

    internal fun newClient(): GenerativeModel = when (this) {
        // Byte-for-byte the sample's initGenerator(). Do not "tidy" the empty lambda away — an
        // empty generationConfig is the measurement, and `Generation.getClient()` with no argument
        // is a different overload.
        SAMPLE_DEFAULT -> Generation.getClient(generationConfig { })

        PREFERENCE_FAST -> Generation.getClient(
            generationConfig {
                modelConfig = modelConfig {
                    releaseStage = ModelReleaseStage.STABLE
                    preference = MlKitModelPreference.FAST
                }
            },
        )
    }
}

/**
 * One variant's result. Every field is nullable-or-string rather than typed, because the point is
 * to render a report a human reads off logcat — a thrown `GenAiException` carrying
 * `[ErrorCode 606] FEATURE_NOT_FOUND: Feature 645` is the single most valuable thing this rig can
 * capture, and it must survive to the report intact.
 */
data class MlKitProbeReport(
    val variant: String,
    val baseModelName: String? = null,
    val baseModelError: String? = null,
    val initialStatus: String? = null,
    val downloadLog: List<String> = emptyList(),
    val finalStatus: String? = null,
    val tokenLimit: Int? = null,
    /** Set when the probe could not complete at all (e.g. `getClient` itself threw). */
    val failure: String? = null,
)

/**
 * Runs [variants] in order and returns one report each. Never throws: a variant that blows up is
 * reported, not propagated, so a failure in the first variant cannot hide the result of the second
 * — which is the entire reason for running them together.
 */
suspend fun runMlKitAvailabilityDiagnostic(
    variants: List<MlKitProbeVariant> = MlKitProbeVariant.entries.toList(),
): List<MlKitProbeReport> = variants.map { probeVariant(it) }

private suspend fun probeVariant(variant: MlKitProbeVariant): MlKitProbeReport {
    var model: GenerativeModel? = null
    try {
        model = variant.newClient()
    } catch (t: Throwable) {
        return MlKitProbeReport(variant = variant.label, failure = describe(t))
    }

    try {
        val baseModelName = runCatching { model.getBaseModelName() }
        val initialStatus = runCatching { model.checkStatus() }

        // The sample's branch, kept in the sample's shape: AVAILABLE runs, UNAVAILABLE errors out,
        // and *everything else* downloads. Collapsing "everything else" to DOWNLOADABLE alone is
        // the bug this rig exists to avoid repeating — a device mid-download reports DOWNLOADING,
        // and treating that as "no ML Kit" is how a provisioning delay gets recorded as a device
        // verdict.
        val shouldDownload = initialStatus.getOrNull()
            ?.let { it != FeatureStatus.AVAILABLE && it != FeatureStatus.UNAVAILABLE }
            ?: false

        val downloadLog = if (shouldDownload) awaitDownload(model) else emptyList()
        val finalStatus = if (shouldDownload) runCatching { model.checkStatus() } else initialStatus

        // Only meaningful once the feature is actually available, and it is the cheapest proof that
        // the model is reachable rather than merely reported present.
        val tokenLimit = if (finalStatus.getOrNull() == FeatureStatus.AVAILABLE) {
            runCatching { model.getTokenLimit() }.getOrNull()
        } else {
            null
        }

        return MlKitProbeReport(
            variant = variant.label,
            baseModelName = baseModelName.getOrNull(),
            baseModelError = baseModelName.exceptionOrNull()?.let(::describe),
            initialStatus = renderStatus(initialStatus),
            downloadLog = downloadLog,
            finalStatus = renderStatus(finalStatus),
            tokenLimit = tokenLimit,
        )
    } catch (t: Throwable) {
        return MlKitProbeReport(variant = variant.label, failure = describe(t))
    } finally {
        // The sample closes its client before re-initialising. probeAvailableLocalVendors() does
        // not, which leaks a GenerativeModel per call — and the bench calls it repeatedly inside
        // the window it is timing.
        runCatching { model.close() }
    }
}

/**
 * Collects `download()` to termination. `DownloadStatus.DownloadFailed` is an **emitted status, not
 * a thrown exception**, so a `runCatching` wrapped around the collect would report a failed
 * download as success — the log entry below is the only place that failure is visible.
 */
private suspend fun awaitDownload(model: GenerativeModel): List<String> {
    val log = mutableListOf<String>()
    try {
        model.download().collect { status ->
            log += when (status) {
                is DownloadStatus.DownloadStarted -> "started (${status.bytesToDownload} bytes)"
                is DownloadStatus.DownloadProgress -> "progress (${status.totalBytesDownloaded} bytes)"
                is DownloadStatus.DownloadFailed -> "FAILED: ${describe(status.e)}"
                is DownloadStatus.DownloadCompleted -> "completed"
                else -> "unknown status: $status"
            }
        }
        log += "flow terminated"
    } catch (t: Throwable) {
        log += "threw: ${describe(t)}"
    }
    return log
}

private fun renderStatus(result: Result<Int>): String =
    result.fold(::featureStatusName) { "threw: ${describe(it)}" }

private fun featureStatusName(status: Int): String = when (status) {
    FeatureStatus.AVAILABLE -> "AVAILABLE"
    FeatureStatus.DOWNLOADABLE -> "DOWNLOADABLE"
    FeatureStatus.DOWNLOADING -> "DOWNLOADING"
    FeatureStatus.UNAVAILABLE -> "UNAVAILABLE"
    else -> "UNKNOWN($status)"
}

/**
 * Keeps the exception type and the full message. `GenAiException`'s message is where the AICore
 * error code and feature id live (`[ErrorCode 606] FEATURE_NOT_FOUND: Feature 645`), and the
 * feature id is the number that distinguishes the variants — dropping it would leave the report
 * saying "unavailable" for both, which is exactly the ambiguity #131 already has.
 */
private fun describe(t: Throwable): String {
    val head = "${t::class.java.simpleName}: ${t.message}"
    val cause = t.cause
    return if (cause != null && cause !== t) "$head (cause: ${cause::class.java.simpleName}: ${cause.message})" else head
}

/** Renders [reports] as plain text, one block per variant. */
fun renderMlKitAvailabilityDiagnostic(reports: List<MlKitProbeReport>): String = buildString {
    appendLine("=== ML Kit GenAI Prompt availability diagnostic ===")
    appendLine("genai-prompt 1.0.0-beta4; sequence mirrors googlesamples/mlkit android/genai")
    reports.forEach { report ->
        appendLine()
        appendLine("--- variant: ${report.variant}")
        if (report.failure != null) {
            appendLine("    probe failed: ${report.failure}")
            return@forEach
        }
        appendLine("    baseModelName: ${report.baseModelName ?: "n/a (${report.baseModelError})"}")
        appendLine("    checkStatus:   ${report.initialStatus}")
        if (report.downloadLog.isNotEmpty()) {
            appendLine("    download():")
            report.downloadLog.forEach { appendLine("      - $it") }
            appendLine("    checkStatus after download: ${report.finalStatus}")
        }
        report.tokenLimit?.let { appendLine("    tokenLimit:    $it") }
        appendLine("    VERDICT: ${if (report.finalStatus == "AVAILABLE") "AVAILABLE" else "not available"}")
    }
}
