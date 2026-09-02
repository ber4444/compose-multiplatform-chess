package com.example.coachserver

import com.example.coachapi.CorpusDiagnostics
import com.example.coachapi.Passage

/**
 * The retrieval index in process memory, over the corpus baked into the image by [CorpusIndexFile].
 *
 * This is the production repository; [PostgresPassageRepository] remains the seed-path and
 * reference implementation. **The two are pinned to each other** by a parity case in
 * `OpeningRetrievalGroundingTest`, which runs both over the same corpus and the same probes and
 * requires identical results. That pinning is the whole safety story here: this class is a second
 * implementation of retrieval rules that were arrived at by measurement (see the SQL in
 * [PostgresPassageRepository.retrieve]), and a second implementation that drifts would return a
 * fluent, cited, validator-approved answer about the wrong opening — the exact failure the book
 * tier was introduced to stop, and one nothing downstream can detect.
 *
 * Brute force is deliberate. 3,825 rows x 384 dimensions is ~1.5M multiply-adds per query, which is
 * sub-millisecond; an ANN index at this scale would add approximation error and a build step to buy
 * nothing. If the corpus ever grows by an order of magnitude, revisit this before revisiting the
 * hosting.
 */
class InMemoryPassageRepository(rows: List<IndexedPassage>) : PassageRepository {

    private val lock = Any()

    @Volatile
    private var rows: List<IndexedPassage> = rows

    /**
     * Book rows only, longest move sequence first. Mirrors the SQL's `ORDER BY length(moves) DESC`:
     * 1.e4 alone is the King's Pawn Game, but 1.e4 c5 is the Sicilian, and the deeper line wins.
     */
    @Volatile
    private var book: List<IndexedPassage> = buildBook(rows)

    constructor(index: CorpusIndex) : this(index.rows)

    override fun retrieve(
        embedding: FloatArray,
        limit: Int,
        movesSan: List<String>,
        eco: String?,
    ): RetrievalResult {
        require(embedding.size == OpeningService.EMBEDDING_DIMENSIONS)
        val snapshot = rows
        val bookSnapshot = book
        val normalizedMoves = MoveSequence.normalizeSan(movesSan)
        val bookHits = if (normalizedMoves.isEmpty()) {
            emptyList()
        } else {
            queryBook(bookSnapshot, normalizedMoves, minOf(BOOK_LIMIT, limit))
        }
        val resolvedEco = bookHits.firstOrNull()?.eco ?: eco
        val collected = LinkedHashMap<String, Passage>()
        bookHits.forEach { collected[it.passage.sourceId] = it.passage }
        if (collected.size < limit && resolvedEco != null) {
            queryVector(snapshot, embedding, limit, resolvedEco)
                .forEach { collected.putIfAbsent(it.sourceId, it) }
        }
        if (collected.size < limit) {
            queryVector(snapshot, embedding, limit, null)
                .forEach { collected.putIfAbsent(it.sourceId, it) }
        }
        return RetrievalResult(collected.values.take(limit), resolvedEco)
    }

    override fun upsert(passage: Passage, embedding: FloatArray, eco: String?, moves: String?) {
        require(embedding.size == OpeningService.EMBEDDING_DIMENSIONS)
        synchronized(lock) {
            val replacement = IndexedPassage(passage, embedding, eco?.uppercase(), moves)
            val updated = rows.filterNot { it.passage.sourceId == passage.sourceId } + replacement
            rows = updated
            book = buildBook(updated)
        }
    }

    /** Longest-prefix match, then every row sharing that exact move sequence, ordered by source id. */
    private fun queryBook(
        book: List<IndexedPassage>,
        normalizedMoves: String,
        limit: Int,
    ): List<IndexedPassage> {
        val matched = book.firstOrNull { it.matches(normalizedMoves) }?.moves ?: return emptyList()
        return book.asSequence()
            .filter { it.moves == matched }
            .sortedBy { it.passage.sourceId }
            .take(limit)
            .toList()
    }

    /**
     * Nearest neighbours by cosine distance, ties broken by source id.
     *
     * A zero query vector yields `NaN` for every row, which is what pgvector's `<=>` also returns;
     * Kotlin's total ordering on Float, like Postgres's, sorts every NaN equal and last, so both
     * implementations fall through to the source-id tiebreak. `OpeningRetrievalGroundingTest` seeds
     * all-zero vectors precisely to exercise that case, so this is load-bearing rather than a
     * curiosity.
     */
    private fun queryVector(
        rows: List<IndexedPassage>,
        embedding: FloatArray,
        limit: Int,
        eco: String?,
    ): List<Passage> {
        val wanted = eco?.uppercase()
        // Distances are computed once per row, not inside the comparator — a comparator that embeds
        // the metric recomputes it O(n log n) times instead of O(n). Warm median for the worst case
        // (an unscoped scan of all 3,807 rows) is 3.6 ms, p95 5.1 ms.
        return rows.asSequence()
            .filter { wanted == null || it.eco == wanted }
            .map { cosineDistance(embedding, it.embedding) to it }
            .sortedWith(compareBy({ it.first }, { it.second.passage.sourceId }))
            .take(limit)
            .map { it.second.passage }
            .toList()
    }

    private companion object {

        // `it.moves != ""`, not `isNotBlank()`, to mirror the SQL's `moves <> ''` exactly. No corpus
        // row is whitespace-only today, so the two agree in practice — but the parity test compares
        // behaviour, not intent, and a divergence here would only surface once such a row appeared.
        fun buildBook(rows: List<IndexedPassage>): List<IndexedPassage> = rows
            .filter { it.moves != null && it.moves != "" }
            .sortedByDescending { it.moves!!.length }

        fun IndexedPassage.matches(normalizedMoves: String): Boolean {
            val moves = moves ?: return false
            return normalizedMoves == moves || normalizedMoves.startsWith("$moves ")
        }

        /** `1 - cosine similarity`, matching pgvector's `<=>` operator including its NaN cases. */
        fun cosineDistance(left: FloatArray, right: FloatArray): Float {
            var dot = 0f
            var leftNorm = 0f
            var rightNorm = 0f
            for (index in left.indices) {
                val a = left[index]
                val b = right[index]
                dot += a * b
                leftNorm += a * a
                rightNorm += b * b
            }
            return 1f - dot / (kotlin.math.sqrt(leftNorm) * kotlin.math.sqrt(rightNorm))
        }
    }
}

/**
 * Corpus health straight from the baked index's manifest.
 *
 * The Postgres reader answers "did a seed complete?", a question about a mutable external system.
 * Here the corpus ships inside the image, so the honest answer is "this is the corpus this build
 * contains" — always ready, and versioned by the same content hash [SeedMain] would have written.
 */
class IndexCorpusStatusReader(private val index: CorpusIndex) : CorpusStatusReader {
    override fun read(): CorpusDiagnostics = CorpusDiagnostics(
        ready = true,
        seedVersion = index.manifest.version,
        rowCount = index.manifest.expectedRowCount,
        finalSourceId = index.manifest.finalSourceId,
    )
}
