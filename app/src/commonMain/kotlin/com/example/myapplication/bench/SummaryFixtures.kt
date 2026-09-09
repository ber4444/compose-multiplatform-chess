package com.example.myapplication.bench

import com.example.myapplication.MotifDetector
import com.example.myapplication.MoveAssessor
import com.example.myapplication.MoveRecord
import com.example.myapplication.Set
import com.example.ondeviceai.GameSummaryGrounding
import com.example.ondeviceai.GameSummaryRequest
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Finished games for the **Game Summary** benchmark, shared by both platforms.
 *
 * Game Summary is measured separately from the Move Coach and on its own terms: it is a deliberate
 * button press with a spinner at game end rather than an automatic per-move panel, so a wait that
 * disqualifies the coach may be fine here. What is *not* different is the bar for truth — this
 * surface has **no response validator at all**, so whatever the model writes reaches the user.
 *
 * The fixtures are real games played by an engine against itself with the player's side deliberately
 * weakened, assessed ply by ply — see `tools/generate_summary_fixtures.py`. The bench used to carry
 * one hand-written game whose `MoveRecord`s were fabricated (`uci = "e2e4"` on every ply, the start
 * position as every `fenAfter`, centipawn losses chosen by hand). Since the summary's whole job is
 * to pick the moments that decided *this* game, a fixture whose turning points were chosen by hand
 * cannot tell you whether it did.
 *
 * The fixture carries raw engine numbers and [MoveAssessor] derives `MoveClass` and
 * `winPercentLost` here, so the bench cannot drift from the app's own definition of a blunder.
 */
@Serializable
private data class SummaryPlyJson(
    val san: String,
    val uci: String,
    val fenAfter: String,
    val isPlayer: Boolean = false,
    val cpBefore: Int? = null,
    val cpPlayed: Int? = null,
    val cpBest: Int? = null,
    val bestMoveSan: String? = null,
)

@Serializable
private data class SummaryGameJson(
    val id: String,
    val pgn: String,
    val playerSide: String = "WHITE",
    val result: String = "*",
    val plies: List<SummaryPlyJson> = emptyList(),
)

data class SummaryFixture(
    val id: String,
    val request: GameSummaryRequest,
    /** What the shipping deterministic composer says for this game — the column to beat. */
    val deterministicSummary: String,
    val result: String,
    val playerBlunders: Int,
)

object SummaryFixtures {

    private val json = Json { ignoreUnknownKeys = true }

    fun load(text: String?): List<SummaryFixture> {
        val games = text?.let {
            runCatching { json.decodeFromString(ListSerializer(SummaryGameJson.serializer()), it) }.getOrNull()
        } ?: return emptyList()

        return games.map { game ->
            val playerSide = if (game.playerSide.equals("BLACK", ignoreCase = true)) Set.BLACK else Set.WHITE
            val history = game.plies.map { ply ->
                MoveRecord(
                    uci = ply.uci,
                    san = ply.san,
                    fenAfter = ply.fenAfter,
                    cpAfter = ply.cpPlayed,
                    // Only the player's plies carry an assessment, exactly as `runIdleAnalysis`
                    // records them: the coach never assesses the engine's replies.
                    assessment = if (ply.isPlayer && ply.cpBefore != null && ply.cpPlayed != null && ply.cpBest != null) {
                        MoveAssessor.assessMove(
                            cpBefore = ply.cpBefore,
                            cpPlayed = ply.cpPlayed,
                            cpBest = ply.cpBest,
                            motifs = emptyList(),
                            motifDetails = emptyMap(),
                            bestMoveUci = null,
                            bestMoveSan = ply.bestMoveSan,
                        )
                    } else {
                        null
                    },
                )
            }
            val request = GameSummaryRequest(
                pgn = game.pgn,
                moveHistory = history,
                playerSide = playerSide,
                engineDifficultyName = "HARD",
            )
            SummaryFixture(
                id = game.id,
                request = request,
                deterministicSummary = GameSummaryGrounding.composeFor(request),
                result = game.result,
                playerBlunders = history.count { (it.assessment?.cpLoss ?: 0) > 300 },
            )
        }
    }

    /**
     * One JSONL row. Same shape on both platforms and, like the coach's, it carries the
     * deterministic text beside the model's so the two can be scored from one file.
     */
    fun jsonLine(
        fixture: SummaryFixture,
        modelIdentifier: String,
        deviceModel: String,
        osVersion: String,
        kind: String,
        elapsedMs: Long,
        text: String,
        // Never just the kind. "FellBack" conflates "no route", "the model errored" and "it wrote
        // something rejected", and on this surface the first is invisible without the reason.
        fallbackReason: String? = null,
    ): String = """{"gameId":${quote(fixture.id)},"plies":${fixture.request.moveHistory.size},""" +
        """"playerBlunders":${fixture.playerBlunders},"result":${quote(fixture.result)},""" +
        """"modelIdentifier":${quote(modelIdentifier)},"deviceModel":${quote(deviceModel)},""" +
        """"osVersion":${quote(osVersion)},"kind":${quote(kind)},"elapsedMs":$elapsedMs,""" +
        """"fallbackReason":${fallbackReason?.let { quote(it) } ?: "null"},""" +
        """"deterministicSummary":${quote(fixture.deterministicSummary)},""" +
        """"pgn":${quote(fixture.request.pgn)},"modelSummary":${quote(text)}}"""

    private fun quote(s: String): String =
        "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"")
            .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t") + "\""
}
