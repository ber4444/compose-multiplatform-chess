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

    internal fun userPrompt(request: MoveCoachRequest): String = buildString {
        appendLine("Rewrite this explanation in 1-2 short, conversational sentences:")
        appendLine()
        append("\"${request.deterministicExplanation}\"")
    }

    const val MAX_OUTPUT_TOKENS_STRICT = 100
    const val MAX_OUTPUT_CHARS = 300
}
