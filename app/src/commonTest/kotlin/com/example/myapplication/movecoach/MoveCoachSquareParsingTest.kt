package com.example.myapplication.movecoach

import com.example.myapplication.algebraicToSquare
import com.example.myapplication.board3d.BoardSquare
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * B16 board anchoring: which squares a coach line names, and where they land on the board.
 *
 * The regression these pin is the original `\b[a-h][1-8]\b`. A word boundary cannot occur between
 * `N` and `f3`, so it matched plain algebraic and silently skipped SAN — which is how the coach
 * writes nearly every square it mentions, meaning the highlight almost never fired.
 */
class MoveCoachSquareParsingTest {

    @Test
    fun `finds squares written in plain algebraic`() {
        assertEquals(
            listOf("e4", "d5"),
            MoveCoachManager.squaresNamedIn("The pawn on e4 is challenged by d5."),
        )
    }

    @Test
    fun `finds squares written in SAN with a piece letter`() {
        assertEquals(
            listOf("f3", "c4"),
            MoveCoachManager.squaresNamedIn("Nf3 develops, and Bc4 eyes the weak square."),
        )
    }

    @Test
    fun `finds squares in a SAN capture`() {
        assertEquals(listOf("c4"), MoveCoachManager.squaresNamedIn("Bxc4+ wins a pawn."))
    }

    @Test
    fun `deduplicates and lowercases`() {
        assertEquals(listOf("f3"), MoveCoachManager.squaresNamedIn("Nf3 is best; after Nf3 you are fine."))
    }

    @Test
    fun `returns nothing for prose with no squares`() {
        assertEquals(emptyList(), MoveCoachManager.squaresNamedIn("This develops a piece and takes the center."))
    }

    @Test
    fun `maps algebraic squares onto board coordinates`() {
        // a8 is the top-left of the rendered board (row 0), h1 the bottom-right (row 7).
        assertEquals(BoardSquare(0, 0), algebraicToSquare("a8"))
        assertEquals(BoardSquare(7, 7), algebraicToSquare("h1"))
        assertEquals(BoardSquare(4, 4), algebraicToSquare("e4"))
    }

    @Test
    fun `rejects anything that is not a square`() {
        assertNull(algebraicToSquare(""))
        assertNull(algebraicToSquare("e"))
        assertNull(algebraicToSquare("e9"))
        assertNull(algebraicToSquare("z4"))
        assertNull(algebraicToSquare("Nf3"))
    }
}
