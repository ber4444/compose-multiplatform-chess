package com.example.myapplication.bench

import android.content.Context
import com.example.ondeviceai.DefaultGameSummaryOrchestrator
import com.example.ondeviceai.GameSummaryResult
import com.example.ondeviceai.VendorRouteExecutor
import java.io.File

/**
 * Benchmarks **Game Summary**, which the coach benchmark does not cover and which has a different
 * contract: a deliberate button press with a spinner at game end, not an automatic per-move panel.
 * A wait that disqualifies the coach may be perfectly acceptable here, so it gets measured on its
 * own terms rather than inheriting the coach's verdict.
 *
 * The bar is set in the plan before the numbers are seen: ~15 s, and **truth** — this surface has no
 * response validator at all, so an invented claim reaches the user unchallenged.
 */
suspend fun runAndroidSummaryBench(context: Context, iterations: Int) {
    val resultsFile = File(context.filesDir, "bench/summary.jsonl")
    resultsFile.parentFile?.mkdirs()
    resultsFile.writeText("")

    // Same filesDir-first path as the coach bench, and the same reason: a run can be pointed at an
    // ad-hoc fixture set by pushing one.
    val fixturesFile = File(context.filesDir, "golden/summary-fixtures.json")
    val fixturesJson = when {
        fixturesFile.exists() -> fixturesFile.readText()
        else -> runCatching { context.assets.open("golden/summary-fixtures.json").bufferedReader().use { it.readText() } }
            .getOrNull()
    }
    val fixtures = SummaryFixtures.load(fixturesJson)
    if (fixtures.isEmpty()) {
        android.util.Log.e("AndroidSummaryBench", "no summary fixtures in filesDir or assets — nothing to measure")
        return
    }

    val executor = VendorRouteExecutor()
    // Warm the model first so the measurement is generation, not download.
    val route = com.example.ondeviceai.probeAvailableLocalVendors().firstOrNull()
    val generator = route?.let { executor.execute(it) }
    generator?.warmup()

    val orchestrator = DefaultGameSummaryOrchestrator(
        executor = executor,
        contextProvider = {
            com.example.ondeviceai.AiContextSnapshot(
                availableLocalVendors = com.example.ondeviceai.probeAvailableLocalVendors(),
                isAppForegrounded = true,
                userSetting = com.example.ondeviceai.AiUserSetting.OFFLINE_ONLY,
            )
        },
    )

    val deviceModel = android.os.Build.MODEL
    val osVersion = android.os.Build.VERSION.RELEASE
    val modelIdentifier = when (val r = route) {
        is com.example.ondeviceai.VendorRoute.MlKitPrompt -> "mlkit-aicore-${r.preference.name.lowercase()}"
        null -> "none"
        else -> r::class.simpleName ?: "unknown"
    }

    for (fixture in (0 until iterations).flatMap { fixtures }) {
        val start = System.currentTimeMillis()
        val result = orchestrator.summarizeGame(fixture.request)
        val elapsed = System.currentTimeMillis() - start

        val text = when (result) {
            is GameSummaryResult.Success -> result.explanation.explanation
            is GameSummaryResult.FellBack -> result.text
            is GameSummaryResult.Failed -> result.message
        }
        resultsFile.appendText(
            SummaryFixtures.jsonLine(
                fixture = fixture,
                modelIdentifier = modelIdentifier,
                deviceModel = deviceModel,
                osVersion = osVersion,
                kind = result::class.simpleName ?: "unknown",
                elapsedMs = elapsed,
                text = text,
            ) + "\n",
        )
    }
}

