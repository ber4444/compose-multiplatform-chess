package com.example.myapplication.board3d

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Landscape `GameScreen` reserves the bottom of the 3D viewport for the coach text and the camera
 * lifts the board clear of it. The lift must leave the board's size alone and picking exact.
 */
class ViewportReserveTest {

    private val phoneLandscape = 2400f / 1080f
    private val reserveTop = 85f / 1080f
    private val reserveBottom = 290f / 1080f

    private fun screenY(camera: CameraParams, point: Vec3) = CameraMath.worldToScreen(camera, point)!!.second

    @Test
    fun noReserveLeavesTheCameraAlone() {
        val plain = OrbitCameraController(phoneLandscape)
        val reserved = OrbitCameraController(phoneLandscape).apply { setViewportReserve(0f, 0f) }
        assertEquals(plain.camera, reserved.camera)
    }

    @Test
    fun theBoardClearsTheBottomReserveWithoutShrinking() {
        val plain = OrbitCameraController(phoneLandscape).camera
        val lifted = OrbitCameraController(phoneLandscape).apply { setViewportReserve(reserveTop, reserveBottom) }.camera

        val frameNearBottom = Vec3(0f, -0.255f, 5.15f)
        assertTrue(screenY(plain, frameNearBottom) > 1f - reserveBottom, "precondition: the frame starts under the reserve")
        assertTrue(screenY(lifted, frameNearBottom) <= 1f - reserveBottom + 1e-3f, "frame still under the coach strip")
        assertTrue(screenY(lifted, Vec3(0f, 1.95f, -3.5f)) >= reserveTop, "far king pushed under the status bar")
        // A translation along the view's up axis: same direction, same distance, same size.
        assertEquals((plain.target - plain.position).length(), (lifted.target - lifted.position).length(), 1e-4f)
        assertEquals(
            (plain.target - plain.position).normalized().y,
            (lifted.target - lifted.position).normalized().y,
            1e-5f,
        )
    }

    @Test
    fun everySquarePicksItselfThroughTheLiftedCamera() {
        for (yaw in listOf(0f, OrbitCameraController.BLACK_YAW_DEG)) {
            val camera = OrbitCameraController(phoneLandscape, yaw)
                .apply { setViewportReserve(reserveTop, reserveBottom) }.camera
            for (row in 0..7) for (col in 0..7) {
                val square = BoardSquare(row, col)
                val (x, y) = CameraMath.worldToScreen(camera, BoardGeometry.squareCenter(square))!!
                assertEquals(square, BoardRayPicker.pickSquare(CameraMath.rayFromScreen(camera, x, y), null), "yaw $yaw")
            }
        }
    }
}
