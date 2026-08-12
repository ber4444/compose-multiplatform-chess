package com.example.ondeviceai

import com.example.myapplication.MoveAssessment
import com.example.myapplication.MoveClass
import com.example.myapplication.MoveRecord
import com.example.myapplication.Set
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The Game Summary floor.
 *
 * `DefaultGameSummaryOrchestrator.fallback()` answered *"No summary available. Review the PGN to
 * spot your mistakes!"* on every give-up path — while holding the player's three worst moves, each
 * already written as a finished sentence. The list existed only as prompt input for a model to
 * paraphrase, so when no model answered, neither did the app.
 *
 * Same defect and same fix as `RulesQaGroundingTest`: a usable answer is never downgraded to the
 * fallback text.
 */
class GameSummaryGroundingTest {

    private fun blunderAt(san: String, cpLoss: Int, best: String?) = MoveRecord(
        uci = "e2e4",
        san = san,
        fenAfter = "",
        assessment = MoveAssessment(
            cpBefore = 0,
            cpPlayed = -cpLoss,
            cpBest = 0,
            cpLoss = cpLoss,
            moveClass = if (cpLoss > 300) MoveClass.BLUNDER else MoveClass.MISTAKE,
            motifs = emptyList(),
            bestMoveSan = best,
        ),
    )

    private fun quiet() = MoveRecord(
        uci = "g1f3", san = "Nf3", fenAfter = "",
        assessment = MoveAssessment(0, 0, 0, 0, MoveClass.BEST, emptyList()),
    )

    private fun turningPoints(history: List<MoveRecord>) =
        GameSummaryPromptBuilder.extractTurningPoints(history, Set.WHITE, "MEDIUM")

    @Test
    fun `composes the turning points instead of apologising`() {
        // White moves are at even indices, so the blunders below are the player's.
        val history = listOf(blunderAt("Qh5", 400, "Nf3"), quiet(), blunderAt("Bxf7", 350, "O-O"))
        val text = GameSummaryGrounding.compose(turningPoints(history))

        assertTrue("No summary available" !in text, text)
        assertTrue("Qh5" in text, "the worst move must be named: $text")
        assertTrue("Nf3" in text, "and what to have played instead: $text")
    }

    @Test
    fun `keeps the move-N citations B16 turns into board jumps`() {
        val history = listOf(blunderAt("Qh5", 400, "Nf3"))
        val text = GameSummaryGrounding.compose(turningPoints(history))

        // CitationSanitizer preserves [move-N] on purpose; losing it here would silently delete the
        // tappable affordance rather than break anything visible.
        //
        // The closing bracket is escaped because Kotlin/JS lowers Regex onto a unicode-mode JS
        // RegExp, which rejects a lone `]` as a "lone quantifier bracket" — the JVM accepts it. Same
        // family of trap as MoveCoachResponseValidator's no-regex note.
        assertTrue(Regex("\\[move-\\d+\\]").containsMatchIn(text), text)
    }

    @Test
    fun `a clean game is congratulated rather than apologised to`() {
        val text = GameSummaryGrounding.compose(turningPoints(listOf(quiet(), quiet())))

        assertEquals(GameSummaryGrounding.CLEAN_GAME, text)
        assertTrue("No summary available" !in text, text)
    }

    @Test
    fun `the lead counts the moments it is about`() {
        val one = GameSummaryGrounding.compose(turningPoints(listOf(blunderAt("Qh5", 400, null))))
        assertTrue(one.startsWith("One moment"), one)

        val two = GameSummaryGrounding.compose(
            turningPoints(listOf(blunderAt("Qh5", 400, null), quiet(), blunderAt("Bxf7", 350, null))),
        )
        assertTrue(two.startsWith("Two moments"), two)
    }

    @Test
    fun `never returns blank whatever it was given`() {
        // The summary has no response validator behind it, so a blank floor would reach the user.
        assertTrue(GameSummaryGrounding.compose(emptyList()).isNotBlank())
        assertTrue(GameSummaryGrounding.compose(turningPoints(emptyList())).isNotBlank())
    }
}
