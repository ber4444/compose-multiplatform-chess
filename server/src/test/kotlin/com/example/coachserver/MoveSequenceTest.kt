package com.example.coachserver

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MoveSequenceTest {
    @Test
    fun `corpus pgn and request san normalize to the same string`() {
        assertEquals(
            MoveSequence.normalizePgn("1. e4 e5 2. Nf3 Nc6 3. Bb5"),
            MoveSequence.normalizeSan(listOf("e4", "e5", "Nf3", "Nc6", "Bb5")),
        )
    }

    @Test
    fun `move numbers results and annotations are stripped`() {
        assertEquals("e4 e5 Nf3", MoveSequence.normalizePgn("1. e4 e5 2. Nf3 1-0"))
        assertEquals("e4 e5", MoveSequence.normalizePgn("1.e4 {a comment} e5"))
        assertEquals("e4 e5", MoveSequence.normalizeSan(listOf("e4!", " e5?? ", "")))
    }

    @Test
    fun `castling and promotion survive normalization`() {
        assertEquals("O-O e8=Q+ Bxf7#", MoveSequence.normalizePgn("12. O-O e8=Q+ 13. Bxf7#"))
    }

    @Test
    fun `prefix matching is token aligned`() {
        // The book query joins on "moves || ' %'", so "e4 e5" must not prefix-match "e4 e5xyz".
        val short = MoveSequence.normalizeSan(listOf("e4", "e5"))
        val long = MoveSequence.normalizeSan(listOf("e4", "e5", "Nf3"))

        assertTrue(long.startsWith("$short "))
    }
}

class EcoNarratorTest {
    @Test
    fun `every ECO code in the checked in corpus is characterized`() {
        val uncharacterized = SeedMain.loadCorpus(java.nio.file.Path.of("corpus"))
            .mapNotNull { it.eco }
            .distinct()
            .filter { EcoNarrator.characterize(it) == null }

        assertTrue(
            uncharacterized.isEmpty(),
            "No range covers: ${uncharacterized.sorted()}. A gap here silently reintroduces the " +
                "tautological passage text for those openings.",
        )
    }

    @Test
    fun `characterizations fit the sentence truncation the composers apply`() {
        // TemplateComposer.sentence takes the leading sentence and truncates at 125 characters;
        // anything longer gets quoted mid-clause.
        val tooLong = ('A'..'E').flatMap { volume ->
            (0..99).mapNotNull { index ->
                val code = "$volume%02d".format(index)
                EcoNarrator.characterize(code)?.takeIf { it.length > 125 }?.let { code to it.length }
            }
        }.distinctBy { it.second }

        assertTrue(tooLong.isEmpty(), "Characterizations exceed the 125-char quote window: $tooLong")
    }

    @Test
    fun `no characterization contains a period except its last character`() {
        // Both composers quote `text.substringBefore('.')`, so an internal period truncates the
        // sentence at that point. Chess notation is full of them: "1.e4" and "...c5" cut the
        // Sicilian's description down to "Sicilian Defence: Black answers 1" in live output.
        // Move names must therefore be written without move numbers or ellipses.
        val truncated = ('A'..'E').flatMap { volume ->
            (0..99).mapNotNull { index ->
                val code = "$volume%02d".format(index)
                EcoNarrator.characterize(code)
                    ?.takeIf { it.dropLast(1).contains('.') }
                    ?.let { code to it.substringBefore('.') }
            }
        }.distinctBy { it.second }

        assertTrue(
            truncated.isEmpty(),
            "These would be quoted truncated at their first period: $truncated",
        )
    }

    @Test
    fun `unparseable codes return null rather than inventing content`() {
        assertNull(EcoNarrator.characterize("ZZ9"))
        assertNull(EcoNarrator.characterize("B"))
        assertNull(EcoNarrator.characterize(""))
    }

    @Test
    fun `seeded passage text leads with content rather than the ECO restatement`() {
        val sicilian = SeedMain.loadCorpus(java.nio.file.Path.of("corpus"))
            .first { it.passage.sourceId == "lichess-b-1-b00" || it.eco == "B20" }

        assertTrue(
            !sicilian.passage.text.substringBefore('.').contains("is classified as ECO"),
            "Leading sentence is still the tautology: ${sicilian.passage.text}",
        )
    }
}
