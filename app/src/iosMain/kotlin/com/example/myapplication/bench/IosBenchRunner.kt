package com.example.myapplication.bench

import com.example.myapplication.ChessEngine
import com.example.myapplication.EngineDifficulty
import com.example.ondeviceai.DefaultAiCoachOrchestrator
import com.example.ondeviceai.bench.BenchProbe
import com.example.ondeviceai.VendorRouteExecutor
import kotlinx.coroutines.flow.collect
import platform.Foundation.NSBundle
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSProcessInfo
import platform.Foundation.NSString
import platform.Foundation.NSURL
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.stringWithContentsOfFile
import platform.posix.fopen
import platform.posix.fputs
import platform.posix.fclose
import kotlinx.cinterop.ExperimentalForeignApi
import com.example.myapplication.persistence.nowEpochMillis

/**
 * The iOS half of the on-device Move Coach benchmark.
 *
 * **It used to build one hardcoded request** — `e4`, `"This controls the center."`, no
 * `moveClassName`, no motifs, no better move — and repeat it `iterations` times. That is the exact
 * shape that made every Android quality verdict before 2026-08-15 meaningless: `MoveCoachPromptBuilder`
 * emits a fact line only when its field is set, so the model was asked to be specific about a
 * position it had been told nothing about, and forbidden from inventing. Comparing Foundation Models
 * against ML Kit through it would have compared two harnesses.
 *
 * Now it loads the same golden set, assesses each case with the same on-device engine through the
 * same [GoldenFixtures] loader, and emits the same JSONL columns, so
 * `./gradlew :evals:scoreDeviceRun` and ferryman's scripts read an iOS run exactly as they read an
 * Android one.
 *
 * The golden file is read from the app's Documents directory rather than the bundle, mirroring
 * Android's filesDir-first path: on the simulator it is pushed in with `simctl`, and no Xcode
 * project or resource plumbing changes. A device run without one degrades to the two marked
 * fallback fixtures rather than failing.
 */
@OptIn(ExperimentalForeignApi::class)
suspend fun runIosBench(engine: ChessEngine?, iterations: Int) {
    val fileManager = NSFileManager.defaultManager
    val documentDirectory = fileManager.URLsForDirectory(NSDocumentDirectory, inDomains = 1uL).first() as NSURL
    val resultsFile = documentDirectory.URLByAppendingPathComponent("bench_results.jsonl")!!.path!!
    val goldenFile = documentDirectory.URLByAppendingPathComponent("golden/candidates.json")!!.path!!

    // Truncate, for the reason the Android runner documents: appending across launches leaves the
    // previous run's rows above the new ones in a file that looks homogeneous.
    writeFile(resultsFile, "")

    val goldenJson = NSString.stringWithContentsOfFile(goldenFile, encoding = NSUTF8StringEncoding, error = null)
    val loaded = GoldenFixtures.load(goldenJson, engine, EngineDifficulty.HARD.thinkTimeMs)
    if (loaded.isFallback) {
        println("IosBenchRunner: no golden set at $goldenFile — running the two built-in fixtures; do not score these for quality")
    }

    val processInfo = NSProcessInfo.processInfo

    // Fallbacks since iOS doesn't easily expose exact device hardware strings in pure Foundation
    // Usually people use sysctlbyname("hw.machine"), we can just use processInfo.environment for bench
    val isEmulator = processInfo.environment["SIMULATOR_DEVICE_NAME"] != null || processInfo.environment["SIMULATOR_UDID"] != null
    val deviceModel = processInfo.environment["SIMULATOR_DEVICE_NAME"] as? String ?: "iPhone_Physical"
    val osVersion = processInfo.operatingSystemVersionString
    val appVersion = NSBundle.mainBundle.infoDictionary?.get("CFBundleShortVersionString") as? String ?: "unknown"

    val runPlan = (0 until iterations).flatMap { loaded.fixtures }
    for ((runIndex, fixture) in runPlan.withIndex()) {
        var initStart = 0L
        var initEnd = 0L
        var genStart = 0L
        var firstToken = 0L
        var completeMs = 0L
        var fallback = false
        var fallbackReason: String? = null
        var rawOutput: String? = null
        var tokens = 0

        val probe = object : BenchProbe {
            override fun onInitStart() { initStart = nowEpochMillis() }
            override fun onInitEnd() { initEnd = nowEpochMillis() }
            override fun onGenerateStart() { genStart = nowEpochMillis() }
            override fun onFirstToken() { firstToken = nowEpochMillis() }
            override fun onGenerateComplete(tokenCount: Int) { completeMs = nowEpochMillis(); tokens = tokenCount }
            // See AndroidBenchRunner — description keeps the emitted JSONL byte-identical.
            override fun onFallback(reason: com.example.ondeviceai.AiRoutePolicyDecider.FallbackReason) {
                fallback = true; fallbackReason = reason.description
            }
            override fun onRawOutput(text: String) { rawOutput = text }
        }

        probe.onInitStart()
        val executor = VendorRouteExecutor()
        val policy = com.example.ondeviceai.AiRoutePolicies.moveCoachOffline
        val context = com.example.ondeviceai.AiContextSnapshot(
            availableLocalVendors = com.example.ondeviceai.probeAvailableLocalVendors(),
            isAppForegrounded = true,
            userSetting = com.example.ondeviceai.AiUserSetting.OFFLINE_ONLY
        )
        val decision = com.example.ondeviceai.AiRoutePolicyDecider.decide(policy, context)
        val generator = (decision as? com.example.ondeviceai.AiRoutePolicyDecider.Decision.RunOnDevice)
            ?.let { executor.execute(it.route) }
        generator?.warmup()
        probe.onInitEnd()

        val orchestrator = DefaultAiCoachOrchestrator(
            executor = executor,
            // Without this the orchestrator's built-in default (isDeviceModelAvailable = false)
            // applies, and resolveVendorRoute short-circuits to null before ever checking
            // SystemLanguageModel availability — every prior bench run reported "no local model",
            // not a real Foundation Models availability check. Mirrors AndroidBenchRunner's fix.
            contextProvider = { com.example.ondeviceai.AiContextSnapshot(availableLocalVendors = com.example.ondeviceai.probeAvailableLocalVendors()) },
            benchProbe = probe
        )

        orchestrator.explainMoveStreaming(fixture.request).collect()

        val result = BenchResult(
            deviceModel = deviceModel,
            osVersion = osVersion,
            appVersion = appVersion,
            modelIdentifier = "FoundationModels",
            isWarm = (runIndex > 0),
            timestampMs = nowEpochMillis(),
            initStartMs = initStart,
            initEndMs = initEnd,
            generateStartMs = genStart,
            firstTokenMs = firstToken,
            completeMs = completeMs,
            tokenCount = tokens,
            peakMemoryBytes = getMemoryBytes(),
            thermalStatusBefore = 0, // not collected on iOS via standard API simply
            thermalStatusAfter = 0,
            fallbackTriggered = fallback,
            isEmulator = isEmulator,
            fallbackReason = fallbackReason,
            rawOutput = rawOutput
        )

        appendToFile(resultsFile, GoldenFixtures.jsonLine(fixture, result) + "\n")
    }

    // Loud, because a silent partial assessment is exactly how a placeholder run passes for a
    // measurement. If this reports anything but 0, the affected rows are latency-only.
    if (loaded.unassessed.isNotEmpty()) {
        println(
            "IosBenchRunner: ${loaded.unassessed.size}/${loaded.fixtures.size} golden cases carry NO " +
                "assessment facts (factsPopulated=false) — do not score these for quality: " +
                loaded.unassessed.take(10).joinToString(", "),
        )
    } else {
        println("IosBenchRunner: all ${loaded.fixtures.size} golden cases assessed; prompts carry engine facts")
    }
    engine?.close()
}

private fun getMemoryBytes(): Long {
    // Memory is tracked via XCTMemoryMetric in XCTest, or we return 0
    return 0L
}

@OptIn(ExperimentalForeignApi::class)
private fun appendToFile(path: String, text: String) {
    val file = fopen(path, "a")
    if (file != null) {
        fputs(text, file)
        fclose(file)
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun writeFile(path: String, text: String) {
    val file = fopen(path, "w")
    if (file != null) {
        fputs(text, file)
        fclose(file)
    }
}
