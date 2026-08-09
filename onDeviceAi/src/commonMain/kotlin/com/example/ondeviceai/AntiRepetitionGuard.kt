package com.example.ondeviceai

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Generation-side half of B15: cuts a degenerate tail off an on-device completion.
 *
 * Two rules, both applied to the *delivered* text rather than to logits (no local runtime exposes a
 * sampler hook through its Kotlin API):
 *
 *  - **Stop sequences** — everything from the first occurrence onwards is dropped. A terminator can
 *    arrive split across tokens, so a trailing slice that is still a *prefix* of one is held back
 *    rather than emitted; otherwise `<end_of` renders in the panel for a frame before vanishing.
 *  - **Repeated n-grams** — the text is cut just before the first n-gram that already occurred
 *    earlier in the completion.
 *
 * Two invariants make this safe to put in front of every runtime, and both were regressions worth
 * pinning (see `AntiRepetitionGuardTest`):
 *
 *  1. **It truncates, it never rejects.** Cactus, LiteRT-LM and Foundation Models all deliver the
 *     whole answer as a *single* [AiTokenOrFinal.Token], so dropping the offending token drops the
 *     entire answer — turning "the model repeated itself" into "the model said nothing", which
 *     [DefaultAiCoachOrchestrator] can only report as a validation fallback. Exactly the case B15
 *     exists to improve.
 *  2. **[AiTokenOrFinal.Final] always passes through, with its metrics untouched.** It is the only
 *     carrier of the route, the latency numbers and `fallbackReason`; swallowing it strands the
 *     orchestrator with no metrics, and rewriting `tokenCount` from a word count would corrupt the
 *     bench JSONL under `docs/benchmarks/on-device-ai/`. Metrics describe what the model *generated*
 *     — truncating what we display does not un-generate it.
 */
fun Flow<AiTokenOrFinal>.withAntiRepetitionGuard(
    ngramSize: Int?,
    stopSequences: List<String>,
): Flow<AiTokenOrFinal> = flow {
    val guard = AntiRepetitionGuard(ngramSize, stopSequences)
    collect { piece ->
        when (piece) {
            is AiTokenOrFinal.Token -> {
                val released = guard.consume(piece.text)
                if (released.isNotEmpty()) emit(AiTokenOrFinal.Token(released))
            }
            is AiTokenOrFinal.Final -> {
                val flushed = guard.flush()
                if (flushed.isNotEmpty()) emit(AiTokenOrFinal.Token(flushed))
                emit(AiTokenOrFinal.Final(guard.clean(piece.text), piece.metrics))
            }
            is AiTokenOrFinal.ToolCall -> {}
        }
    }
}

/** Streaming state for [withAntiRepetitionGuard]; confined to one collector, so not thread-safe. */
internal class AntiRepetitionGuard(
    private val ngramSize: Int?,
    stopSequences: List<String>,
) {
    private val stops = stopSequences.filter(String::isNotEmpty)
    private val maxHeld = (stops.maxOfOrNull(String::length) ?: 1) - 1
    private val released = StringBuilder()
    private var held = ""
    private var stopped = false

    /** The slice of [text] that may be shown, `""` when the completion is already truncated. */
    fun consume(text: String): String {
        if (stopped || text.isEmpty()) return ""
        val buffer = held + text
        held = ""
        val stopIndex = stops.mapNotNull { stop -> buffer.indexOf(stop).takeIf { it >= 0 } }.minOrNull()
        if (stopIndex != null) {
            stopped = true
            return release(buffer.substring(0, stopIndex))
        }
        val holdLength = stopSequencePrefixLength(buffer)
        held = buffer.takeLast(holdLength)
        return release(buffer.dropLast(holdLength))
    }

    /** Releases a held partial stop sequence that turned out to be ordinary text after all. */
    fun flush(): String {
        if (stopped || held.isEmpty()) return ""
        val pending = held
        held = ""
        return release(pending)
    }

    /** Applies the same two rules to a runtime's terminal text (usually empty — see [consume]). */
    fun clean(text: String): String {
        if (text.isEmpty()) return text
        var cleaned = text
        stops.forEach { stop ->
            val index = cleaned.indexOf(stop)
            if (index >= 0) cleaned = cleaned.substring(0, index)
        }
        return ngramSize?.let { cleaned.truncateAtRepetition(it) } ?: cleaned
    }

    private fun release(text: String): String {
        if (text.isEmpty()) return ""
        val size = ngramSize
        if (size == null) {
            released.append(text)
            return text
        }
        val alreadyReleased = released.length
        val candidate = released.toString() + text
        val repeatAt = candidate.repeatedNgramOffset(size)
        if (repeatAt == null) {
            released.append(text)
            return text
        }
        stopped = true
        held = ""
        // A repeat is only detectable once its final word lands, so it can start inside text that
        // already went downstream. Nothing can be un-emitted, so keep what was kept and stop.
        val kept = candidate.substring(0, repeatAt).trimEnd()
        released.clear()
        released.append(kept)
        return if (kept.length > alreadyReleased) kept.substring(alreadyReleased) else ""
    }

    /**
     * Length of the trailing slice of [buffer] that could still grow into a stop sequence, so must
     * not be emitted yet. Zero when there are no stop sequences at all.
     */
    private fun stopSequencePrefixLength(buffer: String): Int {
        var length = minOf(maxHeld, buffer.length)
        while (length > 0) {
            val suffix = buffer.substring(buffer.length - length)
            if (stops.any { it.startsWith(suffix) }) return length
            length--
        }
        return 0
    }
}

/**
 * Character offset at which the first repeated [n]-gram starts, or `null` when there is none.
 * Words are whitespace-delimited and compared verbatim; only a window that repeats a *disjoint*
 * earlier one counts, so a phrase has to genuinely reoccur rather than merely overlap itself.
 */
internal fun String.repeatedNgramOffset(n: Int): Int? {
    if (n <= 0) return null
    val words = wordsWithOffsets()
    if (words.size < n * 2) return null
    for (i in n..words.size - n) {
        val candidate = words.subList(i, i + n).map { it.first }
        for (j in 0..i - n) {
            if (words.subList(j, j + n).map { it.first } == candidate) return words[i].second
        }
    }
    return null
}

/** Cuts [this] just before the first repeated [n]-gram; text without one is returned unchanged. */
internal fun String.truncateAtRepetition(n: Int): String =
    repeatedNgramOffset(n)?.let { substring(0, it).trimEnd() } ?: this

/** Whitespace-delimited words paired with the character offset each one starts at. */
private fun String.wordsWithOffsets(): List<Pair<String, Int>> {
    val words = mutableListOf<Pair<String, Int>>()
    var index = 0
    while (index < length) {
        if (this[index].isWhitespace()) {
            index++
            continue
        }
        val start = index
        while (index < length && !this[index].isWhitespace()) index++
        words += substring(start, index) to start
    }
    return words
}
