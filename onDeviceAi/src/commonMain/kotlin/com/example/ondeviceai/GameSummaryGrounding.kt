package com.example.ondeviceai

/**
 * The Game Summary floor: the turning points composed into an answer, with no model involved.
 *
 * `DefaultGameSummaryOrchestrator.fallback()` used to return
 * *"No summary available. Review the PGN to spot your mistakes!"* on **every** give-up path — no
 * route, timeout, quota, generation error. It said that while holding a ranked list of the player's
 * worst moves, each already written as a finished sentence by
 * [GameSummaryPromptBuilder.extractTurningPoints], because that list was only ever treated as prompt
 * input for a model to paraphrase.
 *
 * That is the same defect [RulesQaGrounding] exists to fix, and the same fix: **a usable answer is
 * never downgraded to the fallback text.** The model's contribution is phrasing; when it does not
 * arrive, the facts still do.
 *
 * The `[move-N]` prefixes are preserved verbatim — B16 turns them into tappable board jumps, and
 * `ui/CitationSanitizer` deliberately keeps that one tag shape for exactly this reason.
 */
object GameSummaryGrounding {

    /**
     * A summary built from [turningPoints], or the clean-game line when there are none.
     *
     * Never returns blank: an empty list is not a failure, it is a player who did not blunder, and
     * telling them so is a better summary than any apology.
     */
    fun compose(turningPoints: List<String>): String {
        if (turningPoints.isEmpty()) return CLEAN_GAME
        val lead = when (turningPoints.size) {
            1 -> "One moment decided this game."
            2 -> "Two moments decided this game."
            else -> "Three moments decided this game."
        }
        return (listOf(lead) + turningPoints).joinToString(" ")
    }

    /**
     * [compose] over the turning points of [request] — the whole floor in one call.
     *
     * Exists so `:app` can render a summary with no orchestrator at all (Android, after the on-device
     * models were measured) without `extractTurningPoints` having to become public API.
     */
    fun composeFor(request: GameSummaryRequest): String = compose(
        GameSummaryPromptBuilder.extractTurningPoints(
            request.moveHistory,
            request.playerSide,
            request.engineDifficultyName,
        ),
    )

    /** `extractTurningPoints` caps at 3, so [compose]'s lead never needs a larger number. */
    const val CLEAN_GAME = "No major mistakes this game — you kept the position under control throughout."
}
