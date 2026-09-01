package com.example.myapplication

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * The 2D board's half of "draw the board from the player's end". The grid is laid out in view
 * positions and every other thing on the screen — selection, legal-move dots, the animated piece,
 * the test tags — is in board squares, so the mapping between them is the whole flip.
 */
class BoardFlipTest {

    @Test
    fun `white sees the board as stored`() {
        for (row in 0..7) {
            for (column in 0..7) {
                assertEquals(Pair(row, column), viewToBoard(row, column, playingWhite = true))
            }
        }
    }

    @Test
    fun `black sees the board rotated a half turn`() {
        // Board rows run 0 = rank 8 … 7 = rank 1, columns 0 = file a. From Black's end the near
        // corner is h1, not a1.
        assertEquals(Pair(7, 7), viewToBoard(0, 0, playingWhite = false)) // top-left  shows h1
        assertEquals(Pair(0, 0), viewToBoard(7, 7, playingWhite = false)) // bottom-right shows a8
        assertEquals(Pair(6, 4), viewToBoard(1, 3, playingWhite = false)) // e2
    }

    @Test
    fun `the flip is its own inverse`() {
        for (row in 0..7) {
            for (column in 0..7) {
                val square = viewToBoard(row, column, playingWhite = false)
                assertEquals(Pair(row, column), boardToView(square, playingWhite = false))
            }
        }
    }

    @Test
    fun `flipping preserves square colour`() {
        // The light/dark pattern is `(row + column) % 2` over the *board* square. A half-turn keeps
        // that parity, which is why the flipped board needs no second colouring rule — but only
        // because it is a rotation. A mirror would not, and would put a light square on the wrong
        // corner.
        for (row in 0..7) {
            for (column in 0..7) {
                val square = viewToBoard(row, column, playingWhite = false)
                assertEquals((row + column) % 2, (square.first + square.second) % 2)
            }
        }
    }

    @Test
    fun `an off-board position is not dragged onto the board`() {
        // Castling leaves the secondary rook slots at INVALID_POSITION when no rook is moving, and
        // the animation overlay maps them before checking. (-1,-1) must not become (8,8).
        assertEquals(INVALID_POSITION, boardToView(INVALID_POSITION, playingWhite = false))
        assertEquals(INVALID_POSITION, boardToView(INVALID_POSITION, playingWhite = true))
    }

    @Test
    fun `black and white disagree about where a square is drawn`() {
        val e2 = Pair(6, 4)
        assertNotEquals(
            boardToView(e2, playingWhite = true),
            boardToView(e2, playingWhite = false),
        )
    }
}
