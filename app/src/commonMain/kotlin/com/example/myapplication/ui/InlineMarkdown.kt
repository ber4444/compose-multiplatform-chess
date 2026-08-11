package com.example.myapplication.ui

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight

/**
 * Renders the small amount of Markdown an on-device model emits, instead of showing its syntax.
 *
 * The coach panel is a `Text`, so `**Why d4 was better:**` arrived on screen with the asterisks
 * intact and nothing bold — the answer read like raw machine output. The prompt now asks for plain
 * prose, but a prompt is a request: gemma3-270m has seen enough Markdown to reach for it anyway, and
 * on the free tier there is no validator between the model and the panel at all. This is the
 * backstop, and it belongs on the display path for the same reason [CitationSanitizer] does.
 *
 * Deliberately tiny. It handles the two things that actually show up — `**bold**` and leading list
 * markers — and leaves everything else as literal text. A general Markdown parser here would be a
 * lot of surface area for output that is at most three sentences, and would start "helpfully"
 * reinterpreting chess notation: `1. e4` is an ordered-list item to a real parser, and `*` is a
 * check-evaluation symbol in some annotation styles.
 */
object InlineMarkdown {

    /**
     * `**bold**` becomes bold, leading bullets become sentences, everything else stays literal.
     *
     * An unpaired `**` is left alone rather than treated as an opener: a truncated answer ends
     * mid-emphasis surprisingly often, and swallowing the rest of the line is worse than showing
     * two asterisks.
     */
    fun render(text: String): AnnotatedString {
        val cleaned = stripListMarkers(text)
        return buildAnnotatedString {
            var i = 0
            while (i < cleaned.length) {
                val open = cleaned.indexOf(MARKER, i)
                if (open < 0) {
                    append(cleaned.substring(i))
                    break
                }
                val close = cleaned.indexOf(MARKER, open + MARKER.length)
                if (close < 0) {
                    append(cleaned.substring(i))
                    break
                }
                append(cleaned.substring(i, open))
                val inner = cleaned.substring(open + MARKER.length, close)
                // `****` and friends: emit nothing rather than an empty bold run.
                if (inner.isNotEmpty()) {
                    pushStyle(SpanStyle(fontWeight = FontWeight.Bold))
                    append(inner)
                    pop()
                }
                i = close + MARKER.length
            }
        }
    }

    /**
     * Turns a model's bullet list back into prose.
     *
     * The panel is a few lines under a board, not a document: `*   **Why d4 was better:** …` should
     * read as a sentence. Markers are only stripped at the start of a line, so a `*` inside a
     * sentence survives untouched.
     */
    private fun stripListMarkers(text: String): String =
        text.lineSequence()
            .map { line ->
                val trimmed = line.trimStart()
                val marker = LIST_MARKERS.firstOrNull { trimmed.startsWith(it) }
                if (marker != null) trimmed.removePrefix(marker).trimStart() else line
            }
            .joinToString("\n")
            .trim()

    private const val MARKER = "**"

    /** Ordered by length so `* ` can't shadow a longer marker. Each requires the trailing space. */
    private val LIST_MARKERS = listOf("* ", "- ", "+ ")
}
