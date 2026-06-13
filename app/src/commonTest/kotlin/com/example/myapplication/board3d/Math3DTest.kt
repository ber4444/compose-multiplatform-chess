package com.example.myapplication.board3d

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.math.abs

class Math3DTest {

    private fun assertFloatEquals(expected: Float, actual: Float, epsilon: Float = 1e-4f) {
        if (abs(expected - actual) > epsilon) {
            throw AssertionError("Expected <$expected> but was <$actual>")
        }
    }

    private fun assertVec3Equals(expected: Vec3, actual: Vec3, epsilon: Float = 1e-4f) {
        assertFloatEquals(expected.x, actual.x, epsilon)
        assertFloatEquals(expected.y, actual.y, epsilon)
        assertFloatEquals(expected.z, actual.z, epsilon)
    }

    @Test
    fun testVec3Operations() {
        val v1 = Vec3(1f, 2f, 3f)
        val v2 = Vec3(4f, 5f, 6f)
        
        assertVec3Equals(Vec3(5f, 7f, 9f), v1 + v2)
        assertVec3Equals(Vec3(-3f, -3f, -3f), v1 - v2)
        assertVec3Equals(Vec3(2f, 4f, 6f), v1 * 2f)
        assertFloatEquals(32f, v1.dot(v2))
        assertVec3Equals(Vec3(-3f, 6f, -3f), v1.cross(v2))
    }

    @Test
    fun testBoardGeometry() {
        val a1 = BoardSquare(7, 0)
        val a1Center = BoardGeometry.squareCenter(a1)
        assertVec3Equals(Vec3(-3.5f, 0f, 3.5f), a1Center)

        val e4 = BoardSquare(4, 4)
        val e4Center = BoardGeometry.squareCenter(e4)
        assertVec3Equals(Vec3(0.5f, 0f, 0.5f), e4Center)

        assertEquals(a1, BoardGeometry.squareFromWorld(-3.5f, 3.5f))
        assertEquals(e4, BoardGeometry.squareFromWorld(0.5f, 0.5f))

        assertNull(BoardGeometry.squareFromWorld(5f, 0f))
        assertNull(BoardGeometry.squareFromWorld(0f, -5f))
    }

    @Test
    fun testCameraMathRoundTrip() {
        val camera = CameraParams(
            position = Vec3(0f, 10f, 10f),
            target = Vec3(0f, 0f, 0f),
            up = Vec3(0f, 1f, 0f),
            fovYDegrees = 45f,
            aspect = 1.0f,
            near = 0.1f,
            far = 100f
        )

        // Point at center
        val centerScreen = CameraMath.worldToScreen(camera, Vec3(0f, 0f, 0f))
        assertNotNull(centerScreen)
        assertFloatEquals(0.5f, centerScreen.first)
        assertFloatEquals(0.5f, centerScreen.second)

        // Ray from center
        val centerRay = CameraMath.rayFromScreen(camera, 0.5f, 0.5f)
        val pickedSquare = BoardRayPicker.pickSquare(centerRay)
        
        // At origin (0,0,0) which is boundary of e4/e5/d4/d5. Let's pick a clear square.
        val e4Center = BoardGeometry.squareCenter(BoardSquare(4, 4))
        val e4Screen = CameraMath.worldToScreen(camera, e4Center)
        assertNotNull(e4Screen)
        
        val rayE4 = CameraMath.rayFromScreen(camera, e4Screen.first, e4Screen.second)
        val pickedE4 = BoardRayPicker.pickSquare(rayE4)
        assertEquals(BoardSquare(4, 4), pickedE4)
    }
}
