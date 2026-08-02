package com.example.myapplication.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class CitationSanitizerTest {

    @Test
    fun `strips lichess citation tags`() {
        val input = "Nf3 develops the knight and controls e5 [lichess-c-955-c55]."
        val result = CitationSanitizer.sanitize(input)
        assertEquals("Nf3 develops the knight and controls e5.", result)
    }

    @Test
    fun `strips on device passages and rule citation tags`() {
        val input = "White starts first [initial-position] and pawns move forward [board-goal]."
        val result = CitationSanitizer.sanitize(input)
        assertEquals("White starts first and pawns move forward.", result)
    }

    @Test
    fun `leaves text without brackets untouched`() {
        val input = "Standard chess text without citations."
        val result = CitationSanitizer.sanitize(input)
        assertEquals("Standard chess text without citations.", result)
    }

    @Test
    fun `preserves move citations for the board-jump affordance`() {
        val input = "Your first slip was [move-14], and the game turned at [move-22] [lichess-c20]."
        val result = CitationSanitizer.sanitize(input)
        assertEquals("Your first slip was [move-14], and the game turned at [move-22].", result)
    }

    @Test
    fun `streaming view hides a tag that has not closed yet`() {
        // Mid-stream the closing bracket has not arrived; the fragment must not render.
        assertEquals(
            "The center is contested",
            CitationSanitizer.sanitizeStreaming("The center is contested [lichess-c2"),
        )
        assertEquals(
            "The center is contested",
            CitationSanitizer.sanitizeStreaming("The center is contested ["),
        )
    }

    @Test
    fun `streaming view keeps a completed move citation`() {
        assertEquals(
            "The game turned at [move-22]",
            CitationSanitizer.sanitizeStreaming("The game turned at [move-22]"),
        )
    }
}
