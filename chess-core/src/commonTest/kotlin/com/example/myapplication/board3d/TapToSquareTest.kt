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
        return BoardRayPicker.pickSquare(ray, null)
    }

    private val squares = listOf(
        BoardSquare(7, 4), // e1
        BoardSquare(6, 4), // e2 (white pawn)
        BoardSquare(4, 4), // e4
        BoardSquare(3, 3), // d5
        BoardSquare(0, 0), // a8
        BoardSquare(7, 7), // h1
    )

    @Test
    fun tapResolvesToSquareUnderDefaultCamera() {
        for (square in squares) {
            assertEquals(square, tap(square), "tap at centre of $square should pick $square")
        }
    }

    @Test
    fun tapResolvesInPortrait() {
        // In portrait (aspect < 1) the renderers widen the vertical FOV to hold a fixed ~60°
        // horizontal FOV. `rayFromScreen` must invert THAT projection, not the base 42° fovY, or
        // taps land a rank off — the original iOS off-by-one. We project each square via the
        // renderer's formula independently here, so this fails if CameraMath ever reverts to fovY.
        val portrait = OrbitCameraController.DEFAULT_WHITE_VIEW.copy(aspect = 0.46f)
        for (square in squares) {
            val screen = projectViaRendererPortrait(portrait, BoardGeometry.squareCenter(square))
            assertNotNull(screen, "square $square should project in front of the camera")
            val ray = CameraMath.rayFromScreen(portrait, screen.first, screen.second)
            assertEquals(square, BoardRayPicker.pickSquare(ray, null), "portrait tap at $square should pick $square")
        }
    }

    @Test
    fun tapOnTallPieceBodyPicksThatPieceNotTheSquareBehindIt() {
        // From this low default camera a piece's body projects several ranks behind its base square,
        // so plane-only picking sends a tap on a piece off by ranks. Picking with the scene present
        // must select the piece the ray actually passes through. Regression guard for the picker.
        val king = BoardSquare(7, 4) // e1
        val center = BoardGeometry.squareCenter(king)
        val scene = Board3DScene(
            pieces = listOf(
                Piece3DInstance(PieceKind.KING, PieceColor.WHITE, king, center, 0f),
            ),
            sideToMove = PieceColor.WHITE,
        )

        // Aim at a point partway up the king's body (not its base).
        val bodyPoint = Vec3(center.x, 0.65f, center.z)
        val screen = CameraMath.worldToScreen(camera, bodyPoint)
        assertNotNull(screen)
        val ray = CameraMath.rayFromScreen(camera, screen.first, screen.second)

        // With the scene, the ray hits the king's cylinder -> e1.
        assertEquals(king, BoardRayPicker.pickSquare(ray, scene))
        // Plane-only picking would land on a different (farther) square: proves the scene mattered.
        kotlin.test.assertNotEquals(king, BoardRayPicker.pickSquare(ray, null))
    }

    @Test
    fun tapOnVisiblePawnBodyIsNotStolenByForegroundPiece() {
        // From White's low camera the e1 king sits in front of the e2 pawn. The hit proxy must stay
        // close to the rendered piece bounds; otherwise a tap on the visible upper body of the pawn
        // intersects the invisible oversized king proxy first and selects e1 on iOS.
        val king = BoardSquare(7, 4)
        val pawn = BoardSquare(6, 4)
        val pawnCenter = BoardGeometry.squareCenter(pawn)
        val scene = Board3DScene(
            pieces = listOf(
                Piece3DInstance(PieceKind.KING, PieceColor.WHITE, king, BoardGeometry.squareCenter(king), 0f),
                Piece3DInstance(PieceKind.PAWN, PieceColor.WHITE, pawn, pawnCenter, 0f),
            ),
            sideToMove = PieceColor.WHITE,
        )

        val visiblePawnBody = Vec3(pawnCenter.x, 0.35f, pawnCenter.z)
        val screen = CameraMath.worldToScreen(camera, visiblePawnBody)
        assertNotNull(screen)
        val ray = CameraMath.rayFromScreen(camera, screen.first, screen.second)

        assertEquals(pawn, BoardRayPicker.pickSquare(ray, scene))
    }

    /** Mirrors the renderers' portrait projection (fixed ~60° horizontal FOV), independent of CameraMath. */
    private fun projectViaRendererPortrait(camera: CameraParams, point: Vec3): Pair<Float, Float>? {
        val forward = (camera.target - camera.position).normalized()
        val right = forward.cross(camera.up).normalized()
        val up = right.cross(forward).normalized()
        val toPoint = point - camera.position
        val z = -toPoint.dot(forward)
        if (z >= 0f) return null
        val tanHalfFovX = kotlin.math.tan((60f * kotlin.math.PI.toFloat() / 180f) / 2f)
        val ndcX = (toPoint.dot(right) / -z) / tanHalfFovX               // fixed horizontal FOV
        val ndcY = (toPoint.dot(up) / -z) / (tanHalfFovX / camera.aspect) // vertical derived from it
        return Pair((ndcX + 1f) / 2f, (1f - ndcY) / 2f)
    }
}
