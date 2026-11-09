package com.example.myapplication.bench

import com.example.ondeviceai.DefaultGameSummaryOrchestrator
import com.example.ondeviceai.GameSummaryResult
import com.example.ondeviceai.GameSummaryValidationSample
import com.example.ondeviceai.VendorRouteExecutor
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSProcessInfo
import platform.Foundation.NSString
import platform.Foundation.NSURL
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.stringWithContentsOfFile
import com.example.myapplication.persistence.nowEpochMillis

/**
 * The iOS half of the Game Summary benchmark — the counterpart to `runAndroidSummaryBench`, reading
 * the same fixtures through [SummaryFixtures] and emitting the same JSONL columns.
 *
 * Records the model text even when the response validator replaces it with the fallback.
 */
@OptIn(ExperimentalForeignApi::class)
suspend fun runIosSummaryBench(iterations: Int) {
    val fileManager = NSFileManager.defaultManager
    val documentDirectory = fileManager.URLsForDirectory(NSDocumentDirectory, inDomains = 1uL).first() as NSURL
    val resultsFile = documentDirectory.URLByAppendingPathComponent("summary_results.jsonl")!!.path!!
    val fixturesFile = documentDirectory.URLByAppendingPathComponent("golden/summary-fixtures.json")!!.path!!
    writeFile(resultsFile, "")

    val fixturesJson = NSString.stringWithContentsOfFile(fixturesFile, encoding = NSUTF8StringEncoding, error = null)
    val fixtures = SummaryFixtures.load(fixturesJson)
    if (fixtures.isEmpty()) {
        println("IosSummaryBench: no fixtures at $fixturesFile — nothing to measure")
        return
    }

    val executor = VendorRouteExecutor()
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

    val processInfo = NSProcessInfo.processInfo
    val deviceModel = processInfo.environment["SIMULATOR_DEVICE_NAME"] as? String ?: "iPhone_Physical"
    val osVersion = processInfo.operatingSystemVersionString

    for (fixture in (0 until iterations).flatMap { fixtures }) {
        val start = nowEpochMillis()
        var validation: GameSummaryValidationSample? = null
        val result = orchestrator.summarizeGame(fixture.request) { validation = it }
        val elapsed = nowEpochMillis() - start

        val text = when (result) {
            is GameSummaryResult.Success -> result.explanation.explanation
            is GameSummaryResult.FellBack -> result.text
            is GameSummaryResult.Failed -> result.message
        }
        appendToFile(
            resultsFile,
            SummaryFixtures.jsonLine(
                fixture = fixture,
                modelIdentifier = if (route != null) "FoundationModels" else "none",
                deviceModel = deviceModel,
                osVersion = osVersion,
                kind = result::class.simpleName ?: "unknown",
                elapsedMs = elapsed,
                text = text,
                fallbackReason = (result as? GameSummaryResult.FellBack)?.reason?.description,
                validation = validation,
            ) + "\n",
        )
    }
    println("IosSummaryBench: wrote ${fixtures.size * iterations} rows to $resultsFile")
}
