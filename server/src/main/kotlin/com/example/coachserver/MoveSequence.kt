package com.example.coachserver

/**
 * Normalizes chess move sequences into one canonical space-separated SAN string, so a corpus PGN
 * (`"1. e4 c5 2. Nf3"`) and a request's `movesSan` (`["e4", "c5", "Nf3"]`) compare as plain text.
 *
 * This is what makes opening identification a *prefix match* rather than a vector-similarity guess.
 * An ECO code is a property of a move prefix, so the corpus can be indexed by it and looked up
 * exactly — see `PassageRepository.retrieve`, which resolves the opening from the moves before it
 * ever consults the embedding. Retrieval used to be embedding-only, which returned the Catalan for
 * a French Defence and the Rubinstein for a Queen's Gambit.
 */
object MoveSequence {

    /** Strips move numbers, results, and NAG/annotation noise from a corpus PGN move sequence. */
    fun normalizePgn(pgn: String): String = pgn
        .replace(Regex("\\{[^}]*}"), " ")
        .split(Regex("\\s+"))
        .asSequence()
        .map { it.trim() }
        // Handles both "1. e4" (a standalone number token) and "1.e4" (glued), which both appear
        // in real PGN. The result marker that ends a PGN is dropped outright.
        .map { it.replace(Regex("^\\d+\\.+"), "") }
        .filter { it.isNotEmpty() }
        .filterNot { it.matches(Regex("\\d+\\.*")) }
        .filterNot { it == "1-0" || it == "0-1" || it == "1/2-1/2" || it == "*" }
        .map { it.trimEnd('!', '?') }
        .filter { it.isNotEmpty() }
        .joinToString(" ")

    /** Canonicalizes a request's SAN list the same way [normalizePgn] canonicalizes a corpus PGN. */
    fun normalizeSan(movesSan: List<String>): String = movesSan
        .asSequence()
        .map { it.trim().trimEnd('!', '?') }
        .filter { it.isNotEmpty() }
        .joinToString(" ")
}
