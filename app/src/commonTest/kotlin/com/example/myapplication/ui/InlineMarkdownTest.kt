package com.example.myapplication.ui

import androidx.compose.ui.text.font.FontWeight
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class InlineMarkdownTest {

    @Test
    fun boldMarkersAreRenderedNotShown() {
        val out = InlineMarkdown.render("It **hangs a piece** here.")
        assertEquals("It hangs a piece here.", out.text)
        val bold = out.spanStyles.single()
        assertEquals(FontWeight.Bold, bold.item.fontWeight)
        assertEquals("hangs a piece", out.text.substring(bold.start, bold.end))
    }

    /** The line from the reported screenshot, verbatim. */
    @Test
    fun modelBulletListReadsAsProse() {
        val out = InlineMarkdown.render(
            "*   **Why d4 was better:** It was stronger than the best move.\n" +
                "*   **What it means:** This move is considered one of the best."
        )
        assertTrue('*' !in out.text, "asterisks survived: ${out.text}")
        assertTrue(out.text.startsWith("Why d4 was better:"), out.text)
        assertEquals(2, out.spanStyles.size, "both headings should be bold")
    }

    @Test
    fun unpairedMarkerIsLeftAlone() {
        // A truncated answer ends mid-emphasis; swallowing the rest of the line is worse than
        // showing the two characters.
        val out = InlineMarkdown.render("It **hangs a piece")
        assertEquals("It **hangs a piece", out.text)
        assertTrue(out.spanStyles.isEmpty())
    }

    @Test
    fun asteriskInsideASentenceIsNotAListMarker() {
        val out = InlineMarkdown.render("Nf3* is one annotation style.")
        assertEquals("Nf3* is one annotation style.", out.text)
    }

    @Test
    fun numberedChessNotationIsNotTreatedAsAList() {
        val out = InlineMarkdown.render("1. e4 e5 2. Nf3")
        assertEquals("1. e4 e5 2. Nf3", out.text)
    }

    @Test
    fun plainTextIsUnchanged() {
        val plain = "It fights for the center."
        assertEquals(plain, InlineMarkdown.render(plain).text)
    }
}
