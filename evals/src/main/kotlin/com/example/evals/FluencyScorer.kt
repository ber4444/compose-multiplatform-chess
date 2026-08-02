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
     * Calculates Flesch-Kincaid grade level:
     * 0.39 * (words / sentences) + 11.8 * (syllables / words) - 15.59
     */
    fun scoreReadability(text: String, maxGradeLevel: Double = 6.0): Pair<Double, Boolean> {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return 0.0 to true

        val sentences = trimmed.split(Regex("[.!?]+")).filter { it.isNotBlank() }
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
     * Estimates syllable count for an English word.
     */
    fun countSyllables(word: String): Int {
        val clean = word.lowercase().filter { it.isLetter() }
        if (clean.isEmpty()) return 1
        if (clean.length <= 3) return 1

        val processed = clean.removeSuffix("es").removeSuffix("s").removeSuffix("e")
        val vowelGroups = processed.split(Regex("[^aeiouy]+")).filter { it.isNotEmpty() }
        val count = vowelGroups.size
        return count.coerceAtLeast(1)
    }

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
