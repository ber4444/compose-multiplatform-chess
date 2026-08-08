package com.example.myapplication.ui

/**
 * Strips internal corpus citation tags from user-facing text so raw retrieval ids never reach the
 * screen (B4). Examples of what goes: `[lichess-c-955-c55]` (the server's opening/chat corpus),
 * `[board-goal]`, `[initial-position]` (the bundled `rulesCorpus/passages.tsv` ids).
 *
 * **`[move-N]` is deliberately preserved.** Those are the evaluative-summary citations RAG-2 emits
 * on purpose, and B16 turns them into tappable board jumps — stripping them would delete the
 * affordance rather than the leak. Anything matching [RENDERABLE_TAG_REGEX] is a tag the UI is
 * expected to render or linkify itself.
 *
 * Applied at every `:app` display path: Position Chat (streamed *and* final), Opening Explainer,
 * Move Coach, and Game Summary. Game Summary matters most — it runs with no response validator at
 * all, so this is the only thing between its raw model output and the user.
 */
object CitationSanitizer {
    private val CITATION_TAG_REGEX = Regex("\\[[a-zA-Z0-9_-]+\\]")

    /** Tags the UI renders itself; matched against the whole tag, brackets included. */
    private val RENDERABLE_TAG_REGEX = Regex("\\[move-\\d+\\]")

    /** A trailing `[…` with no closing bracket yet — an in-flight tag mid-stream. */
    private val UNTERMINATED_TAIL_REGEX = Regex("\\[[a-zA-Z0-9_-]*$")

    /**
     * Strips citation tags from a complete piece of text.
     *
     * Uses replace-with-callback rather than a negative lookahead so no lookaround is involved —
     * same portability reasoning as `MoveCoachResponseValidator.normalize`'s no-regex note.
     */
    fun sanitize(text: String): String {
        val withoutTags = if (!text.contains('[')) {
            text
        } else {
            text
                .replace(CITATION_TAG_REGEX) { match ->
                    if (RENDERABLE_TAG_REGEX.matches(match.value)) match.value else ""
                }
                .tidy()
        }
        // Outside the bracket guard on purpose. Model-added wrapping quotes have nothing to do with
        // citation tags, and most coach lines carry no tag at all — folding this into `tidy()` would
        // make it fire only on the minority of text that happens to cite something.
        return withoutTags.unwrapIfWhollyQuoted()
    }

    /**
     * Display-safe view of a partially streamed buffer.
     *
     * On top of [sanitize], drops a trailing unterminated `[…` fragment: mid-stream the closing
     * bracket hasn't arrived, so the tag can't match yet and would otherwise render character by
     * character before vanishing when the stream completes.
     */
    fun sanitizeStreaming(partialText: String): String {
        if (!partialText.contains('[')) return partialText
        return sanitize(partialText).replace(UNTERMINATED_TAIL_REGEX, "").tidy()
    }

    /** Closes up the whitespace and dangling punctuation a removed tag leaves behind. */
    private fun String.tidy(): String =
        replace(Regex("\\s+([.,!?])"), "$1")
            .replace(Regex("\\s{2,}"), " ")
            .trim()

    /**
     * Unwraps text the model wrapped in quotes end-to-end.
     *
     * Both ends must match, and only one pair is removed. Stripping the two ends independently
     * unbalances any answer that legitimately quotes something — `He said "en passant".` would lose
     * its closing quote — and this runs on every display path, including Game Summary, which has no
     * response validator behind it at all.
     */
    private fun String.unwrapIfWhollyQuoted(): String {
        if (length < 2) return this
        val closer = QUOTE_PAIRS[first()] ?: return this
        if (last() != closer) return this
        // A closer occurring before the end means these are two separate quotations, not one
        // wrapper: `"a" and "b"` must survive intact.
        val inner = substring(1, length - 1)
        if (inner.contains(closer)) return this
        return inner.trim()
    }

    /** Opening quote characters and the closer each one requires. */
    private val QUOTE_PAIRS = mapOf('"' to '"', '\'' to '\'', '“' to '”')
}
