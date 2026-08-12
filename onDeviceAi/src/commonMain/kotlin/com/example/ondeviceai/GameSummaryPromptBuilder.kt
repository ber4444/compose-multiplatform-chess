package com.example.ondeviceai

import com.example.myapplication.MotifDetector
import com.example.myapplication.MoveAssessment
import com.example.myapplication.MoveRecord
import com.example.myapplication.Set

internal object GameSummaryPromptBuilder {

    private const val SYSTEM_PROMPT = """You are a chess coach. Analyze the turning points in the game below and write a short, cohesive summary (2-3 sentences) explaining the player's most significant mistakes. Use a helpful, encouraging tone. You MUST cite the specific moves you discuss using the [move-N] format provided in the turning points. Do not invent any new mistakes; stick to the provided turning points."""

    private const val CONGRATS_SYSTEM_PROMPT = """You are a chess coach. The player played a great game with no major mistakes. Write a short, cohesive summary (1-2 sentences) congratulating them on a well-played game."""

    fun build(request: GameSummaryRequest): AiGenerationRequest {
        val turningPoints = extractTurningPoints(request.moveHistory, request.playerSide, request.engineDifficultyName)
        
        val systemPrompt = if (turningPoints.isEmpty()) CONGRATS_SYSTEM_PROMPT else SYSTEM_PROMPT
        
        val userPromptBuilder = StringBuilder()
        userPromptBuilder.append("PGN:\n${request.pgn}\n\n")
        
        if (turningPoints.isEmpty()) {
            userPromptBuilder.append("The player played well with no major mistakes. Congratulate them.")
        } else {
            userPromptBuilder.append("Turning points in this game:\n")
            turningPoints.forEach { tp ->
                userPromptBuilder.append(tp).append("\n")
            }
            userPromptBuilder.append("\nSummarize these mistakes:")
        }
        
        return AiGenerationRequest(
            systemPrompt = systemPrompt,
            userPrompt = userPromptBuilder.toString(),
            maxOutputTokens = 250,
            temperature = 0.3
        )
    }

    /**
     * The turning points, already written as finished sentences.
     *
     * `internal` rather than private because [GameSummaryGrounding] composes the same list into the
     * answer itself. These were only ever *prompt input* — a model was asked to paraphrase them and,
     * if it could not, the user got "No summary available" instead of the very sentences that were
     * sitting right here.
     */
    internal fun extractTurningPoints(moveHistory: List<MoveRecord>, playerSide: Set, difficulty: String): List<String> {
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
            val better = assessment.bestMoveSan?.takeIf { it.isNotBlank() }?.let { " The engine preferred $it." } ?: ""
            "[move-$ply]: You played ${record.san}. This was a ${assessment.moveClass.name.lowercase()}.$better $intuition"
        }
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
        
        if (assessment.winPercentLost(playerSide) > 20.0) {
            return "This move lost significant material or allowed a forced mate."
        } else if (assessment.winPercentLost(playerSide) > 10.0) {
            return "This move gave up a significant positional or material advantage."
        }
        return "This move was slightly inaccurate."
    }
}
