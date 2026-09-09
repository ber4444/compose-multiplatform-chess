package com.example.ondeviceai

import com.example.myapplication.MoveAssessment
import com.example.myapplication.MoveClass
import com.example.myapplication.MoveRecord
import com.example.myapplication.Set
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The shape of each rule. **What this file cannot show is how often a rule fires on output that is
 * fine** — every rule here passes with text written to trip it, and the first version of this
 * validator did too while rejecting 42% of the summaries the benchmark had judged good. The
 * acceptance half lives in `GameSummaryValidatorFieldTest`, which replays the real corpus; both
 * halves are load-bearing and neither substitutes for the other.
 */
class GameSummaryResponseValidatorTest {

    private fun tp(ply: Int, san: String, moveClass: MoveClass, best: String?) =
        GameSummaryPromptBuilder.TurningPoint(ply, san, moveClass, best)

    private val turningPoints = listOf(
        tp(5, "b4", MoveClass.INACCURACY, "e4"),
        tp(25, "Bh5+", MoveClass.MISTAKE, "Nf3"),
        tp(29, "Rg1", MoveClass.BLUNDER, "Kh1"),
    )

    /** A request whose `extractTurningPoints` returns exactly [turningPoints]. */
    private fun request(): GameSummaryRequest {
        val byPly = turningPoints.associateBy { it.ply }
        val history = (1..29).map { ply ->
            val point = byPly[ply] ?: return@map MoveRecord(uci = "", san = "", fenAfter = "", assessment = null)
            val cpLoss = when (point.moveClass) {
                MoveClass.BLUNDER -> 400
                MoveClass.MISTAKE -> 200
                else -> 80
            }
            MoveRecord(
                uci = "", san = point.san, fenAfter = "",
                assessment = MoveAssessment(
                    cpBefore = 0, cpPlayed = -cpLoss, cpBest = 0, cpLoss = cpLoss,
                    moveClass = point.moveClass, motifs = emptyList(), bestMoveSan = point.bestMoveSan,
                ),
            )
        }
        return GameSummaryRequest(
            pgn = "", moveHistory = history, playerSide = Set.WHITE, engineDifficultyName = "HARD",
        )
    }

    @Test
    fun `accepts a real AICore summary end to end`() {
        // Verbatim from the 2026-08 run, game-001 of the 12-game set. Not trimmed: the closing
        // sentences are where an over-eager rule tends to fire, so they are the point.
        val text = "Okay, let's break down the game. It looks like there were a few key moments where " +
            "we could have played more precisely. Specifically, [move-5] with b4 was a bit of an " +
            "inaccuracy, and [move-25] with Bh5+ proved to be a significant mistake, giving up a good " +
            "advantage. Finally, [move-29] with Rg1 was a blunder that further weakened our position. " +
            "We can learn from these moments to be more careful with our piece placement and avoid " +
            "unnecessary risks. Keep practicing, and we'll continue to improve!"

        val result = GameSummaryResponseValidator.validate(text, request())
        assertTrue(result is GameSummaryResponseValidator.Result.Valid, "must accept: $result")
    }

    @Test
    fun `rejects a move-N that is not a turning point`() {
        val text = "First [move-5] b4 was inaccurate. Then [move-12] was played. Finally [move-25] Bh5+ was a mistake."
        val error = GameSummaryResponseValidator.validateCitationSet(text, turningPoints)
        assertNotNull(error)
        assertTrue(error.contains("[move-12]"), error)
    }

    @Test
    fun `rejects a summary with no citation at all`() {
        val text = "You played b4 which was inaccurate. Then Bh5+ was a mistake."
        val error = GameSummaryResponseValidator.validateCitationCoverage(text, turningPoints)
        assertNotNull(error)
        assertTrue(error.contains("no [move-N] citation"), error)
    }

    @Test
    fun `rejects partial coverage`() {
        val text = "At [move-5] you played b4. At [move-25] Bh5+ was a mistake."
        val error = GameSummaryResponseValidator.validateCitationCoverage(text, turningPoints)
        assertNotNull(error)
        assertTrue(error.contains("cited 2 of 3"), error)
    }

    @Test
    fun `rejects an announced count that disagrees with the facts`() {
        val text = "Two significant mistakes decided this game: [move-5], [move-25] and [move-29]."
        val error = GameSummaryResponseValidator.validateCitationCoverage(text, turningPoints)
        assertNotNull(error)
        assertTrue(error.contains("claims 2"), error)
    }

    @Test
    fun `rejects first person but allows the coach's we`() {
        assertNotNull(GameSummaryResponseValidator.validateVoice("I made two mistakes. First, I played b4."))
        assertNotNull(GameSummaryResponseValidator.validateVoice("My mistake was playing b4 at [move-5]."))
        assertNull(GameSummaryResponseValidator.validateVoice("You played b4, and we can learn from it."))
        // "let me" is not the player's voice, and rejecting it cost nothing but a good summary.
        assertNull(GameSummaryResponseValidator.validateVoice("Let me break this down for you."))
    }

    @Test
    fun `rejects a move that was neither played nor suggested`() {
        val error = GameSummaryResponseValidator.validateMoveAttribution(
            "At [move-5] you played b4, and then Qxh7 lost the game.", turningPoints, request(),
        )
        assertNotNull(error)
        assertTrue(error.contains("Qxh7"), error)
    }

    @Test
    fun `allows a square name that looks like a pawn move`() {
        // "Sacrificing the Bishop on g6" was rejected as an invented move. A bare square is not a
        // claim about a move, and this surface's fallback is a complete answer, not an error.
        assertNull(
            GameSummaryResponseValidator.validateMoveAttribution(
                "At [move-25], sacrificing the bishop on g6 opened the position.", turningPoints, request(),
            ),
        )
    }

    @Test
    fun `allows the counterfactual in either word order`() {
        // Both were rejected by the rule this replaced, on six real summaries between them.
        assertNull(
            GameSummaryResponseValidator.validateMoveAttribution(
                "Around [move-5], opting for e4 instead of b4 would have been stronger.", turningPoints, request(),
            ),
        )
        assertNull(
            GameSummaryResponseValidator.validateMoveAttribution(
                "At [move-5] you played b4 instead of e4, which was an inaccuracy.", turningPoints, request(),
            ),
        )
    }

    @Test
    fun `rejects a reason invented for the engine's move`() {
        val error = GameSummaryResponseValidator.validateMoveAttribution(
            "The engine preferred e4 because it controls the center.", turningPoints, request(),
        )
        assertNotNull(error)
        assertTrue(error.contains("explains why the engine's move was better"), error)
    }

    @Test
    fun `rejects a piece no move in the game involves`() {
        val error = GameSummaryResponseValidator.validatePieceType(
            "At [move-29], your queen was trapped after Rg1.", turningPoints, request(),
        )
        assertNotNull(error)
        assertTrue(error.contains("queen"), error)
    }
}
