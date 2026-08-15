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
    
    val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager

    // Stockfish is vendored on device, so the bench can assess each golden position with the same
    // engine the app uses. Null when it can't start — the run then degrades to unassessed fixtures
    // and says so per row rather than silently producing prompt-starved results.
    val assessmentEngine = createBenchEngine(context)

    val goldenCasesFile = File(context.filesDir, "golden/candidates.json")
    val goldenCasesList = mutableListOf<GoldenCaseFixture>()
    val assessmentGaps = mutableListOf<String>()
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
                val fen = if (obj.has("fen")) obj.getString("fen") else null

                // Assess the ply with the on-device engine and build the request the way the app
                // does. Without this the prompt carries no engine assessment, no motifs and a
                // placeholder baseline, and the run measures the harness rather than the model —
                // see GoldenFixtureAssessor for the prompt that produced every earlier quality
                // verdict on this page.
                val assessed = if (fen != null && assessmentEngine != null) {
                    runCatching {
                        assessGoldenCase(
                            engine = assessmentEngine,
                            fen = fen,
                            playedUci = bestMoveUci,
                            playedSan = moveDisplay,
                            thinkTimeMs = EngineDifficulty.HARD.thinkTimeMs,
                        )
                    }.onFailure {
                        android.util.Log.w("AndroidBenchRunner", "assessment failed for $id", it)
                    }.getOrNull()
                } else {
                    null
                }
                if (assessed == null) assessmentGaps += id

                goldenCasesList += GoldenCaseFixture(
                    id = id,
                    tags = tags,
                    // The unassessed shape is kept as a degraded path rather than a hard failure so
                    // a device with no working Stockfish can still produce latency numbers. It is
                    // recorded per row as `factsPopulated:false`, because the one thing that must
                    // never happen again is a placeholder run being read as a quality measurement.
                    request = assessed ?: MoveCoachRequest(
                        moveUci = bestMoveUci,
                        moveDisplay = moveDisplay,
                        deterministicHeadline = "You played $moveDisplay.",
                        deterministicExplanation = "This was a strong move.",
                        engineDifficultyName = "Hard"
                    ),
                    factsPopulated = assessed != null,
                )
            }
        } catch (t: Throwable) {
            // Don't swallow: a malformed golden file silently degrades the whole run to the two
            // fallback fixtures, and the resulting rows look like real data. isFallbackGolden marks
            // them in the JSONL, but the *reason* only exists here.
            android.util.Log.e("AndroidBenchRunner", "Failed to parse ${goldenCasesFile.path}; falling back to built-in fixtures", t)
            goldenCasesList.clear()
        }
    }

    var isFallbackGoldenRun = false
    if (goldenCasesList.isEmpty()) {
        isFallbackGoldenRun = true
        goldenCasesList += listOf(
            GoldenCaseFixture("fallback-opening-001", listOf("opening", "develops"), MoveCoachRequest("g1h3", "Nh3", "You played Nh3.", "Develops knight.", "Hard"), isFallbackGolden = true),
            GoldenCaseFixture("fallback-opening-002", listOf("opening", "pawn-push"), MoveCoachRequest("f2f4", "f4", "You played f4.", "Attacks center.", "Hard"), isFallbackGolden = true),
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
        
        val isWarm = (i > 0)
        
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

        val reasonJson = jsonStringOrNull(result.fallbackReason)
        val rawOutputJson = jsonStringOrNull(result.rawOutput)
        val tagsJson = fixture.tags.joinToString(",", "[", "]") { jsonStringOrNull(it) }
        val jsonLine = """{"caseId":"${fixture.id}","isFallbackGolden":${fixture.isFallbackGolden},"factsPopulated":${fixture.factsPopulated},"tags":$tagsJson,"deviceModel":"${result.deviceModel}","osVersion":"${result.osVersion}","appVersion":"${result.appVersion}","modelIdentifier":"${result.modelIdentifier}","isWarm":${result.isWarm},"timestampMs":${result.timestampMs},"initStartMs":${result.initStartMs},"initEndMs":${result.initEndMs},"generateStartMs":${result.generateStartMs},"firstTokenMs":${result.firstTokenMs},"completeMs":${result.completeMs},"tokenCount":${result.tokenCount},"peakMemoryBytes":${result.peakMemoryBytes},"thermalStatusBefore":${result.thermalStatusBefore},"thermalStatusAfter":${result.thermalStatusAfter},"fallbackTriggered":${result.fallbackTriggered},"isEmulator":${result.isEmulator},"fallbackReason":$reasonJson,"rawOutput":$rawOutputJson}"""
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
    val isFallbackGolden: Boolean = false,
    /**
     * False when the request carries no engine assessment — placeholder baseline, no
     * `moveClassName`, no motifs. Emitted per JSONL row as `factsPopulated`. Rows with this false
     * measure the harness, not the model, and must not be scored for quality; see
     * [assessGoldenCase].
     */
    val factsPopulated: Boolean = false,
)

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
