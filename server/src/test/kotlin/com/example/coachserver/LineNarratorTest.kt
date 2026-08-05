package com.example.coachserver

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The acceptance criteria for the R-1 fix, as tests.
 *
 * R-1's verdict was that the cloud output is not worth showing, and its deepest cause was a corpus
 * with ~500 claims spread over 3,803 rows: every passage in an ECO opened with the same sentence,
 * and the composers quote the first sentence. These assertions are what "fixed" means.
 */
class LineNarratorTest {

    @Test
    fun `a king move is described by the right it gives up`() {
        val sentence = LineNarrator.describe(listOf("e4", "c5", "Ke2"))

        assertNotNull(sentence)
        assertTrue(sentence.contains("Ke2"), sentence)
        assertTrue(sentence.contains("castle"), sentence)
    }

    @Test
    fun `a completed fianchetto is named, a bare flank pawn move is not`() {
        val fianchetto = LineNarrator.describe(listOf("d4", "Nf6", "c4", "g6", "Nc3", "Bg7"))
        assertNotNull(fianchetto)
        assertTrue(fianchetto.contains("fianchetto"), fianchetto)

        // g6 without the bishop is a pawn move, not a plan — and claiming the plan would be
        // asserting an intention the moves do not prove.
        val pawnOnly = LineNarrator.describe(listOf("d4", "Nf6", "c4", "g6"))
        assertTrue(pawnOnly == null || !pawnOnly.contains("fianchetto"), "$pawnOnly")
    }

    @Test
    fun `the base line of an opening gets no line sentence`() {
        // Too short to distinguish anything; the family claim is the better lead for these.
        assertNull(LineNarrator.describe(listOf("e4", "c5")))
        assertNull(LineNarrator.describe(listOf("e4")))
    }

    @Test
    fun `every sentence fits the window the composers truncate at`() {
        val corpus = SeedMain.loadCorpus(Path.of("corpus"))
        val overlong = corpus.mapNotNull { entry ->
            val moves = entry.moves?.split(' ')?.filter(String::isNotBlank) ?: return@mapNotNull null
            LineNarrator.describe(moves)?.takeIf { it.length > LineNarrator.MAX_SENTENCE_CHARS }
        }

        assertTrue(overlong.isEmpty(), "Sentences past the 125-char quote window:\n${overlong.take(5).joinToString("\n")}")
    }

    @Test
    fun `no generated sentence evaluates the position`() {
        // The serious fence. Anything written here is seeded into the corpus, quoted by both
        // composers as a retrieved source, and certified as grounded by every validator — so an
        // invented evaluation becomes an unfalsifiable citation. Code detects; the model narrates.
        val forbidden = listOf(
            "better", "worse", "advantage", "edge", "winning", "losing", "strong", "weak",
            "best", "dubious", "unsound", "comfortable", "equal", "initiative", "pressure",
        )
        val corpus = SeedMain.loadCorpus(Path.of("corpus"))

        val offenders = corpus.mapNotNull { entry ->
            val moves = entry.moves?.split(' ')?.filter(String::isNotBlank) ?: return@mapNotNull null
            val sentence = LineNarrator.describe(moves) ?: return@mapNotNull null
            forbidden.firstOrNull { sentence.lowercase().contains(it) }?.let { "$it → $sentence" }
        }

        assertTrue(offenders.isEmpty(), "Evaluative language in generated claims:\n${offenders.take(5).joinToString("\n")}")
    }

    @Test
    fun `rows in the same ECO no longer open with the same sentence`() {
        // The check EcoNarratorTest never made, and the reason the hand-review failed: retrieval for
        // 1.e4 c5 returned Sicilian Defense, King David's Opening and Myers Attack — three passages
        // whose first sentences were identical.
        val corpus = SeedMain.loadCorpus(Path.of("corpus"))
        val b20 = corpus.filter { it.eco == "B20" && it.moves?.isNotBlank() == true }
        assertTrue(b20.size >= 3, "expected several B20 lines in the corpus, got ${b20.size}")

        val leadSentences = b20.map { it.passage.text.substringBefore('.').trim() }

        assertTrue(
            leadSentences.toSet().size > 1,
            "All ${b20.size} B20 passages still lead with the same claim:\n${leadSentences.first()}",
        )
    }

    @Test
    fun `distinct lines across the corpus mostly say distinct things`() {
        // Corpus-wide version of the above. Not 100%: two different lines can legitimately share a
        // feature ("Black castles kingside on move 5"), and forcing uniqueness would push this into
        // inventing detail. The bar is that an ECO's rows are no longer uniform.
        val corpus = SeedMain.loadCorpus(Path.of("corpus")).filter { it.eco != null && it.moves?.isNotBlank() == true }
        val byEco = corpus.groupBy { it.eco }.filterValues { it.size >= 4 }

        val uniform = byEco.filterValues { group ->
            group.map { it.passage.text.substringBefore('.') }.toSet().size == 1
        }

        assertEquals(
            emptySet(), uniform.keys,
            "These ECOs still give every line the identical opening sentence",
        )
    }
}
