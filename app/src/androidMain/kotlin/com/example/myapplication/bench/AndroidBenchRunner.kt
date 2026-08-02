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
import com.example.ondeviceai.VendorRouteExecutor
import com.example.ondeviceai.VendorRoute
import kotlinx.coroutines.flow.collect
import java.io.File



suspend fun runAndroidBench(context: Context, iterations: Int) {
    val resultsFile = File(context.filesDir, "bench/results.jsonl")
    resultsFile.parentFile?.mkdirs()
    
    val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager

    val goldenCasesFile = File(context.filesDir, "golden/candidates.json")
    val goldenCasesList = mutableListOf<GoldenCaseFixture>()
    if (goldenCasesFile.exists()) {
        try {
            val jsonArray = org.json.JSONArray(goldenCasesFile.readText())
            for (j in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(j)
                val id = obj.getString("id")
                val tagsArr = obj.getJSONArray("tags")
                val tags = (0 until tagsArr.length()).map { tagsArr.getString(it) }
                val bestMoveUci = obj.getString("bestMoveUci")
                val sanArr = if (obj.has("movesSan")) obj.getJSONArray("movesSan") else null
                val moveDisplay = if (sanArr != null && sanArr.length() > 0) sanArr.getString(sanArr.length() - 1) else bestMoveUci
                goldenCasesList += GoldenCaseFixture(
                    id = id,
                    tags = tags,
                    request = MoveCoachRequest(
                        moveUci = bestMoveUci,
                        moveDisplay = moveDisplay,
                        deterministicHeadline = "You played $moveDisplay.",
                        deterministicExplanation = "This was a strong move.",
                        engineDifficultyName = "Hard"
                    )
                )
            }
        } catch (_: Exception) {}
    }

    if (goldenCasesList.isEmpty()) {
        goldenCasesList += listOf(
            GoldenCaseFixture("opening-001", listOf("opening", "develops"), MoveCoachRequest("g1h3", "Nh3", "You played Nh3.", "Develops knight.", "Hard")),
            GoldenCaseFixture("opening-002", listOf("opening", "pawn-push"), MoveCoachRequest("f2f4", "f4", "You played f4.", "Attacks center.", "Hard")),
        )
    }

    val deviceModel = Build.MODEL
    val osVersion = Build.VERSION.RELEASE
    val appVersion = context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown"
    val isEmulator = Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("Emulator")

    for (i in 0 until iterations) {
        val fixture = goldenCasesList[i % goldenCasesList.size]
        val request = fixture.request
        val thermalBefore = pm.currentThermalStatus
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
            override fun onInitStart() { initStart = System.currentTimeMillis() }
            override fun onInitEnd() { initEnd = System.currentTimeMillis() }
            override fun onGenerateStart() { genStart = System.currentTimeMillis() }
            override fun onFirstToken() { firstToken = System.currentTimeMillis() }
            override fun onGenerateComplete(tokenCount: Int) { completeMs = System.currentTimeMillis(); tokens = tokenCount }
            override fun onFallback(reason: String) { fallback = true; fallbackReason = reason }
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
        val route = (decision as? com.example.ondeviceai.AiRoutePolicyDecider.Decision.RunOnDevice)
            ?.route ?: return
        val generator = executor.execute(route) ?: return
        generator.warmup()
        probe.onInitEnd()
        
        val orchestrator = DefaultAiCoachOrchestrator(
            executor = executor,
            // The orchestrator's own built-in default reports isDeviceModelAvailable = false (a
            // conservative fallback for callers that don't supply one — see its DefaultContextProvider).
            // Every real entry point overrides this to true (MainActivity/Main.kt/AppRoot); without it
            // here the route decider always short-circuits to "no local model" before ever trying
            // CactusLocal, regardless of whether the generator above actually warmed up.
            contextProvider = { com.example.ondeviceai.AiContextSnapshot(availableLocalVendors = com.example.ondeviceai.probeAvailableLocalVendors()) },
            benchProbe = probe
        )
        
        orchestrator.explainMoveStreaming(request).collect() // exhaust flow
        
        generator.close()
        
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
            isEmulator = isEmulator,
            fallbackReason = fallbackReason,
            rawOutput = rawOutput
        )

        val reasonJson = jsonStringOrNull(result.fallbackReason)
        val rawOutputJson = jsonStringOrNull(result.rawOutput)
        val jsonLine = """{"deviceModel":"${result.deviceModel}","osVersion":"${result.osVersion}","appVersion":"${result.appVersion}","modelIdentifier":"${result.modelIdentifier}","isWarm":${result.isWarm},"timestampMs":${result.timestampMs},"initStartMs":${result.initStartMs},"initEndMs":${result.initEndMs},"generateStartMs":${result.generateStartMs},"firstTokenMs":${result.firstTokenMs},"completeMs":${result.completeMs},"tokenCount":${result.tokenCount},"peakMemoryBytes":${result.peakMemoryBytes},"thermalStatusBefore":${result.thermalStatusBefore},"thermalStatusAfter":${result.thermalStatusAfter},"fallbackTriggered":${result.fallbackTriggered},"isEmulator":${result.isEmulator},"fallbackReason":$reasonJson,"rawOutput":$rawOutputJson}"""
        resultsFile.appendText(jsonLine + "\n")
    }
}

private fun jsonStringOrNull(s: String?): String {
    if (s == null) return "null"
    val escaped = s.replace("\\", "\\\\").replace("\"", "\\\"")
        .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t")
    return "\"$escaped\""
}

data class GoldenCaseFixture(
    val id: String,
    val tags: List<String>,
    val request: MoveCoachRequest,
)
