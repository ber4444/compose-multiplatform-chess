package com.example.myapplication.bench

import com.example.ondeviceai.AiCoachOrchestrator
import com.example.ondeviceai.DefaultAiCoachOrchestrator
import com.example.ondeviceai.MoveCoachRequest
import com.example.ondeviceai.bench.BenchProbe
import com.example.ondeviceai.VendorRouteExecutor
import com.example.ondeviceai.AiRoute
import kotlinx.coroutines.flow.collect
import platform.Foundation.NSBundle
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSProcessInfo
import platform.Foundation.NSString
import platform.Foundation.NSURL
import platform.posix.fopen
import platform.posix.fputs
import platform.posix.fclose
import platform.posix.exit
import platform.posix.FILE
import kotlinx.cinterop.ExperimentalForeignApi
import com.example.myapplication.persistence.nowEpochMillis

suspend fun runIosBench(iterations: Int) {
    val fileManager = NSFileManager.defaultManager
    val documentDirectory = fileManager.URLsForDirectory(NSDocumentDirectory, inDomains = 1uL).first() as NSURL
    val resultsFile = documentDirectory.URLByAppendingPathComponent("bench_results.jsonl")!!.path!!
    
    val request = MoveCoachRequest(
        fenBefore = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1",
        bestMoveUci = "e2e4",
        bestMoveDisplay = "e4",
        sideToMove = "White",
        evaluationBeforeCp = 20,
        evaluationAfterCp = 35,
        deterministicTags = listOf("Opening"),
        engineDifficultyName = "Hard"
    )

    val processInfo = NSProcessInfo.processInfo
    
    // Fallbacks since iOS doesn't easily expose exact device hardware strings in pure Foundation
    // Usually people use sysctlbyname("hw.machine"), we can just use processInfo.environment for bench
    val isEmulator = processInfo.environment["SIMULATOR_DEVICE_NAME"] != null || processInfo.environment["SIMULATOR_UDID"] != null
    val deviceModel = processInfo.environment["SIMULATOR_DEVICE_NAME"] as? String ?: "iPhone_Physical"
    val osVersion = processInfo.operatingSystemVersionString
    val appVersion = NSBundle.mainBundle.infoDictionary?.get("CFBundleShortVersionString") as? String ?: "unknown"

    for (i in 0 until iterations) {
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
            override fun onFallback(reason: String) { fallback = true; fallbackReason = reason }
            override fun onRawOutput(text: String) { rawOutput = text }
        }
        
        probe.onInitStart()
        val executor = VendorRouteExecutor()
        val policy = com.example.ondeviceai.AiRoutePolicies.moveCoachOffline
        val context = com.example.ondeviceai.AiContextSnapshot(
            isDeviceModelAvailable = true,
            isAppForegrounded = true,
            userSetting = com.example.ondeviceai.AiUserSetting.OFFLINE_ONLY
        )
        val generator = executor.execute(policy, context)
        generator?.warmup()
        probe.onInitEnd()
        
        val orchestrator = DefaultAiCoachOrchestrator(
            executor = executor,
            // Without this the orchestrator's built-in default (isDeviceModelAvailable = false)
            // applies, and resolveVendorRoute short-circuits to null before ever checking
            // SystemLanguageModel availability — every prior bench run reported "no local model",
            // not a real Foundation Models availability check. Mirrors AndroidBenchRunner's fix.
            contextProvider = { com.example.ondeviceai.AiContextSnapshot(isDeviceModelAvailable = true) },
            benchProbe = probe
        )
        
        orchestrator.explainMoveStreaming(request).collect()
        
        val peakMem = getMemoryBytes()
        val isWarm = (i > 0)
        
        val result = BenchResult(
            deviceModel = deviceModel,
            osVersion = osVersion,
            appVersion = appVersion,
            modelIdentifier = "FoundationModels",
            isWarm = isWarm,
            timestampMs = nowEpochMillis(),
            initStartMs = initStart,
            initEndMs = initEnd,
            generateStartMs = genStart,
            firstTokenMs = firstToken,
            completeMs = completeMs,
            tokenCount = tokens,
            peakMemoryBytes = peakMem,
            thermalStatusBefore = 0, // not collected on iOS via standard API simply
            thermalStatusAfter = 0,
            fallbackTriggered = fallback,
            isEmulator = isEmulator,
            fallbackReason = fallbackReason,
            rawOutput = rawOutput
        )

        val reasonJson = jsonStringOrNull(result.fallbackReason)
        val rawOutputJson = jsonStringOrNull(result.rawOutput)
        val jsonLine = """{"deviceModel":"${result.deviceModel}","osVersion":"${result.osVersion}","appVersion":"${result.appVersion}","modelIdentifier":"${result.modelIdentifier}","isWarm":${result.isWarm},"timestampMs":${result.timestampMs},"initStartMs":${result.initStartMs},"initEndMs":${result.initEndMs},"generateStartMs":${result.generateStartMs},"firstTokenMs":${result.firstTokenMs},"completeMs":${result.completeMs},"tokenCount":${result.tokenCount},"peakMemoryBytes":${result.peakMemoryBytes},"thermalStatusBefore":${result.thermalStatusBefore},"thermalStatusAfter":${result.thermalStatusAfter},"fallbackTriggered":${result.fallbackTriggered},"isEmulator":${result.isEmulator},"fallbackReason":$reasonJson,"rawOutput":$rawOutputJson}"""

        appendToFile(resultsFile, jsonLine + "\n")
    }
}



private fun getMemoryBytes(): Long {
    // Memory is tracked via XCTMemoryMetric in XCTest, or we return 0
    return 0L
}

private fun jsonStringOrNull(s: String?): String {
    if (s == null) return "null"
    val escaped = s.replace("\\", "\\\\").replace("\"", "\\\"")
        .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t")
    return "\"$escaped\""
}

@OptIn(ExperimentalForeignApi::class)
private fun appendToFile(path: String, text: String) {
    val file = fopen(path, "a")
    if (file != null) {
        fputs(text, file)
        fclose(file)
    }
}
