package com.example.coachserver

import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The grounding gate for the repository that actually serves production traffic.
 *
 * `OpeningRetrievalGroundingTest` asks the same questions of the Postgres reference, but it is
 * `@Testcontainers(disabledWithoutDocker = true)` — so on a runner without Docker it contributes
 * nothing. Since the deployed server retrieves from [InMemoryPassageRepository], leaving opening
 * identification gated only behind a suite that can silently skip would put the shipping path in
 * exactly the position the book tier was introduced to fix: wrong answers that are fluent, cited
 * and validator-approved, with nothing downstream able to notice.
 *
 * This runs everywhere. Parity with the SQL is a separate assertion, in the Docker-gated suite.
 *
 * Embeddings are all-zero, as they are there: a zero vector makes every row equidistant, so the
 * vector tiers can contribute nothing and these assertions can only pass through the book tier.
 * That also means no ONNX model is needed on the test classpath.
 */
class InMemoryRetrievalGroundingTest {

    @Test
    fun `each opening retrieves a passage from its own ECO`() {
        val failures = RetrievalProbes.CASES.mapNotNull { case ->
            val result = repository.retrieve(
                embedding = RetrievalProbes.ZERO_VECTOR,
                limit = 4,
                movesSan = case.movesSan,
                // null on purpose: this is what the shipping clients send. The server has to
                // identify the opening from the moves alone.
                eco = null,
            )
            val top = result.passages.firstOrNull()
            when {
                result.resolvedEco != case.expectedEco ->
                    "${case.name}: resolved ECO ${result.resolvedEco}, expected ${case.expectedEco}"
                top == null -> "${case.name}: no passages returned"
                !top.title.startsWith(case.expectedEco) ->
                    "${case.name}: top passage '${top.title}' is not in ${case.expectedEco}"
                else -> null
            }
        }

        assertTrue(failures.isEmpty(), "Retrieval returned the wrong opening for:\n${failures.joinToString("\n")}")
    }

    @Test
    fun `the longest matching line wins over its shorter prefix`() {
        val kingsPawn = repository.retrieve(RetrievalProbes.ZERO_VECTOR, 4, listOf("e4"), null)
        val sicilian = repository.retrieve(RetrievalProbes.ZERO_VECTOR, 4, listOf("e4", "c5"), null)

        assertEquals("B00", kingsPawn.resolvedEco)
        assertEquals("B20", sicilian.resolvedEco)
    }

    @Test
    fun `a line deeper than the book still resolves to the family it belongs to`() {
        val result = repository.retrieve(RetrievalProbes.ZERO_VECTOR, 4, listOf("a3", "h6", "a4", "h5"), null)

        assertEquals("A00", result.resolvedEco)
        assertTrue(result.passages.isNotEmpty())
    }

    @Test
    fun `a position with no move history falls back to vector search`() {
        val result = repository.retrieve(RetrievalProbes.ZERO_VECTOR, 4, emptyList(), null)

        assertEquals(null, result.resolvedEco)
        assertTrue(result.passages.isNotEmpty(), "Vector fallback must still retrieve passages")
    }

    @Test
    fun `retrieved passages say something beyond restating the ECO code`() {
        val result = repository.retrieve(RetrievalProbes.ZERO_VECTOR, 1, listOf("e4", "c5"), null)
        val firstSentence = result.passages.single().text.substringBefore('.')

        assertTrue(
            !firstSentence.contains("is classified as ECO"),
            "Leading sentence is the tautology the composers quote: $firstSentence",
        )
        assertTrue(firstSentence.contains("Sicilian"), "Expected Sicilian content, got: $firstSentence")
    }

    @Test
    fun `equidistant rows are ordered by source id, as the SQL orders them`() {
        // pgvector's `<=>` returns NaN when either vector is zero, so every row ties and Postgres
        // falls through to the `, source_id` secondary key. This implementation has to break the tie
        // the same way or the parity assertion in OpeningRetrievalGroundingTest fails on a runner
        // with Docker while everything here stays green. Pinning the observable behaviour means the
        // ordering contract is checked even where the SQL cannot run.
        val passages = repository.retrieve(RetrievalProbes.ZERO_VECTOR, 4, emptyList(), null).passages
        val ids = passages.map { it.sourceId }

        assertEquals(ids.sorted(), ids, "Tied rows must come back in source-id order")
        assertEquals(4, ids.size)
    }

    @Test
    fun `every probe returns at most the requested number of passages`() {
        RetrievalProbes.MOVE_PROBES.forEach { moves ->
            (1..4).forEach { limit ->
                val passages = repository.retrieve(RetrievalProbes.ZERO_VECTOR, limit, moves, null).passages
                assertTrue(passages.size <= limit, "$moves at limit=$limit returned ${passages.size}")
                assertEquals(
                    passages.map { it.sourceId }.distinct().size,
                    passages.size,
                    "$moves at limit=$limit returned a duplicate passage",
                )
            }
        }
    }

    private companion object {
        /**
         * Built the way `OpeningRetrievalGroundingTest.bulkSeed` builds the database: the real
         * corpus, all-zero vectors. The two must be constructed identically or the parity assertion
         * over there is comparing different corpora.
         */
        val repository = InMemoryPassageRepository(
            SeedMain.loadCorpus(Path.of("corpus")).map { entry ->
                IndexedPassage(
                    passage = entry.passage,
                    embedding = FloatArray(OpeningService.EMBEDDING_DIMENSIONS),
                    eco = entry.eco,
                    moves = entry.moves,
                )
            },
        )
    }
}
