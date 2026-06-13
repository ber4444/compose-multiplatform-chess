package com.example.myapplication.board3d

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Verifies the tap-to-move pick path the host uses: a tap at the screen projection of a square's
 * centre, fed through `rayFromScreen` -> `pickSquare`, resolves back to that square. Uses the
 * default white-side camera so it matches what the user sees on first open.
 */
class TapToSquareTest {

    private val camera = OrbitCameraController.DEFAULT_WHITE_VIEW

    private fun tap(square: BoardSquare): BoardSquare? {
        val center = BoardGeometry.squareCenter(square)
        val screen = CameraMath.worldToScreen(camera, center)
        assertNotNull(screen, "square $square should project in front of the camera")
        val ray = CameraMath.rayFromScreen(camera, screen.first, screen.second)
        return BoardRayPicker.pickSquare(ray)
    }

    @Test
    fun tapResolvesToSquareUnderDefaultCamera() {
        // A representative spread: white back rank, a white pawn, centre, black side, corners.
        for (square in listOf(
            BoardSquare(7, 4), // e1
            BoardSquare(6, 4), // e2 (white pawn)
            BoardSquare(4, 4), // e4
            BoardSquare(3, 3), // d5
            BoardSquare(0, 0), // a8
            BoardSquare(7, 7), // h1
        )) {
            assertEquals(square, tap(square), "tap at centre of $square should pick $square")
        }
    }
}
