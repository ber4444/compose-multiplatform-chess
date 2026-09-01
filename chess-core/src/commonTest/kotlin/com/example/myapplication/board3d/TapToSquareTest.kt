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
    fun tapOnPieceTopPicksThatPieceNotTheSquareBehindIt() {
        // The proxy heights were half the rendered piece height (the 0.5 model scale applied twice),
        // which made the upper half of every piece transparent to taps: on an iPhone 17 a tap on the
        // white king's crown fell through to the board plane and picked e3. The user sees a king, so
        // the picker must return the king for every point on it — including near its top.
        val king = BoardSquare(7, 4) // e1
        val center = BoardGeometry.squareCenter(king)
        val scene = Board3DScene(
            pieces = listOf(
                Piece3DInstance(PieceKind.KING, PieceColor.WHITE, king, center, 0f),
            ),
            sideToMove = PieceColor.WHITE,
        )

        // Just under the crown of the rendered king (chess.glb king is 1.949 tall at model scale).
        val crown = Vec3(center.x, 1.9f, center.z)
        val screen = CameraMath.worldToScreen(camera, crown)
        assertNotNull(screen)
        val ray = CameraMath.rayFromScreen(camera, screen.first, screen.second)

        assertEquals(king, BoardRayPicker.pickSquare(ray, scene))
        // Plane-only picking lands ranks away — that gap is exactly what the user saw.
        kotlin.test.assertNotEquals(king, BoardRayPicker.pickSquare(ray, null))
    }

    @Test
    fun tapOnVisiblePawnBodyIsNotStolenByForegroundPiece() {
        // The other half of the trade-off: proxies must not be so tall/fat that a piece steals taps
        // aimed past it. A rook (1.21) is short enough that the pawn behind it clears its top from
        // the default camera, so a tap on the pawn's head must select the pawn, not the rook.
        //
        // Deliberately NOT a king in front: chess.glb's king is 1.95 tall and from this camera it
        // occludes the e2 pawn outright — verified against the render on an iPhone 17, where no part
        // of that pawn is visible above the crown. Asserting the pawn wins there would be asserting
        // that the picker disagrees with the picture, and shrinking the proxies to satisfy it is
        // what produced the see-through tops above.
        val rook = BoardSquare(7, 4) // e1
        val pawn = BoardSquare(6, 4) // e2
        val pawnCenter = BoardGeometry.squareCenter(pawn)
        val scene = Board3DScene(
            pieces = listOf(
                Piece3DInstance(PieceKind.ROOK, PieceColor.WHITE, rook, BoardGeometry.squareCenter(rook), 0f),
                Piece3DInstance(PieceKind.PAWN, PieceColor.WHITE, pawn, pawnCenter, 0f),
            ),
            sideToMove = PieceColor.WHITE,
        )

        val visiblePawnBody = Vec3(pawnCenter.x, 1.0f, pawnCenter.z)
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
