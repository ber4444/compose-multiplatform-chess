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
}
