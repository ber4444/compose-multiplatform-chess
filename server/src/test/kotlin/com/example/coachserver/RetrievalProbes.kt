package com.example.coachserver

/**
 * The shared probe set for retrieval correctness, used by both repository implementations.
 *
 * It lives apart from either test because the whole point is that the Postgres reference and the
 * in-memory production repository are asked *the same* questions. A probe list copied into two
 * tests is a probe list that drifts, and drift here is invisible: retrieval returning the wrong
 * opening still produces fluent, cited, validator-approved prose.
 */
internal object RetrievalProbes {

    data class Case(val name: String, val movesSan: List<String>, val expectedEco: String)

    val ZERO_VECTOR = FloatArray(OpeningService.EMBEDDING_DIMENSIONS)

    /** Real openings, each asserted to retrieve a passage from its own ECO. */
    val CASES = listOf(
        Case("Sicilian Defence", listOf("e4", "c5"), "B20"),
        Case("French Defence", listOf("e4", "e6"), "C00"),
        Case("Caro-Kann Defence", listOf("e4", "c6"), "B10"),
        Case("Ruy Lopez", listOf("e4", "e5", "Nf3", "Nc6", "Bb5"), "C60"),
        Case("Italian Game", listOf("e4", "e5", "Nf3", "Nc6", "Bc4"), "C50"),
        Case("Queen's Gambit", listOf("d4", "d5", "c4"), "D06"),
        Case("King's Indian Defence", listOf("d4", "Nf6", "c4", "g6"), "E60"),
        Case("English Opening", listOf("c4"), "A10"),
    )

    /** The cases plus the edges: bare first moves, out-of-book tails, and no history at all. */
    val MOVE_PROBES: List<List<String>> = CASES.map(Case::movesSan) + listOf(
        listOf("e4"),
        listOf("d4"),
        listOf("c4", "e5"),
        listOf("a3", "h6", "a4", "h5"),
        listOf("e4", "c5", "Nf3", "d6", "d4", "cxd4", "Nxd4", "Nf6", "Nc3", "a6"),
        listOf("d4", "Nf6", "c4", "e6", "Nf3", "b6", "g3", "Ba6"),
        emptyList(),
    )
}
