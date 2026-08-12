package com.example.ondeviceai

object MoveCoachPromptBuilder {

    /**
     * Contentless filler. This was previously prompted as a `Bad: "..."` counter-example — which
     * backfired: gemma3-270m emitted it verbatim as its answer (observed on-device). A small model
     * cannot represent "don't say this"; a negative example is just more text to copy. So the
     * constraint moved out of the prompt and into the validator, which rejects it outright.
     */
    internal const val GENERIC_FILLER = "This is a good move that improves the position."

    /**
     * The system prompt states the *constraint*; [userPrompt] states the task.
     *
     * It used to open "Rewrite the provided explanation", which contradicted the user prompt once
     * that stopped being a rewrite task — the model was told to reword one sentence and, three
     * lines later, to reason across a list of facts. A 270M model resolves that by copying, which
     * is the behaviour the prompt rewrite existed to stop.
     *
     * What the old wording was really protecting is the no-invention rule, and that is now stated
     * as the rule it is: the facts are handed over, the model supplies only the phrasing. Same
     * fence, no longer disguised as a task description.
     */
    private val SYSTEM_PROMPT: String = buildString {
        appendLine("You are a chess coach.")
        appendLine("Explain the move in 1-2 short, conversational sentences.")
        appendLine("Use only the facts you are given. Never invent moves, squares, or evaluations.")
        // Asking is cheap and works often enough to be worth it; InlineMarkdown on the display path
        // is what makes it safe when it doesn't. gemma3-270m reaches for **bold** and bullet lists
        // unprompted, and the free tier has no validator between the model and the panel.
        appendLine("Write plain prose. No markdown, no asterisks, no bullet points, no headings.")
        appendLine("Do not mention openings by name, engine depth, or ratings.")
    }

    /**
     * Win percentage loss as a simple phrase, or null when there is nothing worth saying.
     */
    internal fun winPercentLossPhrase(winPercentLost: Double?): String? {
        if (winPercentLost == null || winPercentLost < 1.0) return null
        return "It drops your winning chances by ${winPercentLost.toInt()}%"
    }

    /**
     * Motif ids are slugs (`discovered-attack`, `hangs-piece`) and the prompt is prose, so they are
     * spelled out before the model sees them — a small model copies the nearest token, and
     * "discovered-attack" reaching the panel with its hyphen is the coach quoting a database key at
     * the user.
     *
     * The map only covers slugs that read badly when de-hyphenated; everything else falls through
     * to the general rule, so adding a motif to `MotifDetector` can never leak a raw id even if
     * nobody remembers this function exists.
     */
    private val MOTIF_PHRASES = mapOf(
        "hangs-piece" to "hanging piece",
        "castle-kingside" to "castling kingside",
        "castle-queenside" to "castling queenside",
    )

    internal fun humanizeMotif(motif: String): String =
        MOTIF_PHRASES[motif] ?: motif.replace('-', ' ').replace('_', ' ').trim()

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
        winPercentLossPhrase(request.winPercentLost)?.let { appendLine("$it.") }
        request.betterMoveDisplay?.takeIf { it.isNotBlank() }?.let {
            appendLine("The engine preferred $it.")
        }
        if (request.motifs.isNotEmpty()) {
            appendLine(
                "Tactical features detected: " +
                    "${request.motifs.joinToString(", ") { humanizeMotif(it) }}.",
            )
        }
        appendLine("Baseline explanation: \"${request.deterministicExplanation}\"")
        appendLine()
        appendLine(
            "Using only the facts above, tell the player in 1-2 short, conversational sentences " +
                "why ${request.moveDisplay} was that good or bad. Do not invent other moves, " +
                "squares, or evaluations.",
        )
    }

    /**
     * Generation budget in **tokens**, which is not [MAX_OUTPUT_CHARS] divided by anything.
     *
     * 100 was derived from the 300-character display cap, and that is the wrong quantity: the cap
     * bounds what the *user reads*, while this bounds what the *model produces*, and a reasoning
     * model spends most of the second on text nobody ever sees. Qwen3 opens with `<think>`, and at
     * 100 tokens it never reached `</think>` — `MoveCoachResponseValidator.stripThinkBlocks` then
     * discarded the unterminated block, which is the whole output, and every single move logged
     * `Validation failed: empty response`.
     *
     * **This is the third time this repo has made this exact mistake.** `LlmChatComposer` learned it
     * first (2048, with a comment); the cloud opening route repeated it at 90 and is documented in
     * CLAUDE.md as `MAX_OUTPUT_TOKENS was 90, derived from the 300-character output cap — the wrong
     * quantity`; this is the on-device copy. If you are tempted to tie a token budget to a character
     * cap again, that is the bug.
     *
     * Sized for a short answer *plus* a short deliberation, rather than for the answer alone —
     * on-device generation is slow enough that the cloud's 2048 would cost a visible pause on every
     * move, and [suppressReasoning] keeps the common case near the answer length anyway.
     */
    const val MAX_OUTPUT_TOKENS_STRICT = 384
    const val MAX_OUTPUT_CHARS = 300
}
