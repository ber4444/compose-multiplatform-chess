package com.example.ondeviceai

object MoveCoachPromptBuilder {

    /**
     * Contentless filler. This was previously prompted as a `Bad: "..."` counter-example — which
     * backfired: gemma3-270m emitted it verbatim as its answer (observed on-device). A small model
     * cannot represent "don't say this"; a negative example is just more text to copy. So the
     * constraint moved out of the prompt and into the validator, which rejects it outright.
     */
    internal const val GENERIC_FILLER = "This is a good move that improves the position."

    private val SYSTEM_PROMPT: String = buildString {
        appendLine("You are a chess coach.")
        appendLine("Rewrite the provided explanation of the move in 1-2 short, conversational sentences.")
        appendLine("Do not mention openings by name, engine depth, or ratings.")
    }

    fun build(request: MoveCoachRequest): AiGenerationRequest =
        AiGenerationRequest(
            systemPrompt = systemPrompt(request.bannedOpeningFrames),
            userPrompt = userPrompt(request),
            maxOutputTokens = MAX_OUTPUT_TOKENS_STRICT,
            temperature = 0.3,
        )

    /**
     * B15: the openings of the last few coach lines are banned by name, so a game's worth of moves
     * doesn't all start "This move develops…". The prompt is the only place this can live — no local
     * runtime can enforce a banned *phrase* through its sampler, so carrying the list on
     * [AiGenerationRequest] as well would add a field to published `:onDeviceAi` API that nothing
     * reads.
     */
    internal fun systemPrompt(bannedOpeningFrames: List<String>): String = buildString {
        append(SYSTEM_PROMPT)
        if (bannedOpeningFrames.isNotEmpty()) {
            appendLine(
                "Do not start your explanation with these phrases: " +
                    bannedOpeningFrames.joinToString { "'$it'" },
            )
        }
    }

    /**
     * The facts the engine detected, then the task.
     *
     * This used to be `Rewrite this explanation: "<deterministicExplanation>"` and nothing else —
     * no move, no assessment, no motifs. The model could not reason about the position because it
     * was never told the position; every "on-device AI" line was a reworded copy of one
     * deterministic sentence. `MoveCoachRequest` carried `moveUci`, `moveDisplay` and
     * `engineDifficultyName` the whole time and the prompt used none of them, while
     * `MoveCoachResponseValidator.groundingTokens` graded the answer on move squares the model had
     * never seen.
     *
     * **"Code detects, the model narrates" is unchanged.** Every fact below comes from
     * `MoveAssessment`; the model supplies phrasing and the connective reasoning between facts, not
     * new claims. The headline stays code-authored — `DefaultAiCoachOrchestrator.success` uses
     * `request.deterministicHeadline` regardless of what the model returns.
     *
     * The deterministic explanation stays in the prompt as the grounding floor: it is what the
     * fallback would have said, so the model's job is to do at least that well with more context.
     */
    internal fun userPrompt(request: MoveCoachRequest): String = buildString {
        appendLine("The player just played ${request.moveDisplay}.")
        request.moveClassName?.let { appendLine("Engine assessment of that move: ${it.lowercase()}.") }
        request.centipawnLoss?.takeIf { it > 0 }?.let {
            appendLine("It gives up about $it centipawns against the best move.")
        }
        if (request.motifs.isNotEmpty()) {
            appendLine("Tactical features detected: ${request.motifs.joinToString(", ")}.")
        }
        appendLine("Baseline explanation: \"${request.deterministicExplanation}\"")
        appendLine()
        appendLine(
            "Using only the facts above, tell the player in 1-2 short, conversational sentences " +
                "why ${request.moveDisplay} was that good or bad. Do not invent other moves, " +
                "squares, or evaluations.",
        )
    }

    const val MAX_OUTPUT_TOKENS_STRICT = 100
    const val MAX_OUTPUT_CHARS = 300
}
