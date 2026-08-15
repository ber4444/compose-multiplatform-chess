package com.example.myapplication.bench

import com.example.myapplication.ChessEngine
import com.example.ondeviceai.MoveCoachRequest
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * The golden set, parsed and assessed the same way on every platform.
 *
 * This is shared rather than per-platform because the two runners exist to be *compared*: an
 * Android number and an iOS number mean nothing against each other unless both models were handed
 * the same prompt built from the same facts. The Android runner had already learned this the
 * expensive way — every quality verdict on `docs/benchmarks/on-device-ai/` before 2026-08-15 was
 * measured through a hardcoded placeholder request — and iOS was still building one fixed request
 * with `deterministicExplanation = "This controls the center."` and no facts at all.
 */
@Serializable
private data class GoldenCaseJson(
    val id: String,
    val fen: String? = null,
    val bestMoveUci: String,
    val tags: List<String> = emptyList(),
    val movesSan: List<String> = emptyList(),
)

/**
 * A golden case whose facts were assessed elsewhere and shipped with it.
 *
 * **Why a run would want this.** Comparing two on-device models is only meaningful if both were
 * handed the same prompt, and an engine is a source of variance between platforms: a different
 * Stockfish build, a different think-time budget, or a search that lands one centipawn the other
 * side of a `MoveClass` threshold changes the facts and therefore the prompt. Assessing once and
 * replaying the same facts everywhere makes the comparison exact.
 *
 * It also takes the local engine off the critical path — which is what surfaced it: the iOS
 * Stockfish bridge finishes its UCI handshake on a detached task, so the bench's first searches
 * returned nothing and all 100 rows came back `factsPopulated:false`, the very shape this file
 * exists to prevent.
 *
 * The trade is explicit: rows built this way are still `factsPopulated`, because the prompt really
 * does carry engine facts — they were just not computed on *this* device.
 */
@Serializable
private data class PreAssessedCaseJson(
    val id: String,
    val moveUci: String,
    val moveDisplay: String,
    val deterministicHeadline: String,
    val deterministicExplanation: String,
    val tags: List<String> = emptyList(),
    val moveClassName: String? = null,
    val motifs: List<String> = emptyList(),
    val winPercentLost: Double? = null,
    val betterMoveDisplay: String? = null,
)

data class GoldenCaseFixture(
    val id: String,
    val tags: List<String>,
    val request: MoveCoachRequest,
    /** True for the built-in stand-ins used when no golden file is present. */
    val isFallbackGolden: Boolean = false,
    /**
     * False when the request carries no engine assessment — placeholder baseline, no
     * `moveClassName`, no motifs. Emitted per JSONL row as `factsPopulated`. Rows with this false
     * measure the harness, not the model, and must not be scored for quality; see
     * [assessGoldenCase].
     */
    val factsPopulated: Boolean = false,
)

data class GoldenFixtureLoad(
    val fixtures: List<GoldenCaseFixture>,
    /** Ids the engine could not assess. Non-empty means those rows are latency-only. */
    val unassessed: List<String>,
    val isFallback: Boolean,
)

object GoldenFixtures {

    // Lenient: the golden file gains columns (`eco`, `expectedConcepts`) for the eval harness that
    // the bench has no use for, and a new one must not stop a device run.
    private val json = Json { ignoreUnknownKeys = true }

    // Strict on purpose: this one is used to *detect* the pre-assessed shape, so a plain golden
    // case must fail to parse as it rather than being silently accepted with empty facts.
    private val strictJson = Json { ignoreUnknownKeys = false }

    /**
     * Two stand-ins so a device with no golden file still yields latency numbers. They are marked,
     * because rows that look like data and are not is the failure this whole path exists to avoid.
     */
    private val FALLBACK_FIXTURES = listOf(
        GoldenCaseFixture(
            "fallback-opening-001",
            listOf("opening", "develops"),
            MoveCoachRequest("g1h3", "Nh3", "You played Nh3.", "Develops knight.", "Hard"),
            isFallbackGolden = true,
        ),
        GoldenCaseFixture(
            "fallback-opening-002",
            listOf("opening", "pawn-push"),
            MoveCoachRequest("f2f4", "f4", "You played f4.", "Attacks center.", "Hard"),
            isFallbackGolden = true,
        ),
    )

    /**
     * Parses [text] and assesses every case with [engine], building each request the way the app
     * builds a real one.
     *
     * A null or unparseable [text], or a file that yields no cases, degrades to [FALLBACK_FIXTURES]
     * rather than failing — a device without a golden file can still produce latency — and says so
     * through [GoldenFixtureLoad.isFallback]. A null [engine] leaves every row unassessed, which is
     * reported the same way.
     */
    suspend fun load(text: String?, engine: ChessEngine?, thinkTimeMs: Long): GoldenFixtureLoad {
        // A pre-assessed file wins: it carries the facts already, so there is nothing to search for
        // and no engine variance between the platforms being compared.
        preAssessed(text)?.let { return it }

        val cases = text?.let {
            runCatching { json.decodeFromString(ListSerializer(GoldenCaseJson.serializer()), it) }
                .getOrNull()
        }
        if (cases.isNullOrEmpty()) return GoldenFixtureLoad(FALLBACK_FIXTURES, emptyList(), isFallback = true)

        val fixtures = mutableListOf<GoldenCaseFixture>()
        val unassessed = mutableListOf<String>()
        for (case in cases) {
            val display = case.movesSan.lastOrNull() ?: case.bestMoveUci
            val assessed = if (case.fen != null && engine != null) {
                runCatching {
                    assessGoldenCase(
                        engine = engine,
                        fen = case.fen,
                        playedUci = case.bestMoveUci,
                        playedSan = display,
                        thinkTimeMs = thinkTimeMs,
                    )
                }.getOrNull()
            } else {
                null
            }
            if (assessed == null) unassessed += case.id

            fixtures += GoldenCaseFixture(
                id = case.id,
                tags = case.tags,
                request = assessed ?: MoveCoachRequest(
                    moveUci = case.bestMoveUci,
                    moveDisplay = display,
                    deterministicHeadline = "You played $display.",
                    deterministicExplanation = "This was a strong move.",
                    engineDifficultyName = "Hard",
                ),
                factsPopulated = assessed != null,
            )
        }
        return GoldenFixtureLoad(fixtures, unassessed, isFallback = false)
    }

    /**
     * Fixtures from a file that already carries its facts, or null when [text] isn't one.
     *
     * Detection is by shape rather than a flag: the pre-assessed form requires
     * `deterministicExplanation`, which the plain golden set has no field for, so a strict parse
     * distinguishes them and a plain golden file simply falls through.
     */
    private fun preAssessed(text: String?): GoldenFixtureLoad? {
        val cases = text?.let {
            runCatching { strictJson.decodeFromString(ListSerializer(PreAssessedCaseJson.serializer()), it) }
                .getOrNull()
        } ?: return null
        if (cases.isEmpty()) return null
        return GoldenFixtureLoad(
            fixtures = cases.map { case ->
                GoldenCaseFixture(
                    id = case.id,
                    tags = case.tags,
                    request = MoveCoachRequest(
                        moveUci = case.moveUci,
                        moveDisplay = case.moveDisplay,
                        deterministicHeadline = case.deterministicHeadline,
                        deterministicExplanation = case.deterministicExplanation,
                        engineDifficultyName = "Hard",
                        moveClassName = case.moveClassName,
                        motifs = case.motifs,
                        winPercentLost = case.winPercentLost,
                        betterMoveDisplay = case.betterMoveDisplay,
                    ),
                    factsPopulated = true,
                )
            },
            unassessed = emptyList(),
            isFallback = false,
        )
    }

    /**
     * One JSONL row, in the shape `:evals:scoreDeviceRun` and ferryman's scripts both read.
     *
     * Shared so the two platforms cannot drift into emitting different columns, which would make
     * the comparison they exist for unreadable. The fact columns are what let a reader see the
     * prompt each answer came from, and let the deterministic baseline be scored beside the model.
     */
    fun jsonLine(fixture: GoldenCaseFixture, result: BenchResult): String {
        val request = fixture.request
        val tags = fixture.tags.joinToString(",", "[", "]") { jsonString(it) }
        val motifs = request.motifs.joinToString(",", "[", "]") { jsonString(it) }
        return """{"caseId":"${fixture.id}","isFallbackGolden":${fixture.isFallbackGolden},""" +
            """"factsPopulated":${fixture.factsPopulated},"tags":$tags,""" +
            """"moveDisplay":${jsonString(request.moveDisplay)},""" +
            """"deterministicExplanation":${jsonString(request.deterministicExplanation)},""" +
            """"moveClassName":${jsonStringOrNull(request.moveClassName)},"motifs":$motifs,""" +
            """"winPercentLost":${request.winPercentLost},""" +
            """"betterMoveDisplay":${jsonStringOrNull(request.betterMoveDisplay)},""" +
            """"deviceModel":${jsonString(result.deviceModel)},"osVersion":${jsonString(result.osVersion)},""" +
            """"appVersion":${jsonString(result.appVersion)},"modelIdentifier":${jsonString(result.modelIdentifier)},""" +
            """"isWarm":${result.isWarm},"timestampMs":${result.timestampMs},""" +
            """"initStartMs":${result.initStartMs},"initEndMs":${result.initEndMs},""" +
            """"generateStartMs":${result.generateStartMs},"firstTokenMs":${result.firstTokenMs},""" +
            """"completeMs":${result.completeMs},"tokenCount":${result.tokenCount},""" +
            """"peakMemoryBytes":${result.peakMemoryBytes},"thermalStatusBefore":${result.thermalStatusBefore},""" +
            """"thermalStatusAfter":${result.thermalStatusAfter},"fallbackTriggered":${result.fallbackTriggered},""" +
            """"isEmulator":${result.isEmulator},"fallbackReason":${jsonStringOrNull(result.fallbackReason)},""" +
            """"rawOutput":${jsonStringOrNull(result.rawOutput)}}"""
    }

    private fun jsonString(s: String): String =
        "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"")
            .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t") + "\""

    private fun jsonStringOrNull(s: String?): String = if (s == null) "null" else jsonString(s)
}
