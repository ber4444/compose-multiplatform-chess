package com.example.evals

/**
 * Mechanical readability and tone rules scorer for coaching text (B14).
 *
 * Emits discriminating, string-checkable rules:
 * 1. [scoreReadability]: Flesch-Kincaid grade level bound (target <= 6.0).
 * 2. [scoreProcessPraise]: Rejects person praise ("you are a genius") in favor of process praise.
 * 3. [scoreCriticismNextStep]: Ensures criticism/mistake notes carry constructive next-step advice.
 * 4. [scoreNoSelfReference]: Rejects conversational filler ("I see", "I notice", "As an AI") using \b word boundaries.
 */
object FluencyScorer {

    data class FluencyResult(
        val gradeLevel: Double,
        val passesReadability: Boolean,
        val passesProcessPraise: Boolean,
        val passesCriticismNextStep: Boolean,
        val passesNoSelfReference: Boolean,
        val violations: List<String>,
    ) {
        val isCompliant: Boolean get() = violations.isEmpty()
    }

    /**
     * Per-surface reading-level bounds.
     *
     * **These are regression bounds, not aspirations.** They are calibrated against what the
     * *deterministic* composer for each surface actually produces, because that text is the shipped
     * floor (see the plan: "the deterministic path remains the floor, not the plan"). A model whose
     * output reads harder than the template it replaced is the regression worth catching; a single
     * absolute target is not, because the surfaces write differently by design — the move coach
     * emits one or two short instructional sentences, while the opening explainer quotes reference
     * passages complete with named openings ("Ruy Lopez", "Nimzo-Indian") that no rewrite removes.
     *
     * A single grade-6 bound across both failed 100% of `local-template` while `local-template-chat`
     * silently measured nothing — a column that is always red is as uninformative as one that is
     * always green.
     *
     * Calibration also cancels most of [countSyllables]'s imprecision: the same approximate measure
     * sets the bound and evaluates against it, so systematic bias affects both sides equally. Treat
     * the absolute numbers as ordinal, not as real US grade levels.
     *
     * Re-derive with `./gradlew :evals:run` and read the `Reading grade` column; see
     * `docs/benchmarks/on-device-ai/fluency-calibration.md`.
     */
    enum class FluencySurface(val maxGradeLevel: Double) {
        /**
         * One or two short sentences under a 300-char cap.
         * Floor: `fake-generator` p90 5.2 → bound 6.5. This is also the surface where the grade-6
         * product aspiration genuinely applies, so the bound is deliberately the tightest.
         */
        MOVE_COACH(6.5),

        /**
         * Two or three sentences quoting corpus passages, opening names included.
         * Floor: `local-template` p90 12.4, max 12.8 → bound 13.5.
         */
        OPENING(13.5),

        /**
         * Prose answers to a position question, bounded at 400 chars.
         * Floor: `local-template-chat` p90 12.5, max 18.4 → bound 13.5. The bound sits below the
         * max on purpose: that outlier is a genuinely dense sentence and is exactly what the gate
         * should flag, which is why this route reports a small non-zero violation rate rather than
         * a decorative 0%.
         */
        CHAT(13.5),
    }

    /**
     * Calculates Flesch-Kincaid grade level:
     * 0.39 * (words / sentences) + 11.8 * (syllables / words) - 15.59
     */
    fun scoreReadability(text: String, maxGradeLevel: Double = 6.0): Pair<Double, Boolean> {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return 0.0 to true

        // Mask decimals to avoid splitting numbers (e.g. "3.14") into two sentences.
        // Mask any period *preceded* by a digit — not just one between two digits. The corpus is
        // chess prose, so the dominant case is a move number ("1. e4 c5"), which is
        // digit-period-SPACE and slipped straight through a `(?=\d)` lookahead. Every such period
        // counted as a sentence boundary, inflating the sentence count and understating the
        // Flesch-Kincaid grade for exactly the rows that quote corpus passages. This is the same
        // rule OpeningExplanationValidator.splitSentences already applies, and the same bug it was
        // written to fix. Lookbehind is fine here: :evals is JVM-only.
        val masked = trimmed.replace(Regex("(?<=\\d)\\."), "<DEC>")
        val sentences = masked.split(Regex("[.!?]+")).filter { it.isNotBlank() }
        val sentenceCount = sentences.size.coerceAtLeast(1)

        val words = trimmed.lowercase().split(Regex("[^a-z0-9']+")).filter { it.isNotBlank() }
        val wordCount = words.size.coerceAtLeast(1)

        val totalSyllables = words.sumOf { countSyllables(it) }.coerceAtLeast(1)

        val gradeLevel = 0.39 * (wordCount.toDouble() / sentenceCount) +
            11.8 * (totalSyllables.toDouble() / wordCount) - 15.59

        val roundedGrade = (kotlin.math.round(gradeLevel * 10.0) / 10.0).coerceAtLeast(0.0)
        val passes = roundedGrade <= maxGradeLevel
        return roundedGrade to passes
    }

    /**
     * Estimates syllable count for an English word by counting vowel groups.
     *
     * Approximate by design — FK only needs an aggregate, and the bounds in [FluencySurface] are
     * calibrated with this same function, so its bias largely cancels. Do not invest in making it
     * exact; if the absolute grade ever needs to be trustworthy, use a dictionary (CMUdict), not a
     * better heuristic.
     */
    fun countSyllables(word: String): Int {
        val clean = word.lowercase().filter { it.isLetter() }
        if (clean.isEmpty()) return 1
        if (clean.length <= 3) return 1

        // Strip at most ONE trailing inflection, and only a silent "e". Chaining these
        // (removeSuffix("es").removeSuffix("s").removeSuffix("e")) over-strips: "pieces" became
        // "piec" and scored 1 syllable instead of 2.
        // "-es" is only silent after a non-sibilant ("moves" = mov-es, 1). After c/s/x/z/ch/sh it is
        // its own syllable ("pieces" = pie-ces, 2), so stripping it there under-counts.
        val esIsSilent = clean.endsWith("es") &&
            !clean.dropLast(2).endsWithAny("c", "s", "x", "z", "ch", "sh")
        val processed = when {
            esIsSilent -> clean.dropLast(2)
            clean.endsWith("e") && !clean.endsWith("le") -> clean.dropLast(1)
            clean.endsWith("s") && !clean.endsWith("ss") -> clean.dropLast(1)
            else -> clean
        }
        val vowelGroups = processed.split(Regex("[^aeiouy]+")).filter { it.isNotEmpty() }
        return vowelGroups.size.coerceAtLeast(1)
    }

    private fun String.endsWithAny(vararg suffixes: String): Boolean = suffixes.any { endsWith(it) }

    private val PERSON_PRAISE_PATTERNS = listOf(
        Regex("""\byou are a genius\b""", RegexOption.IGNORE_CASE),
        Regex("""\byou're a genius\b""", RegexOption.IGNORE_CASE),
        Regex("""\byou are brilliant\b""", RegexOption.IGNORE_CASE),
        Regex("""\byou're brilliant\b""", RegexOption.IGNORE_CASE),
        Regex("""\byou are smart\b""", RegexOption.IGNORE_CASE),
        Regex("""\byou're smart\b""", RegexOption.IGNORE_CASE),
        Regex("""\bbrilliant player\b""", RegexOption.IGNORE_CASE),
        Regex("""\bgenius player\b""", RegexOption.IGNORE_CASE),
    )

    fun scoreProcessPraise(text: String): Boolean {
        return PERSON_PRAISE_PATTERNS.none { pattern -> pattern.containsMatchIn(text) }
    }

    private val CRITICISM_WORDS = listOf(
        "blunder", "mistake", "bad move", "poor move", "inaccuracy", "error",
    )
    private val NEXT_STEP_WORDS = listOf(
        "instead", "should", "try", "look to", "play", "develop", "defend", "protect", "control", "consider",
    )

    fun scoreCriticismNextStep(text: String): Boolean {
        val lower = text.lowercase()
        val words = lower.split(Regex("[^a-z0-9']+")).filter { it.isNotBlank() }.toSet()
        val containsCriticism = CRITICISM_WORDS.any { crit ->
            if (crit.contains(" ")) lower.contains(crit) else words.contains(crit)
        }
        if (!containsCriticism) return true
        return NEXT_STEP_WORDS.any { step ->
            if (step.contains(" ")) lower.contains(step) else words.contains(step)
        }
    }

    private val SELF_REFERENCE_PATTERNS = listOf(
        Regex("""\bi see\b""", RegexOption.IGNORE_CASE),
        Regex("""\bi notice\b""", RegexOption.IGNORE_CASE),
        Regex("""\bas an ai\b""", RegexOption.IGNORE_CASE),
        Regex("""\bi observed\b""", RegexOption.IGNORE_CASE),
        Regex("""\bi think\b""", RegexOption.IGNORE_CASE),
        Regex("""\bi believe\b""", RegexOption.IGNORE_CASE),
        Regex("""\bi would suggest\b""", RegexOption.IGNORE_CASE),
    )

    fun scoreNoSelfReference(text: String): Boolean {
        return SELF_REFERENCE_PATTERNS.none { pattern -> pattern.containsMatchIn(text) }
    }

    /** Scores [text] against the bound for [surface]. Prefer this over the raw-threshold overload. */
    fun evaluate(text: String, surface: FluencySurface): FluencyResult =
        evaluate(text, surface.maxGradeLevel)

    fun evaluate(text: String, maxGradeLevel: Double = 6.0): FluencyResult {
        val (gradeLevel, passesReadability) = scoreReadability(text, maxGradeLevel)
        val passesProcessPraise = scoreProcessPraise(text)
        val passesCriticismNextStep = scoreCriticismNextStep(text)
        val passesNoSelfReference = scoreNoSelfReference(text)

        val violations = mutableListOf<String>()
        if (!passesReadability) violations += "readability grade $gradeLevel exceeds max $maxGradeLevel"
        if (!passesProcessPraise) violations += "contains person praise instead of process praise"
        if (!passesCriticismNextStep) violations += "criticism does not carry next-step advice"
        if (!passesNoSelfReference) violations += "contains forbidden self-reference phrases"

        return FluencyResult(
            gradeLevel = gradeLevel,
            passesReadability = passesReadability,
            passesProcessPraise = passesProcessPraise,
            passesCriticismNextStep = passesCriticismNextStep,
            passesNoSelfReference = passesNoSelfReference,
            violations = violations,
        )
    }
}
