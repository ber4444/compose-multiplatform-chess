package com.example.myapplication.bench

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Debug
import android.os.PowerManager
import com.example.ondeviceai.AiCoachOrchestrator
import com.example.ondeviceai.DefaultAiCoachOrchestrator
import com.example.ondeviceai.MoveCoachRequest
import com.example.ondeviceai.bench.BenchProbe
import com.example.ondeviceai.defaultOnDeviceTextGeneratorFactory
import kotlinx.coroutines.flow.collect
import java.io.File



suspend fun runAndroidBench(context: Context, iterations: Int) {
    val resultsFile = File(context.filesDir, "bench/results.jsonl")
    resultsFile.parentFile?.mkdirs()
    
    val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager

    // We use a fixed prompt request
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

    val deviceModel = Build.MODEL
    val osVersion = Build.VERSION.RELEASE
    val appVersion = context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown"
    val isEmulator = Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("Emulator")

    for (i in 0 until iterations) {
        val thermalBefore = pm.currentThermalStatus
        var initStart = 0L
        var initEnd = 0L
        var genStart = 0L
        var firstToken = 0L
        var completeMs = 0L
        var fallback = false
        var tokens = 0
        
        val probe = object : BenchProbe {
            override fun onInitStart() { initStart = System.currentTimeMillis() }
            override fun onInitEnd() { initEnd = System.currentTimeMillis() }
            override fun onGenerateStart() { genStart = System.currentTimeMillis() }
            override fun onFirstToken() { firstToken = System.currentTimeMillis() }
            override fun onGenerateComplete(tokenCount: Int) { completeMs = System.currentTimeMillis(); tokens = tokenCount }
            override fun onFallback(reason: String) { fallback = true }
        }
        
        probe.onInitStart()
        val factory = defaultOnDeviceTextGeneratorFactory()
        val generator = factory.create()
        generator?.warmup()
        probe.onInitEnd()
        
        val orchestrator = DefaultAiCoachOrchestrator(
            factory = factory,
            benchProbe = probe
        )
        
        orchestrator.explainMoveStreaming(request).collect() // exhaust flow
        
        generator?.close()
        
        val thermalAfter = pm.currentThermalStatus
        val pmi = android.os.Debug.MemoryInfo()
        Debug.getMemoryInfo(pmi)
        val peakMem = Debug.getNativeHeapAllocatedSize() + (pmi.totalPss * 1024L) // crude approx or just native
        
        val isWarm = (i > 0) // The plan says "relaunch per iteration via run_android.sh for cold init". But if iterations > 1, 2nd is warm. 
        
        val result = BenchResult(
            deviceModel = deviceModel,
            osVersion = osVersion,
            appVersion = appVersion,
            modelIdentifier = "gemma3-270m",
            isWarm = isWarm,
            timestampMs = System.currentTimeMillis(),
            initStartMs = initStart,
            initEndMs = initEnd,
            generateStartMs = genStart,
            firstTokenMs = firstToken,
            completeMs = completeMs,
            tokenCount = tokens,
            peakMemoryBytes = peakMem,
            thermalStatusBefore = thermalBefore,
            thermalStatusAfter = thermalAfter,
            fallbackTriggered = fallback,
            isEmulator = isEmulator
        )
        
        val jsonLine = """{"deviceModel":"${result.deviceModel}","osVersion":"${result.osVersion}","appVersion":"${result.appVersion}","modelIdentifier":"${result.modelIdentifier}","isWarm":${result.isWarm},"timestampMs":${result.timestampMs},"initStartMs":${result.initStartMs},"initEndMs":${result.initEndMs},"generateStartMs":${result.generateStartMs},"firstTokenMs":${result.firstTokenMs},"completeMs":${result.completeMs},"tokenCount":${result.tokenCount},"peakMemoryBytes":${result.peakMemoryBytes},"thermalStatusBefore":${result.thermalStatusBefore},"thermalStatusAfter":${result.thermalStatusAfter},"fallbackTriggered":${result.fallbackTriggered},"isEmulator":${result.isEmulator}}"""
        resultsFile.appendText(jsonLine + "\n")
    }
}
