package com.example.ondeviceai

/**
 * The Game Summary response validator: citation fidelity, coverage, voice, move attribution.
 *
 * This surface had **no validator at all** — any non-blank text reached the user — and it is live on
 * iOS today. Measured over the 2026-08 50-game set, the rules here accept 37 of 43 AICore summaries
 * and 8 of 50 Foundation Models ones, which is the gap the benchmark describes: half of the iOS
 * answers carry no citation, a quarter are written in first person as the player, and two invent a
 * `[move-N]` that B16 would turn into a board jump to a ply that was never a turning point.
 *
 * A rejection routes to [GameSummaryGrounding], which is what ships today, so a false rejection
 * costs the user the wait and nothing else. That makes strictness cheap — but not free, and
 * `GameSummaryValidatorFieldTest` is the check that keeps it honest: it replays real accepted
 * output and fails if this file starts rejecting it.
 *
 * **Two rules were tried and removed**, both of which passed their own unit tests and produced zero
 * true positives against 43 real summaries:
 *
 *  - *Class fidelity* (rejecting a BLUNDER described as an inaccuracy) has no way to see negation,
 *    so it rejected *"While these aren't huge blunders…"* and *"[move-17] with Qc2 wasn't a
 *    blunder"*; and its text segmentation ran from each `[move-N]` to the next, so the last
 *    citation's segment swallowed the closing paragraph and matched *"focusing on those moments of
 *    inaccuracy"*. Severity paraphrase is a judgement about a whole clause, and matching words in a
 *    window is not that.
 *  - *Best-move-described-as-played* rejected the standard counterfactual: it required the engine's
 *    move to appear **after** "instead of", so *"opting for e4 instead of Nd2 would have been a
 *    stronger choice"* — which is correct English and correct chess — was flagged on six summaries.
 *
 * See `docs/benchmarks/on-device-ai/game-summary-2026-08.md`.
 */
object GameSummaryResponseValidator {

    /** Same shape as `MoveCoachResponseValidator.Result`; the reason names the failing rule. */
    sealed interface Result {
        data class Valid(val text: String) : Result
        data class Invalid(val reason: String) : Result
    }

    fun validate(rawText: String, request: GameSummaryRequest): Result {
        // Reused rather than reimplemented: the coach's normalize/split/dedup already handle the
        // chess-text traps (a `.` inside a move number, an unterminated <think> block, a few-shot
        // label). A second copy in this file would drift from the one the coach's tests cover.
        val text = MoveCoachResponseValidator.normalize(rawText)
        if (text.isEmpty()) {
            val unterminatedThink = rawText.contains("<think>", ignoreCase = true) &&
                !rawText.contains("</think>", ignoreCase = true)
            return Result.Invalid(
                if (unterminatedThink) "model spent its entire budget reasoning and never answered"
                else "empty response",
            )
        }

        val deduped = MoveCoachResponseValidator.deduplicateSentences(text)
        val fitted = MoveCoachResponseValidator.trimToBudget(deduped, MAX_SUMMARY_CHARS)
            ?: return Result.Invalid("no complete sentence fits in $MAX_SUMMARY_CHARS chars")

        val lower = fitted.lowercase()
        FORBIDDEN_PHRASES.firstOrNull { lower.contains(it) }?.let {
            return Result.Invalid("forbidden phrase: $it")
        }

        val turningPoints = GameSummaryPromptBuilder.extractTurningPoints(
            request.moveHistory,
            request.playerSide,
            request.engineDifficultyName,
        )

        validateCitationSet(fitted, turningPoints)?.let { return Result.Invalid(it) }
        validateCitationCoverage(fitted, turningPoints)?.let { return Result.Invalid(it) }
        validateMoveAttribution(fitted, turningPoints, request)?.let { return Result.Invalid(it) }
        validateVoice(fitted)?.let { return Result.Invalid(it) }
        validatePieceType(fitted, turningPoints, request)?.let { return Result.Invalid(it) }

        return Result.Valid(fitted)
    }

    /** Well above the 541-char median and 695-char maximum measured over 43 accepted summaries. */
    private const val MAX_SUMMARY_CHARS = 1000

    private val FORBIDDEN_PHRASES = listOf(
        "stockfish thinks", "engine depth", "probably depth", "elo ", "rating of",
    )

    private val CITATION_REGEX = Regex("\\[move-(\\d+)\\]")

    /**
     * Every `[move-N]` must be one of the request's turning-point plies.
     *
     * The hard rule of this surface. B16 turns the tag into a tappable board jump, so a fabricated
     * one silently navigates the user to a ply that was never a turning point — the single defect
     * here that a reader cannot detect. Foundation Models did it twice in 50.
     */
    internal fun validateCitationSet(
        text: String,
        turningPoints: List<GameSummaryPromptBuilder.TurningPoint>,
    ): String? {
        val valid = turningPoints.map { it.ply }.toSet()
        CITATION_REGEX.findAll(text)
            .mapNotNull { it.groupValues[1].toIntOrNull() }
            .firstOrNull { it !in valid }
            ?.let { return "invented citation: [move-$it]" }
        return null
    }

    private val NUMBER_WORDS = mapOf("one" to 1, "two" to 2, "three" to 3, "four" to 4)

    private val COUNT_NOUNS = listOf("mistake", "turning point", "moment", "inaccurac", "error", "blunder")

    /** Adjectives the model puts between the count and the noun ("two *significant* mistakes"). */
    private val COUNT_ADJECTIVES = listOf("", "significant ", "major ", "small ", "critical ", "key ", "big ")

    /**
     * Rejects a summary that cites fewer than all the turning points, or announces a count that
     * disagrees with them.
     *
     * The coverage half is the only rule that fires on Android — 6 of 43, every one a real partial
     * summary. It is a deliberate product choice rather than a truth check: a summary naming one of
     * three turning points is not false, it is incomplete, and the composed floor names all three.
     */
    internal fun validateCitationCoverage(
        text: String,
        turningPoints: List<GameSummaryPromptBuilder.TurningPoint>,
    ): String? {
        if (turningPoints.isEmpty()) return null
        val lower = text.lowercase()

        for ((word, count) in NUMBER_WORDS) {
            if (count == turningPoints.size) continue
            for (noun in COUNT_NOUNS) {
                if (COUNT_ADJECTIVES.any { lower.contains("$word $it$noun") }) {
                    return "claims $count ${noun}s, but there are ${turningPoints.size} turning points"
                }
            }
        }

        val cited = CITATION_REGEX.findAll(text).mapNotNull { it.groupValues[1].toIntOrNull() }.toSet()
        if (cited.isEmpty()) return "no [move-N] citation"
        val required = turningPoints.map { it.ply }.toSet()
        if (!cited.containsAll(required)) {
            return "cited ${cited.size} of ${required.size} turning points"
        }
        return null
    }

    /**
     * Any move token in the text, including bare pawn moves. Used to build the *allowed* set, where
     * over-matching is harmless.
     */
    private val SAN_PATTERN = Regex(
        "\\b(?:O-O(?:-O)?|[KQRBN][a-h1-8]?x?[a-h][1-8](?:=[QRBN])?[+#]?" +
            "|[a-h]x[a-h][1-8](?:=[QRBN])?[+#]?|[a-h][1-8](?:=[QRBN])?[+#]?)\\b",
    )

    /**
     * Move tokens that cannot also be a square name — a piece move, a capture, a castle, a
     * promotion.
     *
     * Used for the *invented move* check, where over-matching is not harmless: the bare-pawn form is
     * indistinguishable from prose referring to a square, and rejected a correct summary for saying
     * *"Sacrificing the Bishop on g6"*. The cost of the narrower pattern is that an invented **pawn**
     * move goes unflagged; the alternative rejected true sentences, and this surface's fallback is a
     * complete answer, not an error.
     */
    private val UNAMBIGUOUS_SAN_PATTERN = Regex(
        "\\b(?:O-O(?:-O)?|[KQRBN][a-h1-8]?x?[a-h][1-8](?:=[QRBN])?[+#]?" +
            "|[a-h]x[a-h][1-8](?:=[QRBN])?[+#]?|[a-h][1-8]=[QRBN][+#]?)\\b",
    )

    private val CAUSAL_MARKERS = listOf("because", "since ", "as it ", "as this ", "in order to", "so that", "due to")

    /**
     * Rejects a move that appears in neither the game nor the engine's suggestions, and a causal
     * claim attached to the engine's preferred move.
     *
     * The second half is `MoveCoachResponseValidator.validateBetterMoveAttribution`'s rule: the facts
     * say the engine preferred a move, never *why*, so a sentence that explains the engine's choice
     * without mentioning the played move is inventing the reason.
     */
    internal fun validateMoveAttribution(
        text: String,
        turningPoints: List<GameSummaryPromptBuilder.TurningPoint>,
        request: GameSummaryRequest,
    ): String? {
        val fromPgn = if (request.pgn.isBlank()) emptySet() else {
            SAN_PATTERN.findAll(request.pgn).map { cleanSan(it.value) }.toSet()
        }
        val allowed = (
            turningPoints.map { cleanSan(it.san) } +
                turningPoints.mapNotNull { it.bestMoveSan }.map { cleanSan(it) } +
                request.moveHistory.map { cleanSan(it.san) } +
                fromPgn
            ).toSet()

        UNAMBIGUOUS_SAN_PATTERN.findAll(text)
            .map { cleanSan(it.value) }
            .firstOrNull { it !in allowed }
            ?.let { return "move not played and not suggested: $it" }

        for (tp in turningPoints) {
            val best = tp.bestMoveSan?.let { cleanSan(it) }?.lowercase() ?: continue
            val played = cleanSan(tp.san).lowercase()
            for (sentence in MoveCoachResponseValidator.splitSentences(text)) {
                val lower = sentence.lowercase()
                if (!containsMoveToken(lower, best)) continue
                if (played.isNotEmpty() && containsMoveToken(lower, played)) continue
                if (CAUSAL_MARKERS.any { lower.contains(it) }) {
                    return "explains why the engine's move was better, which the facts do not say"
                }
            }
        }
        return null
    }

    private fun cleanSan(san: String): String = san.trim().removeSuffix("+").removeSuffix("#")

    /**
     * Rejects first-person-singular voice.
     *
     * Foundation Models writes the summary **as the player** — *"I made two significant mistakes in
     * this game"* — in 26 of 50, and at the extreme stops summarising altogether: *"I apologize for
     * the mistakes I made in the game… I will try to improve."* A coach summary addresses the
     * player.
     *
     * "we"/"our" are deliberately allowed: AICore writes *"we could have played more precisely"*,
     * which is a coach speaking with the player, not as them. "me" is left out of the list so
     * *"let me break this down"* is not a rejection.
     */
    internal fun validateVoice(text: String): String? {
        val firstPerson = setOf("i", "i'm", "i've", "i'll", "i'd", "my", "mine", "myself")
        text.lowercase().split(Regex("[^a-z0-9']+")).firstOrNull { it in firstPerson }
            ?.let { return "first person: '$it'" }
        return null
    }

    private val PIECE_NOUNS = mapOf(
        'K' to "king", 'Q' to "queen", 'R' to "rook", 'B' to "bishop", 'N' to "knight",
    )

    /**
     * Rejects a piece noun that belongs to no move in the summary's own facts.
     *
     * Catches *"your queen was trapped"* on a game whose turning points are a pawn and a rook move.
     * It does **not** catch structural decoration — *"a blunder that weakened your pawn structure"*
     * about a knight move passes, because "pawn" is usually reachable from some move in the game —
     * and the plan says so explicitly rather than claiming coverage it does not have.
     */
    internal fun validatePieceType(
        text: String,
        turningPoints: List<GameSummaryPromptBuilder.TurningPoint>,
        request: GameSummaryRequest,
    ): String? {
        val allowed = mutableSetOf<String>()

        fun allow(san: String) {
            val clean = san.trim()
            if (clean.startsWith("O-O")) {
                allowed += listOf("king", "rook")
                return
            }
            when (val first = clean.firstOrNull { it.isLetter() }) {
                in PIECE_NOUNS.keys -> allowed += PIECE_NOUNS.getValue(first!!)
                in 'a'..'h' -> allowed += "pawn"
                else -> {}
            }
            PIECE_NOUNS.forEach { (letter, noun) -> if (clean.contains("=$letter")) allowed += noun }
        }

        val sources = turningPoints.map { it.san } +
            turningPoints.mapNotNull { it.bestMoveSan } +
            request.moveHistory.map { it.san }
        sources.forEach(::allow)
        turningPoints.forEach { tp ->
            PIECE_NOUNS.values.forEach { noun -> if (tp.intuition.contains(noun, ignoreCase = true)) allowed += noun }
        }

        val words = text.lowercase().split(Regex("[^a-z]+")).toSet()
        (PIECE_NOUNS.values + "pawn").firstOrNull { noun ->
            noun !in allowed && (noun in words || "${noun}s" in words)
        }?.let { return "names a $it, which no move in this game involves" }
        return null
    }

    /** Local because the coach's copy is private; same boundary rule ("e4" must not match "e4xd5"). */
    private fun containsMoveToken(text: String, move: String): Boolean {
        if (move.isEmpty()) return false
        var index = text.indexOf(move)
        while (index >= 0) {
            val before = text.getOrNull(index - 1)
            val after = text.getOrNull(index + move.length)
            if ((before == null || !before.isLetterOrDigit()) && (after == null || !after.isLetterOrDigit())) {
                return true
            }
            index = text.indexOf(move, index + 1)
        }
        return false
    }
}
