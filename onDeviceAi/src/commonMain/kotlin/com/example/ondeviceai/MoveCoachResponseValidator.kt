package com.example.ondeviceai

/**
 * Gates honesty, echoed scaffolding, restatement, length, grounding, and — since the move to a
 * model fluent enough to assert things — reason-faithfulness and piece-type.
 *
 * See `docs/benchmarks/on-device-ai/move-coach-quality-axes.md`, which measured the last two failing
 * on 3/10 and 1/10 while every case passed this validator. It still does not check whether the
 * *reasoning* is sound, only whether the claims are supported by the supplied facts.
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
        if (text.isEmpty()) {
            // Distinguished because the two want opposite fixes and looked identical in the log:
            // "empty response" sends you hunting for a broken generator, when the generator worked
            // fine and simply ran out of budget mid-deliberation. See MAX_OUTPUT_TOKENS_STRICT.
            val unterminatedThink = rawText.contains("<think>", ignoreCase = true) &&
                !rawText.contains("</think>", ignoreCase = true)
            return Result.Invalid(
                if (unterminatedThink) "model spent its entire budget reasoning and never answered"
                else "empty response",
            )
        }
        val dedupedText = deduplicateSentences(text)
        
        // Length runs first, after deduplication, and **trims rather than rejects**.
        //
        // Every rule below is a quality judgement — an echoed example, an ungrounded answer and a
        // forbidden phrase are all things the user must not see. Length is not: it is a layout
        // constraint, the panel sits under a board on a phone. Rejecting on it threw away answers
        // that were correct, grounded and about the right move for being a sentence too long, and
        // handed the user the template instead.
        //
        // Trimming must happen *before* quality checks, so that we validate exactly what the user
        // will see. Otherwise, an answer might pass the `explains` check because of a concept word
        // in a sentence that is subsequently trimmed away.
        val fitted = trimToBudget(dedupedText, MoveCoachPromptBuilder.MAX_OUTPUT_CHARS)
            ?: return Result.Invalid(
                "no complete sentence fits in ${MoveCoachPromptBuilder.MAX_OUTPUT_CHARS} chars",
            )

        val lower = fitted.lowercase()
        FORBIDDEN_PHRASES.firstOrNull { lower.contains(it) }?.let { phrase ->
            return Result.Invalid("forbidden phrase: $phrase")
        }
        // Small models copy the prompt's few-shot examples instead of describing the move (observed
        // on-device: gemma3-270m returned style example #1 plus the old `Bad:` filler, verbatim).
        // Rejecting here routes the orchestrator straight to the deterministic fallback (there is
        // no retry loop — a validation failure emits MoveCoachFallback immediately), which is
        // actually about *this* move. Never show the user the prompt back.
        if (isEchoedScaffolding(fitted)) {
            return Result.Invalid("echoed a prompt example instead of describing the move")
        }
        if (isEchoedPrompt(fitted, request)) {
            return Result.Invalid("echoed the prompt instead of answering it")
        }
        // Grounded *and* explanatory. Naming the move used to be enough on its own, so "You played
        // Nf3." passed: grounded, short, not a forbidden phrase, not a verbatim prompt line — and
        // worth nothing. That mattered little while the fallback was "The position stays roughly
        // balanced.", and matters a lot now the fallback names the pieces involved, because an
        // accepted restatement *replaces* a sentence that actually said something.
        //
        // So the bar is a concept, not a noun. Piece names and squares are the "what", which the
        // user watched themselves play; only [CONCEPT_VOCAB] is the "why".
        val tokens = groundingTokens(request)
        val mentionsMove = tokens.any { lower.contains(it) }
        val explains = CONCEPT_VOCAB.any { lower.contains(it) }
        if (!mentionsMove && !explains) {
            return Result.Invalid("response is not grounded in the move or chess vocabulary")
        }
        if (!explains) {
            return Result.Invalid("restates the move without explaining it")
        }

        val reasonFaithfulnessError = validateReasonFaithfulness(fitted, request)
        if (reasonFaithfulnessError != null) {
            return Result.Invalid(reasonFaithfulnessError)
        }

        val pieceTypeError = validatePieceType(fitted, request)
        if (pieceTypeError != null) {
            return Result.Invalid(pieceTypeError)
        }

        return Result.Valid(fitted)
    }

    /** Capture claims that name what was taken. "takes space" is not one of these. */
    private val CAPTURE_OBJECT_PHRASES = listOf(
        "takes the", "takes a ", "take the", "wins the", "wins a ", "grabs the", "snags the",
    )

    private fun containsWord(text: String, word: String): Boolean {
        return text.lowercase().split(Regex("[^a-z0-9]+")).contains(word)
    }

    internal fun validateReasonFaithfulness(text: String, request: MoveCoachRequest): String? {
        val moveLower = request.moveDisplay.lowercase()
        val hasCheckFact = moveLower.contains("+") || moveLower.contains("#") || request.motifs.contains("check") || request.motifs.contains("checkmate")
        val hasMateFact = moveLower.contains("#") || request.motifs.contains("checkmate")
        val hasCaptureFact = moveLower.contains("x") || request.motifs.any { it in setOf("capture", "recapture", "material-swing", "hangs-piece") }

        if (!hasMateFact && (containsWord(text, "mate") || containsWord(text, "checkmate"))) {
            return "unsupported claim: mate"
        }
        if (!hasCheckFact && containsWord(text, "check")) {
            return "unsupported claim: check"
        }
        // "takes"/"take" as bare words are not capture claims — "takes space", "takes control" and
        // "takes aim" are ordinary chess prose, and rejecting them threw away good answers. Only a
        // capture *with an object* counts, alongside the unambiguous verbs.
        val lower = text.lowercase()
        val claimsCapture = containsWord(text, "capture") || containsWord(text, "captures") ||
            containsWord(text, "material") || CAPTURE_OBJECT_PHRASES.any { it in lower }
        if (!hasCaptureFact && claimsCapture) {
            return "unsupported claim: capture/material"
        }

        return null
    }

    internal fun validatePieceType(text: String, request: MoveCoachRequest): String? {
        val allPieces = setOf("pawn", "pawns", "knight", "knights", "bishop", "bishops", "rook", "rooks", "queen", "queens", "king", "kings")
        val allowed = mutableSetOf<String>()

        fun addAllowed(moveStr: String) {
            val clean = moveStr.trim()
            if (clean.startsWith("O-O")) {
                allowed.addAll(listOf("king", "kings", "rook", "rooks"))
                return
            }
            val first = clean.firstOrNull { it.isLetter() }
            when (first) {
                'K' -> allowed.addAll(listOf("king", "kings"))
                'Q' -> allowed.addAll(listOf("queen", "queens"))
                'R' -> allowed.addAll(listOf("rook", "rooks"))
                'B' -> allowed.addAll(listOf("bishop", "bishops"))
                'N' -> allowed.addAll(listOf("knight", "knights"))
                in 'a'..'h' -> allowed.addAll(listOf("pawn", "pawns"))
            }
            if (clean.contains("=Q")) allowed.addAll(listOf("queen", "queens"))
            if (clean.contains("=R")) allowed.addAll(listOf("rook", "rooks"))
            if (clean.contains("=B")) allowed.addAll(listOf("bishop", "bishops"))
            if (clean.contains("=N")) allowed.addAll(listOf("knight", "knights"))
        }

        addAllowed(request.moveDisplay)
        request.betterMoveDisplay?.takeIf { it.isNotBlank() }?.let { addAllowed(it) }

        // Every piece the *prompt* names is fair game, not just the one that moved. The deterministic
        // line now says "Your bishop on b5 pins the knight on c6 against the king on e8", and a model
        // repeating those pieces is being faithful — under a mover-only rule it was rejected for
        // naming the knight it had just been told about. The same applies to a captured piece: with
        // "It takes the bishop on c4" in the facts, "it captures the bishop" is supported, and
        // without it that claim really is invented.
        val prose = (request.deterministicHeadline + " " + request.deterministicExplanation).lowercase()
        allPieces.forEach { if (containsWord(prose, it)) allowed.add(it) }

        for (piece in allPieces) {
            if (piece !in allowed && containsWord(text, piece)) {
                return "named a piece other than the one moved: $piece"
            }
        }
        return null
    }

    /**
     * The longest whole-sentence prefix of [text] that fits in [budget], or null if not even the
     * first sentence does.
     *
     * Null means the model produced one unbroken run-on longer than the whole panel budget, which is
     * degenerate output rather than a long answer — the deterministic line is genuinely better, so
     * that is the one case that still rejects.
     *
     * Cuts a prefix of the original string instead of splitting and rejoining. [deduplicateSentences]
     * documents why: a rejoin injects a space into "0.5" and "e.g.". For the same reason a period is
     * only a boundary when it is followed by whitespace or ends the text, and never when it follows
     * a digit — otherwise "1. e4" and "0.5" both read as sentence ends.
     */
    internal fun trimToBudget(text: String, budget: Int): String? {
        if (text.length <= budget) return text
        var cut = -1
        for (i in 0 until minOf(text.length, budget)) {
            val c = text[i]
            if (c != '.' && c != '!' && c != '?') continue
            if (c == '.' && i > 0 && text[i - 1].isDigit()) continue
            if (i < text.lastIndex && !text[i + 1].isWhitespace()) continue
            cut = i
        }
        if (cut < 0) return null
        return text.substring(0, cut + 1).trim()
    }

    /**
     * Words that carry an *explanation*, as opposed to naming what moved where.
     *
     * Deliberately excludes piece names and the bare positional nouns ("square", "file", "rank"):
     * "You played the knight to f3" contains a piece name and still tells the reader nothing they
     * did not just do themselves. Every entry here asserts something about the position.
     *
     * Erring toward rejection is correct while the fallback is the better writer. A model that
     * cannot clear this bar has not earned the panel, and `DeterministicCoach` will say something
     * specific in its place.
     */
    internal val CONCEPT_VOCAB = listOf(
        "develop", "center", "centre", "attack", "defend", "defence", "defense",
        "control", "threat", "pressure", "castle", "promot", "capture", "check",
        "space", "pin", "fork", "skewer", "battery", "open", "block", "trade",
        "material", "tempo", "safe", "weak", "strong", "activ", "cover", "support",
        "undefended", "hanging", "outpost", "initiative", "solid", "positional",
        "win", "chance", "better"
    )

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
        val cleanedText = stripThinkBlocks(rawText)
        val filteredLines = cleanedText.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { unwrapQuotes(stripFewShotLabel(it)) }
            .filter { it.isNotEmpty() }
            .joinToString(" ")
            .trim()
        
        return stripConversationalFiller(filteredLines)
    }

    private fun stripThinkBlocks(text: String): String {
        var result = text
        while (true) {
            val start = result.indexOf("<think>", ignoreCase = true)
            if (start == -1) break
            val end = result.indexOf("</think>", startIndex = start, ignoreCase = true)
            if (end != -1) {
                result = result.substring(0, start) + result.substring(end + 8)
            } else {
                result = result.substring(0, start)
                break
            }
        }
        return result
    }

    /**
     * Strips leading few-shot labels, repeatedly — a model that echoes "Good: Bad: …" needs more
     * than one pass, and restarting the scan after each hit means the labels are order-independent
     * rather than only stripping in list order.
     */
    private fun stripFewShotLabel(line: String): String {
        var result = line
        var strippedSomething = true
        while (strippedSomething) {
            strippedSomething = false
            for (label in FEW_SHOT_LABELS) {
                if (result.regionMatches(0, label, 0, label.length, ignoreCase = true)) {
                    result = result.substring(label.length).trim()
                    strippedSomething = true
                    break
                }
            }
        }
        return result
    }

    /**
     * Drops a leading conversational preamble ("Okay, here's the explanation:") so the answer
     * starts at the answer.
     *
     * No regex, deliberately — the same rule [normalize] documents. An inline `(?i)` flag in
     * particular does not survive the trip: Kotlin/JS lowers `Regex` onto JS `RegExp`, which has no
     * inline-flag syntax, so a pattern written that way throws on the target the React Native port
     * consumes. Prefix matching over a fixed list needs none of it.
     *
     * The preamble is only removed when what follows it is non-empty: a response that is *entirely*
     * filler should fail validation as blank rather than be silently emptied here.
     */
    private fun stripConversationalFiller(text: String): String {
        for (opener in CONVERSATIONAL_OPENERS) {
            if (!text.regionMatches(0, opener, 0, opener.length, ignoreCase = true)) continue
            // The preamble runs to the first sentence end; anything past that is the answer.
            val end = text.indexOfFirst { it in FILLER_TERMINATORS }
            if (end == -1 || end == text.lastIndex) return text
            val remainder = text.substring(end + 1).trimStart()
            return if (remainder.isEmpty()) text else remainder
        }
        return text
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

    /**
     * True if [text] reproduces a line of the prompt it was given.
     *
     * The third time this model has answered by copying the nearest text in its context: first the
     * style examples, then the `Bad:` counter-example added to forbid them, then the JSON schema's
     * placeholder strings. Each time the copied thing was removed and it moved on to the next. With
     * all of those gone it started returning the *facts block* — verbatim, on-device:
     *
     *     The player just played hxg3. Engine assessment of that move: best. Tactical features
     *     detected: discovered attack, pin. Baseline explanation: "It pins a good piece against a
     *     more valuable one."
     *
     * [isEchoedScaffolding] did not catch it: that only knows the one filler sentence. Nothing
     * checked the far more basic property the comment above it already claims — *never show the
     * user the prompt back*.
     *
     * Lines are compared as fingerprints and derived from the builder, never hand-copied, so
     * rephrasing the prompt cannot leave this matching a line that no longer exists. Short lines are
     * skipped: a blank or a two-word line would match ordinary prose by accident.
     *
     * Note this cannot fire on an answer that merely reuses the *baseline explanation* — the prompt
     * line carries its `Baseline explanation:` label, so the fingerprints differ. Returning the
     * baseline in the model's own words is a weak answer, not an echo.
     */
    private fun isEchoedPrompt(text: String, request: MoveCoachRequest): Boolean {
        val fingerprint = fingerprintOf(text)
        if (fingerprint.isEmpty()) return false
        return MoveCoachPromptBuilder.userPrompt(request)
            .lineSequence()
            .map(::fingerprintOf)
            .filter { it.length >= MIN_ECHOED_LINE_CHARS }
            .any { fingerprint.contains(it) }
    }

    /** Below this a prompt line is too short to distinguish an echo from a coincidence. */
    private const val MIN_ECHOED_LINE_CHARS = 20

    private fun fingerprintOf(s: String): String =
        s.lowercase().filter { it.isLetterOrDigit() }

    private val FEW_SHOT_LABELS = listOf(
        // Retained so responses shaped by the older Good:/Bad: prompt are still cleaned.
        "Good:",
        "Bad:",
    )

    /**
     * Openers a chat-tuned model uses before getting to the answer. Prefixes only — matching these
     * anywhere in the text would cut sentences out of the middle of a legitimate explanation.
     */
    private val CONVERSATIONAL_OPENERS = listOf(
        "okay", "ok,", "ok ", "sure", "certainly", "of course",
        "here is", "here's", "let me", "let's", "i understand",
    )

    /** What ends a preamble. A colon counts: "Here's the explanation:" is all preamble. */
    private const val FILLER_TERMINATORS = ".!?:\n"

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
