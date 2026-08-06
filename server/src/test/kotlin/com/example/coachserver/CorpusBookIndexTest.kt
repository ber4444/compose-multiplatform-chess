package com.example.coachserver

import com.example.coachapi.Passage
import kotlin.test.Test
import kotlin.test.assertEquals

class CorpusBookIndexTest {
    @Test
    fun `book retrieval suppresses strict prefix ancestors after resolving a deeper line`() {
        val index = CorpusBookIndex(
            listOf(
                entry("family", "e4", "C20"),
                entry("specific", "e4 e5 Nf3", "C40"),
            ),
        )

        val passages = index.retrieve(listOf("e4", "e5", "Nf3"), limit = 4)

        assertEquals(listOf("specific"), passages.map(Passage::sourceId))
    }

    private fun entry(sourceId: String, moves: String, eco: String) = SeedMain.CorpusEntry(
        passage = Passage(sourceId, sourceId, "$sourceId insight."),
        eco = eco,
        moves = moves,
    )
}
