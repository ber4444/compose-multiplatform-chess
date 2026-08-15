package com.example.myapplication.bench

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Debug
import android.os.PowerManager
import com.example.myapplication.EngineDifficulty
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
    // Truncate: the file used to accumulate across launches, so a re-run left the previous run's
    // rows above the new ones and the reader had to date-sort a file that looks homogeneous.
    resultsFile.writeText("")
    
    val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager

    // Stockfish is vendored on device, so the bench can assess each golden position with the same
    // engine the app uses. Null when it can't start — the run then degrades to unassessed fixtures
    // and says so per row rather than silently producing prompt-starved results.
    val assessmentEngine = createBenchEngine(context)

    // filesDir first so a run can be pointed at an ad-hoc golden file by pushing one; otherwise the
    // copy staged into debug assets by `:androidApp`'s stageGoldenBenchAssets. Reading only filesDir
    // is what produced the 2026-08-15 run: nothing had written it, so every row came back
    // `isFallbackGolden` and looked like data.
    val goldenCasesFile = File(context.filesDir, "golden/candidates.json")
    val goldenCasesJson: String? = when {
        goldenCasesFile.exists() -> goldenCasesFile.readText()
        else -> runCatching { context.assets.open("golden/candidates.json").bufferedReader().use { it.readText() } }
            .onFailure { android.util.Log.e("AndroidBenchRunner", "no golden set in filesDir or assets", it) }
            .getOrNull()
    }
    // Parsed and assessed by the shared loader, so this platform and iOS build the same prompt from
    // the same facts — the only way their numbers can be compared.
    val loaded = GoldenFixtures.load(goldenCasesJson, assessmentEngine, EngineDifficulty.HARD.thinkTimeMs)
    val goldenCasesList = loaded.fixtures
    val assessmentGaps = loaded.unassessed

    val deviceModel = Build.MODEL
    val osVersion = Build.VERSION.RELEASE
    val appVersion = context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown"
    val isEmulator = Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("Emulator")

    // Every fixture, `iterations` times over — not `iterations` rows cycling through the set. The
    // old `goldenCasesList[i % size]` meant a 100-case golden set at `iterations = 3` measured three
    // cases, which is not a golden-set run however the rows are labelled.
    val runPlan = (0 until iterations).flatMap { goldenCasesList }
    for ((runIndex, fixture) in runPlan.withIndex()) {
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
            // Store the description, not the sealed object: the JSONL line below is consumed by
            // docs/benchmarks/on-device-ai/, and description preserves the exact prior strings.
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
        val route = (decision as? com.example.ondeviceai.AiRoutePolicyDecider.Decision.RunOnDevice)
            ?.route ?: return
        val generator = executor.execute(route) ?: return
        // A bench reports a terminal state, so it must await warmup rather than fire and forget —
        // generation that starts while the model is still loading reports "no local model" on every
        // row, which looks like data. Only Cactus ever exposed an awaitable warmup; with it gone the
        // remaining routes initialise synchronously inside warmup().
        generator.warmup()
        probe.onInitEnd()
        
        val orchestrator = DefaultAiCoachOrchestrator(
            executor = executor,
            contextProvider = { com.example.ondeviceai.AiContextSnapshot(availableLocalVendors = com.example.ondeviceai.probeAvailableLocalVendors()) },
            benchProbe = probe
        )
        
        orchestrator.explainMoveStreaming(request).collect() // exhaust flow
        
        generator.release()
        
        val thermalAfter = pm.currentThermalStatus
        val pmi = android.os.Debug.MemoryInfo()
        Debug.getMemoryInfo(pmi)
        val peakMem = Debug.getNativeHeapAllocatedSize() + (pmi.totalPss * 1024L)
        
        val isWarm = (runIndex > 0)
        
        val result = BenchResult(
            deviceModel = deviceModel,
            osVersion = osVersion,
            appVersion = appVersion,
            // Derived from the route that actually ran, never restated. This was hardcoded to
            // `gemma3-270m`, then briefly to whatever Cactus's default was — both wrong the moment
            // the decider picks ML Kit, which serves AICore's own model and has no Cactus slug at
            // all. The one field a benchmark must get right: the file is worthless if you cannot
            // tell what produced it.
            modelIdentifier = when (route) {
                is VendorRoute.MlKitPrompt -> "mlkit-aicore-${route.preference.name.lowercase()}"
                else -> route::class.simpleName ?: "unknown"
            },
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

        val jsonLine = GoldenFixtures.jsonLine(fixture, result)
        resultsFile.appendText(jsonLine + "\n")
    }

    // Loud, because a silent partial assessment is exactly how the previous placeholder run passed
    // for a measurement. If this line reports anything but 0, the affected rows are latency-only.
    if (assessmentGaps.isNotEmpty()) {
        android.util.Log.w(
            "AndroidBenchRunner",
            "${assessmentGaps.size}/${goldenCasesList.size} golden cases carry NO assessment facts " +
                "(factsPopulated=false) — do not score these for quality: " +
                assessmentGaps.take(10).joinToString(", ") + (if (assessmentGaps.size > 10) ", …" else ""),
        )
    } else {
        android.util.Log.i(
            "AndroidBenchRunner",
            "all ${goldenCasesList.size} golden cases assessed; prompts carry engine facts",
        )
    }
    assessmentEngine?.close()
}


/**
 * The engine the bench uses to assess golden positions. Separate from the app's — the bench branch
 * in `MainActivity` returns before the normal engine wiring, and this one is closed when the run
 * ends rather than living for the session.
 */
private fun createBenchEngine(context: Context): com.example.myapplication.ChessEngine? {
    val engine = com.example.myapplication.StockfishEngine(
        nativeLibraryDir = context.applicationInfo.nativeLibraryDir,
        filesDir = context.filesDir,
        assetManager = context.assets,
        supportedAbis = Build.SUPPORTED_ABIS,
    )
    return if (engine.isAvailable() && engine.start()) {
        engine
    } else {
        android.util.Log.w(
            "AndroidBenchRunner",
            "Stockfish unavailable; golden fixtures will carry no assessment facts",
        )
        null
    }
}
