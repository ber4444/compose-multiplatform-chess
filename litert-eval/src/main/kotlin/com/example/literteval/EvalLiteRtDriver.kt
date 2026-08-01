package com.example.literteval

import com.example.myapplication.MoveAssessment
import com.example.myapplication.MoveClass
import com.example.myapplication.MoveRecord
import com.example.myapplication.movecoach.DeterministicCoach
import com.example.ondeviceai.AiAvailability
import com.example.ondeviceai.AiTokenOrFinal
import com.example.ondeviceai.MoveCoachPromptBuilder
import com.example.ondeviceai.MoveCoachRequest
import com.example.ondeviceai.litertlm.LitertLmModelStore
import com.example.ondeviceai.litertlm.LitertLmTextGenerator
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import kotlin.system.exitProcess

/**
 * Standalone driver that runs the LiteRT-LM on-device generator over the golden
 * set and emits raw outputs as JSON, for scoring by ferryman's
 * `score_reason_faithfulness` scorer.
 *
 * Lives in its own Gradle module (`:litert-eval`) deliberately: this module
 * depends on `:onDeviceAi` + `:coachapi` only — **not** `:server` — so its
 * dependency graph stays free of Ktor, and the kotlinx-coroutines version here
 * is controlled solely by the `resolutionStrategy.force` in
 * `litert-eval/build.gradle.kts`. That force is the actual reason this module
 * exists: litertlm-jvm 0.14.0 is an internally-inconsistent artifact whose
 * bytecode calls `SendChannel.close$default` (a static bridge that only exists
 * in coroutines 1.11.0+) while its POM declares 1.9.0. The rest of the app
 * resolves to 1.10.2 (via Ktor 3.4.3) or 1.9.0 (catalog) — neither provides
 * the method, so litertlm crashes the moment a generation completes. Forcing
 * 1.11.0 here is the only version that satisfies litertlm's bytecode.
 *
 * Why a separate driver (not a route in EvalMain): [EvalMain] drives
 * `FakeTextGenerator` + the opening-explainer HTTP routes, scored by the chess
 * app's own EvalScorer (grounding + length only — the weak check). This
 * driver's purpose is different: produce the 0.6B model's *actual* paraphrases
 * under the Move Coach prompt contract, so an external scorer (ferryman's
 * reason-faithfulness check) can measure whether those paraphrases stay faithful
 * to the supplied deterministic tags. Generation and scoring are deliberately
 * decoupled — two repos, two languages, one JSON contract between them.
 *
 * Usage:
 *   ./gradlew :litert-eval:run --args="[caseCount] [outputPath]"
 *   # defaults: 10 cases, writes ./litert-outputs.json
 *
 * The driver downloads the ~347MB Qwen3-0.6B-int4 model on first run
 * ([LitertLmModelStore]); subsequent runs load from disk (~1-2s). On unsupported
 * hosts (Intel Mac — no darwin-x86_64 native lib) it exits with a clear message
 * rather than emitting empty outputs.
 *
 * Run from the repository root so `golden/candidates.json` resolves (the
 * `application` plugin's `run` task sets the working dir to this module's dir,
 * so the default path below points one level up).
 */
fun main(args: Array<String>) {
    val caseCount = args.getOrNull(0)?.toIntOrNull() ?: 10
    val outputPath = Path.of(args.getOrNull(1) ?: "litert-outputs.json")

    // Load cases directly from the golden set (mirrors GoldenCase's shape) —
    // depending on :evals would pull Ktor via :server, defeating the isolation.
    val cases = loadCandidates(Path.of("../evals/golden/candidates.json")).take(caseCount)
    println("LiteRT-LM faithfulness driver: ${cases.size} cases → $outputPath")

    // Download the model if missing (blocking, with progress to stdout).
    if (!LitertLmModelStore.isDownloaded()) {
        println("Downloading LiteRT-LM model (~347 MB, first run only)...")
        LitertLmModelStore.download { frac ->
            print("\r  ${((frac * 100).toInt()).coerceIn(0, 100)}%")
        }
        println()
    }

    val generator = LitertLmTextGenerator(
        modelPath = LitertLmModelStore.modelFile().absolutePath,
    )

    runBlocking {
        // Warm up the engine once so the first case isn't penalized for model load.
        val status = generator.status()
        if (status is AiAvailability.Error) {
            System.err.println(
                "LiteRT-LM unavailable: ${status.message}\n" +
                    "(Intel Mac is unsupported — litertlm-jvm ships no darwin-x86_64 native lib.)",
            )
            exitProcess(1)
        }
        generator.warmup()
        println("Engine ready. Running ${cases.size} cases...")

        val records = cases.mapIndexed { index, case ->
            val record = runOneCase(generator, case)
            print("  [${index + 1}/${cases.size}] ${case.id}: ${record.route}")
            record.firstTokenMs?.let { print(" firstToken=${it}ms") }
            println()
            record
        }

        val json = Json { prettyPrint = true }
        Files.writeString(outputPath, json.encodeToString(ListSerializer(OutputRecord.serializer()), records))
        println("Wrote ${records.size} outputs to $outputPath")
        println("Next: score them with ferryman's reason-faithfulness scorer:")
        println("  python3 score_litert_outputs.py $outputPath")
    }
    runBlocking { generator.close() }
}

private suspend fun runOneCase(
    generator: LitertLmTextGenerator,
    case: CandidateCase,
): OutputRecord {
    val request = case.toMoveCoachRequest()
    val prompt = MoveCoachPromptBuilder.build(request)

    val chunks = try {
        generator.generate(prompt).toList()
    } catch (t: Throwable) {
        // An inference failure becomes a fallback record — the scorer will see
        // the deterministic fallback text and score it (which should pass
        // faithfulness, since the fallback is built from the same tags).
        return fallbackRecord(case, reason = "generation failed: ${t.message}")
    }

    val text = tokenText(chunks)
    val metrics = chunks.lastOrNull { it is AiTokenOrFinal.Final } as? AiTokenOrFinal.Final

    return if (text.isNotBlank()) {
        OutputRecord(
            id = case.id,
            fen = case.fen,
            bestMoveUci = case.bestMoveUci,
            tags = case.tags,
            output = text,
            route = "litert",
            firstTokenMs = metrics?.metrics?.firstTokenMs,
            completeMs = metrics?.metrics?.completeMs,
        )
    } else {
        // Empty generation → the orchestrator would fall back in production.
        fallbackRecord(case, reason = metrics?.metrics?.fallbackReason ?: "empty output")
    }
}

private fun fallbackRecord(case: CandidateCase, reason: String): OutputRecord {
    // The production orchestrator falls back to deterministic text when the
    // generator produces nothing. Record that text so the scorer sees what
    // the user would actually see, and mark the route.
    val req = case.toMoveCoachRequest()
    val fallbackText = "${req.deterministicHeadline} ${req.deterministicExplanation}"
    return OutputRecord(
        id = case.id,
        fen = case.fen,
        bestMoveUci = case.bestMoveUci,
        tags = case.tags,
        output = fallbackText,
        route = "fallback ($reason)",
        firstTokenMs = null,
        completeMs = null,
    )
}

private fun tokenText(chunks: List<AiTokenOrFinal>): String = buildString {
    chunks.forEach { chunk ->
        when (chunk) {
            is AiTokenOrFinal.Token -> append(chunk.text)
            is AiTokenOrFinal.Final -> append(chunk.text)
        }
    }
}.trim()

/** Mirrors com.example.evals.GoldenCase — read directly to avoid the :evals dep. */
@Serializable
private data class CandidateCase(
    val id: String,
    val fen: String,
    val bestMoveUci: String,
    val tags: List<String>,
    val eco: String? = null,
    val movesSan: List<String> = emptyList(),
    val expectedConcepts: List<String> = emptyList(),
)

private fun loadCandidates(path: Path): List<CandidateCase> {
    val json = Json { ignoreUnknownKeys = true }
    return json.decodeFromString(ListSerializer(CandidateCase.serializer()), Files.readString(path))
}

/**
 * Builds the request exactly as production does: the deterministic layer produces the headline and
 * explanation, and the model is asked only to rewrite them.
 *
 * The headline/explanation come from [DeterministicCoach] over a synthesized [MoveRecord] rather
 * than from string literals. That matters for what this driver measures. A fixed
 * `"This was a good move."` on every case reduces the run to "can the model rewrite one sentence",
 * and any faithfulness score taken that way describes the harness, not the model — the same class
 * of error as the UCI-instead-of-SAN bug noted below, which made every prompt say "Pawn".
 *
 * The golden set's `tags` already share the coach's motif vocabulary — `develops`,
 * `center-control`, and `king-safety` all hit real branches — so passing them through yields
 * case-specific text for 97 of the 100 cases. [MoveClass.BEST] with `cpLoss = 0` is the honest
 * classification: these cases are the engine's chosen move by construction, and the golden set
 * carries no centipawn data to say otherwise.
 */
private fun CandidateCase.toMoveCoachRequest(): MoveCoachRequest {
    val display = movesSan.lastOrNull() ?: bestMoveUci
    val record = MoveRecord(
        uci = bestMoveUci,
        san = movesSan.lastOrNull() ?: "",
        fenAfter = "",
        assessment = MoveAssessment(
            cpBefore = 0, cpPlayed = 0, cpBest = 0, cpLoss = 0,
            moveClass = MoveClass.BEST,
            motifs = tags,
        ),
    )
    return MoveCoachRequest(
        moveUci = bestMoveUci,
        // SAN, not UCI — describeMove reads the piece from the first letter, and UCI always starts
        // with a lowercase file, so UCI here described every move as a pawn. The first faithfulness
        // run was scored on output generated that way, with 4 of 10 cases actually N/N/Q/N moves.
        moveDisplay = display,
        deterministicHeadline = DeterministicCoach.buildHeadline(record),
        deterministicExplanation = DeterministicCoach.buildExplanation(record),
        engineDifficultyName = "EVAL",
    )
}

@Serializable
data class OutputRecord(
    val id: String,
    val fen: String,
    val bestMoveUci: String,
    val tags: List<String>,
    val output: String,
    val route: String,
    val firstTokenMs: Long?,
    val completeMs: Long?,
)
