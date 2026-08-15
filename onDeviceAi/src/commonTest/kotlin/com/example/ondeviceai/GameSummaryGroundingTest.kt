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

    /** A record whose class is set directly, so the classes that only appear on HARD are reachable. */
    private fun classedAt(san: String, cpLoss: Int, moveClass: MoveClass) = MoveRecord(
        uci = "e2e4", san = san, fenAfter = "",
        assessment = MoveAssessment(0, -cpLoss, 0, cpLoss, moveClass, emptyList()),
    )

    @Test
    fun `every move class reads as English prose`() {
        // "This was a inaccuracy." and "This was a good." both shipped, on both platforms, as the
        // text every fallback path renders. GOOD is reachable because it spans 30-60cp while the
        // HARD threshold is 50; the rest are covered so a threshold change cannot reintroduce one.
        for (moveClass in MoveClass.entries) {
            val text = GameSummaryGrounding.compose(
                GameSummaryPromptBuilder.extractTurningPoints(
                    listOf(classedAt("Qh5", 55, moveClass)), Set.WHITE, "HARD",
                ),
            )
            assertTrue("a inaccuracy" !in text, "$moveClass: $text")
            assertTrue("a good." !in text, "$moveClass: $text")
            assertTrue("a best" !in text && "a excellent" !in text && "a book" !in text, "$moveClass: $text")
        }
    }

    @Test
    fun `a parallel list of turning points is not read as a repetition loop`() {
        // Reconstructed from the iOS game-010 row of the 2026-08 benchmark. The recorded output stops
        // dead at "The engine preferred cxd4," — the guard cut it there, and the tail below is what
        // it cut: the third list item phrased like the first two, which is what the 4-gram matched.
        val summary = "You made a few small mistakes in this game. " +
            "First, you played [move-31] Ra2 instead of O-O. The engine preferred O-O, so this was a small inaccuracy. " +
            "Next, you played [move-45] Qb2 instead of c3. The engine preferred c3, so this was another small inaccuracy. " +
            "Finally, you played [move-47] c3 instead of cxd4. The engine preferred cxd4, so this was another small inaccuracy."

        val request = GameSummaryRequest(
            pgn = "1. e4 e5",
            moveHistory = listOf(blunderAt("Qh5", 400, "Nf3")),
            playerSide = Set.WHITE,
            engineDifficultyName = "HARD",
        )
        val ngram = GameSummaryPromptBuilder.build(request).noRepeatNgramSize
        assertTrue(ngram != null && ngram > 4, "the summary must widen the default: $ngram")

        assertTrue(summary.truncateAtRepetition(4).length < summary.length, "the old default cut this")
        assertEquals(summary, summary.truncateAtRepetition(ngram!!), "the width shipped must not")

        // Still a guard, not a hole: this surface has no response validator, so a genuine loop has
        // to be cut by the same width that leaves the list above intact.
        val loop = "You played Qh5. " + "It gave up a large advantage and lost material. ".repeat(3)
        assertTrue(loop.truncateAtRepetition(ngram).length < loop.length, "a real loop must still be cut")
    }

    @Test
    fun `a summary cut mid-sentence is trimmed back to the last finished one`() {
        val cut = "Okay, let's break down those turning points! At [move-45], `Rhh1` didn't quite " +
            "achieve the desired effect, and `Re1`"
        assertEquals(
            "Okay, let's break down those turning points!",
            trimIncompleteSummaryTail(cut),
        )

        // A finished answer is left exactly as it is, closing punctuation included.
        val whole = "You played well. Two moments decided this game (both in the middlegame.)"
        assertEquals(whole, trimIncompleteSummaryTail(whole))

        // Nothing completed means nothing to trim to — one ragged sentence still beats no answer.
        assertEquals("A single unfinished thought", trimIncompleteSummaryTail("A single unfinished thought"))
    }

    @Test
    fun `never returns blank whatever it was given`() {
        // The summary has no response validator behind it, so a blank floor would reach the user.
        assertTrue(GameSummaryGrounding.compose(emptyList()).isNotBlank())
        assertTrue(GameSummaryGrounding.compose(turningPoints(emptyList())).isNotBlank())
    }
}
