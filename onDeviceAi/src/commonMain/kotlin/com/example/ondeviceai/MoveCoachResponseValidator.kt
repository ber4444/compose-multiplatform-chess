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
        val dedupedText = deduplicateSentences(text)
        val lower = dedupedText.lowercase()
        FORBIDDEN_PHRASES.firstOrNull { lower.contains(it) }?.let { phrase ->
            return Result.Invalid("forbidden phrase: $phrase")
        }
        // Small models copy the prompt's few-shot examples instead of describing the move (observed
        // on-device: gemma3-270m returned style example #1 plus the old `Bad:` filler, verbatim).
        // Rejecting here routes the orchestrator straight to the deterministic fallback (there is
        // no retry loop — a validation failure emits MoveCoachFallback immediately), which is
        // actually about *this* move. Never show the user the prompt back.
        if (isEchoedScaffolding(dedupedText)) {
            return Result.Invalid("echoed a prompt example instead of describing the move")
        }
        // Grounding check: accept if the response mentions the move (UCI squares,
        // display text) OR contains chess-relevant vocabulary (piece names,
        // tactical terms).
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
        // Length check runs after deduplication so a verbatim sentence repeat (e.g. Gemini Nano)
        // is deduplicated into a valid response rather than rejected for length.
        if (dedupedText.length > MoveCoachPromptBuilder.MAX_OUTPUT_CHARS) {
            return Result.Invalid("response exceeds ${MoveCoachPromptBuilder.MAX_OUTPUT_CHARS} chars")
        }
        return Result.Valid(dedupedText)
    }

    /**
     * Splits sentences character-by-character without regex lookbehinds for KMP / JS IR compatibility.
     */
    internal fun splitSentences(text: String): List<String> {
        if (text.isEmpty()) return emptyList()
        val sentences = mutableListOf<String>()
        val sb = StringBuilder()
        for (char in text) {
            sb.append(char)
            if (char == '.' || char == '!' || char == '?') {
                val s = sb.toString().trim()
                if (s.isNotEmpty()) sentences.add(s)
                sb.clear()
            }
        }
        val remainder = sb.toString().trim()
        if (remainder.isNotEmpty()) {
            sentences.add(remainder)
        }
        return sentences
    }

    /**
     * Deduplicates verbatim repeated sentences in place (B15).
     */
    internal fun deduplicateSentences(text: String): String {
        val sentences = splitSentences(text)
        if (sentences.size <= 1) return text
        val uniqueSentences = mutableListOf<String>()
        val seen = mutableSetOf<String>()
        for (sentence in sentences) {
            val key = sentence.lowercase().filter { it.isLetterOrDigit() }
            if (key.isNotEmpty() && !seen.add(key)) {
                continue
            }
            uniqueSentences.add(sentence)
        }
        // Nothing was a duplicate, so return the input untouched rather than a split/rejoin of it.
        // splitSentences breaks on every '.', including ones inside "0.5" or "e.g.", and rejoining
        // with " " would inject a space there ("You are up 0. 5 pawns."). Only pay that risk when
        // there is actually a duplicate to drop.
        if (uniqueSentences.size == sentences.size) return text
        return uniqueSentences.joinToString(" ")
    }

    /**
     * Clean the model's few-shot echo before validating/displaying. Plain string ops, no regex,
     * so behavior is identical on every JVM/JS/Wasm/Native/Android runtime (and can't hit the ICU-vs-JVM regex divergence).
     */
    internal fun normalize(rawText: String): String {
        val filteredLines = rawText.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { unwrapQuotes(stripFewShotLabel(it)) }
            .filter { it.isNotEmpty() }
            .joinToString(" ")
            .trim()
        
        return stripConversationalFiller(filteredLines)
    }

    private fun stripFewShotLabel(line: String): String {
        var result = line
        for (label in FEW_SHOT_LABELS) {
            if (result.regionMatches(0, label, 0, label.length, ignoreCase = true)) {
                result = result.substring(label.length).trim()
            }
        }
        return result
    }

    private fun stripConversationalFiller(text: String): String =
        text.replace(Regex("^(?i)(okay|sure|certainly|here is|here's|let me|let's|i understand)[^.!?\\n]*[.!?:]+\\s*"), "")

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
        /**
         * [reason] is a *diagnostic*, not a routed [AiRoutePolicyDecider.FallbackReason]: it names
         * which rule the text broke ("forbidden phrase: …") for the log line. Every rejection maps
         * to the single product state [AiRoutePolicyDecider.FallbackReason.Validation] — the caller
         * decides that, not the validator.
         */
        data class Invalid(val reason: String) : Result
    }
}
