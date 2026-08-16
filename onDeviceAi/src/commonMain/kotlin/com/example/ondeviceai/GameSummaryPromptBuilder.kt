package com.example.ondeviceai

import com.example.myapplication.MotifDetector
import com.example.myapplication.MoveAssessment
import com.example.myapplication.MoveClass
import com.example.myapplication.MoveRecord
import com.example.myapplication.Set

internal object GameSummaryPromptBuilder {

    private const val SYSTEM_PROMPT = """You are a chess coach. Analyze the turning points in the game below and write a short, cohesive summary (2-3 sentences) explaining the player's most significant mistakes. Use a helpful, encouraging tone. You MUST cite the specific moves you discuss using the [move-N] format provided in the turning points. Do not invent any new mistakes; stick to the provided turning points."""

    private const val CONGRATS_SYSTEM_PROMPT = """You are a chess coach. The player played a great game with no major mistakes. Write a short, cohesive summary (1-2 sentences) congratulating them on a well-played game."""

    fun build(request: GameSummaryRequest): AiGenerationRequest {
        val turningPoints = extractTurningPoints(request.moveHistory, request.playerSide, request.engineDifficultyName)
        
        val systemPrompt = if (turningPoints.isEmpty()) CONGRATS_SYSTEM_PROMPT else SYSTEM_PROMPT
        
        val userPromptBuilder = StringBuilder()
        // Deliberately **not** the PGN. It was the first thing in this prompt and it bought nothing:
        // the turning points already carry every fact the summary is allowed to use, and the system
        // prompt forbids going beyond them. What the raw movetext did buy was two problems. Apple
        // Foundation Models rejected 8 of 12 of these prompts outright with "An unsupported language
        // or locale was used" — the same 8 across two runs, in 15-20 ms, which is an input guardrail
        // and not a model deliberating — and on Android the PGN was where the invention came from:
        // every unverifiable flourish in the 2026-08 run ("in the endgame", "contributed to the
        // loss" on a game with no result) was the model reading the movetext and narrating it.
        val side = if (request.playerSide == Set.WHITE) "White" else "Black"
        userPromptBuilder.append("You played $side. The game lasted ${request.moveHistory.size} plies.\n\n")

        if (turningPoints.isEmpty()) {
            userPromptBuilder.append("The player played well with no major mistakes. Congratulate them.")
        } else {
            userPromptBuilder.append("Turning points in this game:\n")
            turningPoints.forEach { tp ->
                userPromptBuilder.append(render(tp)).append("\n")
            }
            userPromptBuilder.append("\nSummarize these mistakes:")
        }
        
        return AiGenerationRequest(
            systemPrompt = systemPrompt,
            userPrompt = userPromptBuilder.toString(),
            maxOutputTokens = 250,
            temperature = 0.3,
            noRepeatNgramSize = NO_REPEAT_NGRAM,
        )
    }

    /**
     * Wider than [AiGenerationRequest]'s default of 4, because a summary of three turning points is
     * a **parallel list** and a parallel list repeats its connectives by construction.
     *
     * The default cut real answers mid-sentence on both runtimes in the 2026-08 benchmark — 4 of 4
     * Foundation Models successes and 1 of 7 AICore successes — and it was read as the models
     * truncating. It was this constant. The cut lands at the start of the repeated window, so the
     * user sees a sentence stop dead:
     *
     * > *"…Next, you played [move-45] Qb2 instead of c3. The engine preferred c3, so this was another
     * > small inaccuracy. Finally, you played [move-47] c3 instead of cxd4. The engine preferred cxd4,"*
     *
     * — cut before a second *"so this was another small inaccuracy"*, which is not a degenerate loop
     * but the third item of a list phrased like the first two. The observed legitimate repeats run
     * 4–7 words; a runtime that has genuinely fallen into a loop repeats far more than 8. B15's rule
     * is kept rather than disabled because this surface has **no response validator at all**, so the
     * guard is the only thing between a looping model and the user.
     */
    private const val NO_REPEAT_NGRAM = 8

    /**
     * Structured turning point carrying the facts needed by [GameSummaryResponseValidator] and [render].
     */
    internal data class TurningPoint(
        val ply: Int,
        val san: String,
        val moveClass: MoveClass,
        val bestMoveSan: String?,
        val intuition: String = "",
    )

    /**
     * Extracts the turning points from the move history as structured [TurningPoint] records.
     *
     * `internal` rather than private because [GameSummaryGrounding] and [GameSummaryResponseValidator]
     * consume the facts directly.
     */
    internal fun extractTurningPoints(moveHistory: List<MoveRecord>, playerSide: Set, difficulty: String): List<TurningPoint> {
        val threshold = when (difficulty) {
            "EASY" -> 300 // BLUNDER only
            "MEDIUM" -> 100 // MISTAKE and BLUNDER
            "HARD", "MAX" -> 50 // INACCURACY and worse
            else -> 100
        }

        val playerMoves = moveHistory.mapIndexedNotNull { index, moveRecord ->
            val isPlayerMove = if (playerSide == Set.WHITE) index % 2 == 0 else index % 2 != 0
            val assessment = moveRecord.assessment
            if (isPlayerMove && assessment != null && assessment.cpLoss > threshold) {
                // Return a Pair of ply number (index + 1) and the move record
                Pair(index + 1, moveRecord)
            } else null
        }

        // Rank by winPercentLost descending, take top 3
        val topMistakes = playerMoves.sortedByDescending { it.second.assessment!!.winPercentLost(playerSide) }.take(3)

        return topMistakes.sortedBy { it.first }.map { (ply, record) ->
            val assessment = record.assessment!!
            val intuition = mapToIntuition(assessment, playerSide)
            TurningPoint(
                ply = ply,
                san = record.san,
                moveClass = assessment.moveClass,
                bestMoveSan = assessment.bestMoveSan?.takeIf { it.isNotBlank() },
                intuition = intuition,
            )
        }
    }

    /**
     * Renders a [TurningPoint] into a finished summary sentence.
     */
    internal fun render(tp: TurningPoint): String {
        val better = tp.bestMoveSan?.takeIf { it.isNotBlank() }?.let { " The engine preferred $it." } ?: ""
        val intuitionPart = if (tp.intuition.isNotBlank()) " ${tp.intuition}" else ""
        return "[move-${tp.ply}]: You played ${tp.san}. This was ${classPhrase(tp.moveClass)}.$better$intuitionPart"
    }

    /**
     * The move class as a noun phrase, article included.
     *
     * This was `"a ${moveClass.name.lowercase()}"`, which shipped **"This was a inaccuracy."** and
     * **"This was a good."** to both platforms — and this is the deterministic floor, so it is also
     * the text every fallback path renders. Two classes are reachable here beyond the obvious three:
     * [MoveClass.GOOD] spans 30–60cp while the HARD/MAX turning-point threshold is 50, so a move can
     * clear the bar without ever being a named error. [MoveClass.BEST], [MoveClass.EXCELLENT] and
     * [MoveClass.BOOK] cannot clear any current threshold, and are mapped anyway so that lowering one
     * cannot reintroduce "a best" — the `when` is exhaustive, so a new class is a compile error here
     * rather than a new article bug in shipped prose.
     */
    private fun classPhrase(moveClass: MoveClass): String = when (moveClass) {
        MoveClass.BLUNDER -> "a blunder"
        MoveClass.MISTAKE -> "a mistake"
        MoveClass.INACCURACY -> "an inaccuracy"
        MoveClass.GOOD, MoveClass.BEST, MoveClass.EXCELLENT, MoveClass.BOOK -> "a small slip"
    }

    // Motif strings come from MotifDetector's constants, never string literals. This branch was
    // written as "Fork"/"Pin"/"Skewer"/"Discovered Attack" while the detector emits
    // "fork"/"pin"/"skewer"/"discovered-attack", so the intersection was empty and every tactical
    // turning point silently fell through to the cpLoss-only text below. That is the same failure
    // MotifDetector's KDoc records for DeterministicCoach; the test that pins it
    // (`motif vocabulary is understood by DeterministicCoach`) only covers that one consumer.
    private val TACTICAL_MOTIFS = setOf(
        MotifDetector.FORK,
        MotifDetector.PIN,
        MotifDetector.SKEWER,
        MotifDetector.DISCOVERED_ATTACK,
    )

    private fun mapToIntuition(assessment: MoveAssessment, playerSide: Set): String {
        if (assessment.motifs.any { it in TACTICAL_MOTIFS }) {
            return "You missed a tactical sequence or allowed a tactic."
        }
        
        // Not "lost significant material or allowed a forced mate" — the assessment is a win-percent
        // delta and does not know which of the two happened, so the old wording made the reader pick.
        // What it does know is the size of the swing, and saying only that is both shorter and true.
        val lost = assessment.winPercentLost(playerSide)
        return when {
            lost > 20.0 -> "This move gave up a large advantage."
            lost > 10.0 -> "This move gave up a significant positional or material advantage."
            // The two numbers can disagree, because the class comes from `cpLoss` and this comes from
            // a win-percent delta: a large centipawn swing barely moves the win estimate in a
            // position that is already decided. Saying "slightly inaccurate" there contradicted the
            // class named one sentence earlier, and shipped as *"This was a mistake. The engine
            // preferred Qxc5. This move was slightly inaccurate."*
            assessment.moveClass == MoveClass.MISTAKE || assessment.moveClass == MoveClass.BLUNDER ->
                "The engine's move was much stronger, though the practical chances barely changed."
            else -> "This move was slightly inaccurate."
        }
    }
}
