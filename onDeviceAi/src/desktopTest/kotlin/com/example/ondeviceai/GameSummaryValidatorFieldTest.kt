package com.example.ondeviceai

import com.example.myapplication.MoveAssessment
import com.example.myapplication.MoveClass
import com.example.myapplication.MoveRecord
import com.example.myapplication.Set
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Replays real Game Summary output through [GameSummaryResponseValidator].
 *
 * **This is the test that decides whether the validator is shippable**, and the one whose absence let
 * two unusable rules land green. Every rule is easy to unit-test against text written to trip it;
 * what none of those tests can show is how often the rule fires on output that is *fine*. The first
 * version of this validator passed nine hand-written cases and rejected 42% of the AICore summaries
 * the 2026-08 benchmark had already judged good — six of them for saying *"while these aren't huge
 * blunders"* or *"opting for e4 instead of Nd2 would have been stronger"*.
 *
 * The corpus is `game-summary-field-corpus.jsonl`: 43 AICore and 20 Foundation Models summaries from
 * the 50-game run, each carrying the turning points the device actually computed. Regenerate it from
 * a fresh run rather than editing it by hand — a corpus edited to make a rule pass measures the rule
 * against itself, which is the failure this file exists to prevent.
 *
 * JVM-only because it reads a resource file; the rules themselves are commonMain and the shape of
 * each one is pinned in `GameSummaryResponseValidatorTest`.
 */
class GameSummaryValidatorFieldTest {

    @Serializable
    private data class TurningPointJson(
        val ply: Int,
        val san: String,
        val moveClass: String,
        val bestMoveSan: String? = null,
    )

    @Serializable
    private data class CaseJson(
        val id: String,
        val platform: String,
        val pgn: String,
        val turningPoints: List<TurningPointJson>,
        val modelSummary: String,
    )

    private val json = Json { ignoreUnknownKeys = true }

    private fun corpus(): List<CaseJson> {
        val stream = javaClass.classLoader.getResourceAsStream("game-summary-field-corpus.jsonl")
            ?: fail("game-summary-field-corpus.jsonl missing from desktopTest resources")
        return stream.bufferedReader().readLines()
            .filter { it.isNotBlank() }
            .map { json.decodeFromString(CaseJson.serializer(), it) }
    }

    /**
     * Rebuilds a request whose `extractTurningPoints` returns exactly the plies the device recorded.
     *
     * The player is White in every fixture, so player plies are the odd ply numbers (even indices).
     * `cpLoss` is chosen to land in the recorded `MoveClass` band; only the recorded turning points
     * carry an assessment, so nothing else can enter the top three.
     */
    private fun requestFor(case: CaseJson): GameSummaryRequest {
        val byPly = case.turningPoints.associateBy { it.ply }
        val length = maxOf(case.turningPoints.maxOf { it.ply }, 2)
        val history = (1..length).map { ply ->
            val tp = byPly[ply]
            if (tp == null) {
                MoveRecord(uci = "", san = "", fenAfter = "", assessment = null)
            } else {
                val moveClass = MoveClass.valueOf(tp.moveClass)
                val cpLoss = when (moveClass) {
                    MoveClass.BLUNDER -> 400
                    MoveClass.MISTAKE -> 200
                    MoveClass.INACCURACY -> 80
                    else -> 55
                }
                MoveRecord(
                    uci = "", san = tp.san, fenAfter = "",
                    assessment = MoveAssessment(
                        cpBefore = 0, cpPlayed = -cpLoss, cpBest = 0, cpLoss = cpLoss,
                        moveClass = moveClass, motifs = emptyList(), bestMoveSan = tp.bestMoveSan,
                    ),
                )
            }
        }
        return GameSummaryRequest(
            pgn = case.pgn,
            moveHistory = history,
            playerSide = Set.WHITE,
            engineDifficultyName = "HARD",
        )
    }

    private fun verdicts(platform: String): List<Pair<CaseJson, GameSummaryResponseValidator.Result>> =
        corpus().filter { it.platform == platform }
            .map { it to GameSummaryResponseValidator.validate(it.modelSummary, requestFor(it)) }

    @Test
    fun `accepts the AICore summaries the benchmark judged good`() {
        val results = verdicts("android")
        val accepted = results.count { it.second is GameSummaryResponseValidator.Result.Valid }
        val rejected = results.filter { it.second is GameSummaryResponseValidator.Result.Invalid }
            .joinToString("\n") { (case, result) ->
                "  ${case.id}: ${(result as GameSummaryResponseValidator.Result.Invalid).reason}\n" +
                    "    ${case.modelSummary.take(200)}"
            }

        // 37 of 43 at the time of writing. The floor is the number that makes the surface worth
        // attaching at all: every rejection costs the user a ~12 s wait and then the composed
        // summary they could have had instantly.
        assertTrue(
            accepted >= (results.size * 85) / 100,
            "accepted $accepted/${results.size}, below the 85% floor:\n$rejected",
        )
    }

    @Test
    fun `the only thing it rejects AICore for is incomplete coverage`() {
        // The sharp half of the guarantee. Every other rejection reason on this corpus has been a
        // validator bug, not a bad summary — this fails the build the moment a new rule starts
        // firing on real output, and names the summary it fired on.
        val surprises = verdicts("android")
            .mapNotNull { (case, result) -> (result as? GameSummaryResponseValidator.Result.Invalid)?.let { case to it.reason } }
            .filterNot { (_, reason) -> reason.startsWith("cited ") }

        assertEquals(
            emptyList(),
            surprises.map { "${it.first.id}: ${it.second}" },
            "a rule fired on output the benchmark judged good",
        )
    }

    @Test
    fun `rejects the Foundation Models output that made it unshippable`() {
        val results = verdicts("ios")
        val rejected = results.count { it.second is GameSummaryResponseValidator.Result.Invalid }

        // The iOS half of the 50-game run: half the answers carry no citation, a quarter are written
        // in first person as the player, two fabricate a [move-N], and some are refusals or
        // apologies rather than summaries. A validator that let most of this through would not be
        // protecting the surface it already ships on.
        assertTrue(
            rejected >= (results.size * 60) / 100,
            "only rejected $rejected/${results.size} Foundation Models summaries",
        )
    }

    @Test
    fun `never fabricates a board jump`() {
        // The one failure a reader cannot detect: B16 turns [move-N] into a tap that moves the board,
        // so a cited ply that was never a turning point navigates them somewhere meaningless.
        val leaked = corpus().mapNotNull { case ->
            val result = GameSummaryResponseValidator.validate(case.modelSummary, requestFor(case))
            val text = (result as? GameSummaryResponseValidator.Result.Valid)?.text ?: return@mapNotNull null
            val plies = case.turningPoints.map { it.ply }.toSet()
            val cited = Regex("\\[move-(\\d+)\\]").findAll(text).mapNotNull { it.groupValues[1].toIntOrNull() }
            cited.firstOrNull { it !in plies }?.let { "${case.id} cited [move-$it]" }
        }
        assertEquals(emptyList(), leaked, "an accepted summary cites a ply that is not a turning point")
    }
}
