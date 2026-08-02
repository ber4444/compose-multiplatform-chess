package com.example.ondeviceai

/**
 * Sanitizes internal raw corpus citation tags (e.g. `[lichess-c-955-c55]` or `[lichess-c20]`)
 * from user-facing text outputs so raw citation tags never leak onto the user's screen (B4).
 */
object CitationSanitizer {
    private val LICHESS_TAG_REGEX = Regex("\\[lichess-[^\\]]+\\]", RegexOption.IGNORE_CASE)

    /**
     * Strips `[lichess-...]` tags and cleans up extra whitespace or dangling punctuation.
     */
    fun sanitize(text: String): String {
        if (!text.contains("lichess-", ignoreCase = true)) return text
        return text.replace(LICHESS_TAG_REGEX, "")
            .replace(Regex("\\s+([.,!?])"), "$1")
            .replace(Regex("\\s{2,}"), " ")
            .trim()
    }
}
