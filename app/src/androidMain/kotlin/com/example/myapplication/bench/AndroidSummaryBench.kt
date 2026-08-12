package com.example.myapplication.bench

import android.content.Context
import com.example.myapplication.MoveAssessment
import com.example.myapplication.MoveClass
import com.example.myapplication.MoveRecord
import com.example.myapplication.Set
import com.example.ondeviceai.DefaultGameSummaryOrchestrator
import com.example.ondeviceai.GameSummaryRequest
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

    repeat(iterations) { i ->
        val start = System.currentTimeMillis()
        val result = orchestrator.summarizeGame(request())
        val elapsed = System.currentTimeMillis() - start

        val text = when (result) {
            is GameSummaryResult.Success -> result.explanation.explanation
            is GameSummaryResult.FellBack -> result.text
            is GameSummaryResult.Failed -> result.message
        }
        val kind = result::class.simpleName
        val line = """{"run":$i,"kind":"$kind","elapsedMs":$elapsed,"model":"${route?.let { it::class.simpleName } ?: "none"}","text":${quote(text)}}"""
        resultsFile.appendText(line + "\n")
    }
}

private fun quote(s: String): String =
    "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "") + "\""

/**
 * A 24-ply game with three player blunders, so `extractTurningPoints` yields its full cap of 3 and
 * the prompt carries a realistic PGN alongside them.
 */
private fun request(): GameSummaryRequest {
    val history = mutableListOf<MoveRecord>()
    val sans = listOf(
        "e4", "e5", "Qh5", "Nc6", "Bc4", "Nf6", "Qxf7+", "Kxf7", "Nf3", "d5", "exd5", "Bg4",
        "d3", "Bb4+", "c3", "Bd6", "O-O", "Re8", "Bg5", "h6", "Bxf6", "Qxf6", "Nbd2", "Rxe1",
    )
    sans.forEachIndexed { index, san ->
        val isPlayer = index % 2 == 0
        val cpLoss = when (index) {
            4 -> 420   // Qh5 area — a real blunder
            12 -> 260
            18 -> 140
            else -> 5
        }
        history += MoveRecord(
            uci = "e2e4",
            san = san,
            fenAfter = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1",
            assessment = if (isPlayer) MoveAssessment(
                cpBefore = 0,
                cpPlayed = -cpLoss,
                cpBest = 0,
                cpLoss = cpLoss,
                moveClass = when {
                    cpLoss > 300 -> MoveClass.BLUNDER
                    cpLoss > 100 -> MoveClass.MISTAKE
                    cpLoss > 60 -> MoveClass.INACCURACY
                    else -> MoveClass.BEST
                },
                motifs = emptyList(),
                bestMoveSan = if (cpLoss > 100) "Nf3" else null,
            ) else null,
        )
    }
    val pgn = sans.chunked(2).mapIndexed { i, pair -> "${i + 1}. ${pair.joinToString(" ")}" }
        .joinToString(" ")
    return GameSummaryRequest(
        pgn = pgn,
        moveHistory = history,
        playerSide = Set.WHITE,
        engineDifficultyName = "MEDIUM",
    )
}
