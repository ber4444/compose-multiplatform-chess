package com.example.ondeviceai

/**
 * Gates honesty, echoed scaffolding, length, and grounding — it does not check reason-faithfulness
 * or piece-type accuracy. See `docs/benchmarks/on-device-ai/move-coach-quality-axes.md` for measured
 * cases that pass here while failing those two axes.
 */
object MoveCoachResponseValidator {

    val FORBIDDEN_PHRASES = listOf(
        "i think stockfish",
        "probably depth",
        "stockfish thinks",
        "engine depth",
        "elo ",
        "rating of",
    )

    fun groundingTokens(request: MoveCoachRequest): List<String> {
        val tokens = mutableSetOf<String>()
        val uci = request.moveUci.lowercase()
        if (uci.isNotEmpty()) {
            tokens += uci
            if (uci.length >= 4) {
                tokens += uci.substring(0, 2)
                tokens += uci.substring(2, 4)
            }
        }
        request.moveDisplay.lowercase()
            .replace(Regex("[^a-z0-9]"), " ")
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() }
            .forEach { tokens += it }
        return tokens.toList()
    }

    fun validate(rawText: String, request: MoveCoachRequest): Result {
        val text = normalize(rawText)
        if (text.isEmpty()) return Result.Invalid("empty response")
        val lower = text.lowercase()
        FORBIDDEN_PHRASES.firstOrNull { lower.contains(it) }?.let { phrase ->
            return Result.Invalid("forbidden phrase: $phrase")
        }
        // Small models copy the prompt's few-shot examples instead of describing the move (observed
        // on-device: gemma3-270m returned style example #1 plus the old `Bad:` filler, verbatim).
        // Rejecting here routes the orchestrator to its retry, then to the deterministic fallback —
        // both of which are actually about *this* move. Never show the user the prompt back.
        if (isEchoedScaffolding(text)) {
            return Result.Invalid("echoed a prompt example instead of describing the move")
        }
        // B15: Repetition check runs before length check so a repeated sentence loop (e.g. Gemini Nano)
        // is caught as a repetition loop rather than misreported as a length violation.
        if (hasRepeatedLoop(text)) {
            return Result.Invalid("repeated sentence loop detected")
        }
        // Grounding check: accept if the response mentions the move (UCI squares,
        // display text) OR contains chess-relevant vocabulary (piece names,
        // tactical terms). This is permissive enough to let natural-language
        // explanations through ("Develops the knight toward the center") while
        // still rejecting completely irrelevant output.
        val tokens = groundingTokens(request)
        val chessVocab = listOf(
            "knight", "bishop", "rook", "queen", "king", "pawn",
            "develop", "center", "centre", "attack", "defend", "defends",
            "control", "threat", "pressure", "castle", "promot",
            "capture", "check", "space", "square", "file", "rank",
            "pin", "fork", "skewer", "battery", "open", "block",
        )
        val mentionsMove = tokens.any { lower.contains(it) }
        val mentionsChess = chessVocab.any { lower.contains(it) }
        if (!mentionsMove && !mentionsChess) {
            return Result.Invalid("response is not grounded in the move or chess vocabulary")
        }
        // Length check runs LAST so grounding and repetition checks take precedence.
        if (text.length > MoveCoachPromptBuilder.MAX_OUTPUT_CHARS) {
            return Result.Invalid("response exceeds ${MoveCoachPromptBuilder.MAX_OUTPUT_CHARS} chars")
        }
        return Result.Valid(text)
    }

    /**
     * True if [text] contains repeated sentence loops (B15).
     */
    internal fun hasRepeatedLoop(text: String): Boolean {
        val sentences = text.split(Regex("(?<=[.!?])\\s+"))
            .map { it.trim().lowercase() }
            .filter { it.length >= 10 }
        if (sentences.size < 2) return false
        val seen = mutableSetOf<String>()
        for (sentence in sentences) {
            if (!seen.add(sentence)) return true
        }
        return false
    }

    /**
     * Clean the model's few-shot echo and strip raw citation tags (B4) before validating/displaying.
     */
    internal fun normalize(rawText: String): String {
        val sanitized = CitationSanitizer.sanitize(rawText)
        return sanitized.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { unwrapQuotes(stripFewShotLabel(it)) }
            .filter { it.isNotEmpty() }
            .joinToString(" ")
            .trim()
    }

    private fun stripFewShotLabel(line: String): String {
        for (label in FEW_SHOT_LABELS) {
            if (line.regionMatches(0, label, 0, label.length, ignoreCase = true)) {
                return line.substring(label.length).trim()
            }
        }
        return line
    }

    private fun unwrapQuotes(s: String): String =
        if (s.length >= 2 && s.first() == '"' && s.last() == '"') s.substring(1, s.length - 1).trim() else s

    /**
     * True if [text] reproduces the prompt's scaffolding — a style example or the generic filler
     * sentence — rather than describing the move. Compared on a letters-and-digits-only fingerprint
     * so punctuation, casing, and stray quoting don't let a copy slip through, and with `contains`
     * so a response that pads an echo with a few words is still caught.
     */
    private fun isEchoedScaffolding(text: String): Boolean {
        val fingerprint = fingerprintOf(text)
        if (fingerprint.isEmpty()) return false
        return SCAFFOLDING_FINGERPRINTS.any { fingerprint.contains(it) }
    }

    private fun fingerprintOf(s: String): String =
        s.lowercase().filter { it.isLetterOrDigit() }

    private val FEW_SHOT_LABELS = listOf(
        // Retained so responses shaped by the older Good:/Bad: prompt are still cleaned.
        "Good:",
        "Bad:",
    )

    /** Fingerprints of every sentence the prompt shows the model. Derived, never hand-copied. */
    private val SCAFFOLDING_FINGERPRINTS: List<String> =
        listOf(MoveCoachPromptBuilder.GENERIC_FILLER)
            .map { it.lowercase().filter(Char::isLetterOrDigit) }
            .filter { it.isNotEmpty() }

    sealed interface Result {
        data class Valid(val text: String) : Result
        data class Invalid(val reason: String) : Result
    }
}
