package com.example.myapplication.ui

/**
 * Sanitizes internal raw corpus citation tags (e.g. `[lichess-c-955-c55]`, `[board-goal]`,
 * `[initial-position]`, `[turn-order]`, `[rule-1]`, `[move-14]`) from user-facing text outputs
 * so raw internal tags never leak onto the user's screen (B4).
 *
 * Scope: Installed in `:app`'s display & render paths (Position Chat, Opening Explainer, Move Coach).
 */
object CitationSanitizer {
    private val CITATION_TAG_REGEX = Regex("\\[[a-zA-Z0-9_-]+\\]")

    /**
     * Strips `[...]` corpus tags and cleans up trailing whitespace or dangling punctuation.
     */
    fun sanitize(text: String): String {
        if (!text.contains("[")) return text
        return text.replace(CITATION_TAG_REGEX, "")
            .replace(Regex("\\s+([.,!?])"), "$1")
            .replace(Regex("\\s{2,}"), " ")
            .trim()
    }
}
