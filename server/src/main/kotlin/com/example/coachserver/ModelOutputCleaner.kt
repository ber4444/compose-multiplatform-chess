package com.example.coachserver

/**
 * Removes a model's *deliberation* from its answer before the answer is validated or shown.
 *
 * The one-shot opening composer had none of this while [LlmChatComposer] stripped `<think>` blocks
 * and code fences from its stream — a gap that showed up in measurement as the model "failing", when
 * what actually reached the validator was scratchpad:
 *
 * ```
 * ]" -> wait, "3. Qb3" is in [lichess-d-145-d06].
 * - "From here, 3. d5 leads to the Anti-Grünfeld" -> [lichess-e-233-e60]
 * ```
 *
 * Deliberation is only stripped where it is *structurally marked* — a `<think>` block, a code
 * fence, or a leading run of note-shaped lines. Content the model presents as its answer is never
 * rewritten: a cleaner that guesses which prose is "really" the answer would manufacture passes and
 * make the eval measure the cleaner instead of the model. Anything that survives here and still
 * fails validation is a genuine failure.
 */
object ModelOutputCleaner {

    /** Paired `<think>…</think>`, plus an unclosed `<think>` running to the end of the output. */
    private val THINK_BLOCK = Regex("(?is)<think>.*?(?:</think>|$)")

    private val CODE_FENCE = Regex("^```(?:json)?\\s*\\n?([\\s\\S]*?)\\n?```\\s*$", RegexOption.IGNORE_CASE)

    /**
     * Line shapes that are never part of a two-to-three sentence coaching answer: markdown bullets,
     * and self-correction arrows ("-> wait,", "-> [id]"). Matched only while they appear *before*
     * the first ordinary prose line — a note in the middle of an answer is left alone, because at
     * that point removing it would be editing the answer rather than removing a preamble.
     */
    private val NOTE_LINE = Regex("""^\s*(?:[-*+•]\s|\d+[.)]\s|["']?\s*(?:->|=>|→))|(?:->|=>|→)\s*(?:wait|hmm|actually|no,)""", RegexOption.IGNORE_CASE)

    /** Whether [line] is note-shaped. Shared with `LeadingNoteGate`, which must make the same call
     * mid-stream that [clean] makes on a whole answer — two different answers to "is this a note?"
     * would show the user text the validator never sees. */
    internal fun isNoteLine(line: String): Boolean = NOTE_LINE.containsMatchIn(line)

    fun clean(rawText: String): String {
        val withoutThink = THINK_BLOCK.replace(rawText, " ")
        val unfenced = stripCodeFence(withoutThink.trim())
        return dropLeadingNotes(unfenced).trim()
    }

    private fun stripCodeFence(text: String): String =
        CODE_FENCE.matchEntire(text)?.groupValues?.get(1)?.trim() ?: text

    private fun dropLeadingNotes(text: String): String {
        val lines = text.lines()
        val firstProse = lines.indexOfFirst { line ->
            line.isNotBlank() && !NOTE_LINE.containsMatchIn(line)
        }
        // Every line looked like a note: return the input untouched rather than the empty string,
        // so the failure is reported as invalid output instead of silently becoming "empty".
        if (firstProse <= 0) return text
        return lines.drop(firstProse).joinToString("\n")
    }
}
