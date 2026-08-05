package com.example.coachserver

import com.example.coachapi.Passage
import java.nio.file.Path

/**
 * The book tier of retrieval, in memory and without a database.
 *
 * `PostgresPassageRepository.queryBook` is the production implementation, and it is only checkable
 * where Docker is: `OpeningRetrievalGroundingTest` is `disabledWithoutDocker`. That left the
 * *correctness* of opening identification — the thing that was measured wrong 8/8 times and shipped
 * anyway, because every wrong answer was fluent, cited and validator-approved — resting entirely on
 * a suite that a runner can silently skip, and on nothing at all in the eval scorecard.
 *
 * This class exists so the same question ("does this move prefix resolve to the right opening?") can
 * be asked from a plain JVM process over the checked-in corpus, and therefore gated as an AUTOMATED
 * eval row on every PR.
 *
 * **It is a second implementation of a rule that already exists in SQL, so it is pinned to the
 * first one**: `OpeningRetrievalGroundingTest` runs both over the same move lists and asserts they
 * resolve identically. Without that, this index drifting from the SQL would turn the new gate green
 * while production regressed — a worse outcome than having no gate.
 */
class CorpusBookIndex(entries: List<SeedMain.CorpusEntry>) {

    /**
     * Book rows only (a passage with no move sequence — a concept note — is reachable in production
     * solely through the vector tier), longest first. The ordering mirrors the SQL's
     * `ORDER BY length(moves) DESC`: 1.e4 alone is the King's Pawn Game, but 1.e4 c5 is the
     * Sicilian, and the deeper line has to win.
     */
    private val book: List<BookRow> = entries
        .mapNotNull { entry ->
            val moves = entry.moves?.takeIf(String::isNotBlank) ?: return@mapNotNull null
            BookRow(moves = moves, eco = entry.eco, passage = entry.passage)
        }
        .sortedByDescending { it.moves.length }

    /**
     * Longest matching book line for [movesSan], or `null` when nothing matches (an empty move
     * list, or a corpus whose `moves` column was never populated — see the seeding note in
     * CLAUDE.md § Cloud retrieval).
     */
    fun resolve(movesSan: List<String>): Match? {
        val normalized = MoveSequence.normalizeSan(movesSan)
        if (normalized.isEmpty()) return null
        val row = book.firstOrNull { normalized == it.moves || normalized.startsWith("${it.moves} ") }
            ?: return null
        return Match(eco = row.eco, passage = row.passage, matchedMoves = row.moves)
    }

    /** Book passages for [movesSan], longest match first, mirroring the SQL's `LIMIT`. */
    fun retrieve(movesSan: List<String>, limit: Int): List<Passage> {
        val normalized = MoveSequence.normalizeSan(movesSan)
        if (normalized.isEmpty()) return emptyList()
        return book.asSequence()
            .filter { normalized == it.moves || normalized.startsWith("${it.moves} ") }
            .map(BookRow::passage)
            .distinctBy(Passage::sourceId)
            .take(limit)
            .toList()
    }

    data class Match(val eco: String?, val passage: Passage, val matchedMoves: String)

    private data class BookRow(val moves: String, val eco: String?, val passage: Passage)

    companion object {
        /** Builds an index straight from the checked-in corpus directory. */
        fun fromCorpus(directory: Path): CorpusBookIndex = CorpusBookIndex(SeedMain.loadCorpus(directory))
    }
}
