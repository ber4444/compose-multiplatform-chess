package com.example.evals

/**
 * Whether an answer *covers* a golden-case concept, allowing the paraphrases a fluent writer
 * actually uses.
 *
 * The scorer this replaces required the literal token: a case tagged `center` was only "grounded"
 * if the string `center` appeared. Measured against a real provider on 2026-08-05, that rejected
 * 90 of 96 validator-approved answers, every sampled one of which was a correct paraphrase —
 * *"contesting the center"* satisfies it, *"fighting for central squares"* did not. The only writer
 * that reliably passed was the one quoting its source verbatim, which is the opposite of what the
 * column was supposed to reward.
 *
 * Deliberately a small, hand-written table rather than a stemmer or an embedding:
 * - it is auditable — anyone can read what `king safety` is allowed to mean, and disagree;
 * - it cannot silently widen, which is how a grounding check turns into no check at all.
 *
 * **Adding a synonym is loosening a gate.** Add one only for wording a *correct* answer used and
 * was failed for, and keep the entries specific — `"safe"` alone would make "a safe, solid setup"
 * count as king safety.
 */
object ConceptVocabulary {

    /**
     * Concept → accepted surface forms, matched as **word prefixes**, so `develop` covers
     * "develops", "developing" and "development" without listing each.
     */
    private val synonyms: Map<String, List<String>> = mapOf(
        "development" to listOf("develop", "minor piece", "piece activity", "bring the pieces", "activate"),
        "center" to listOf("center", "centre", "central"),
        "king safety" to listOf("king safety", "safety of the king", "castl", "shield the king", "king is safe"),
        "pawn tension" to listOf("pawn tension", "tension", "pawn break", "pawn chain", "pawn structure"),
        "counterplay" to listOf("counterplay", "counterattack", "counter-attack", "counter play", "activity against"),
    )

    /**
     * True when [text] discusses [concept]. Unknown concepts fall back to plain containment — the
     * old behaviour — so a new tag in the golden set is scored strictly rather than accidentally
     * being scored as always-covered.
     */
    fun isCovered(concept: String, text: String): Boolean {
        val lower = text.lowercase()
        val forms = synonyms[concept.lowercase().trim()] ?: return lower.contains(concept.lowercase())
        return forms.any { lower.contains(it) }
    }

    /** The concepts with an explicit vocabulary; everything else is matched literally. */
    fun knownConcepts(): Set<String> = synonyms.keys
}
