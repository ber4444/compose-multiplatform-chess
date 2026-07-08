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
    fun testCatmullRomArc() {
        val start = Vec3(-3.5f, 0f, 3.5f)
        val end = Vec3(0.5f, 0f, 0.5f)

        // Endpoints are exact: t=0 -> start, t=1 -> end (piece lands on its square).
        assertVec3Equals(start, catmullRomArc(start, end, 0f))
        assertVec3Equals(end, catmullRomArc(start, end, 1f))

        // Mid-flight the piece hops above the board: the vertical peak is liftHeight/8.
        val mid = catmullRomArc(start, end, 0.5f)
        assertFloatEquals(PIECE_MOVE_LIFT / 8f, mid.y)
        if (mid.y <= 0f) throw AssertionError("expected an upward hop, got y=${mid.y}")

        // Hop stays above the board for the whole flight and is symmetric about the midpoint.
        for (i in 1..9) {
            val t = i / 10f
            if (catmullRomArc(start, end, t).y <= 0f) throw AssertionError("piece dipped below board at t=$t")
        }
        assertFloatEquals(catmullRomArc(start, end, 0.25f).y, catmullRomArc(start, end, 0.75f).y)
    }

    @Test
    fun testSelectionBounce() {
        // The bounce sits on the board at the start of each cycle and peaks at SELECTION_BOUNCE_HEIGHT.
        assertFloatEquals(0f, selectionBounceOffset(0L))
        assertFloatEquals(0f, selectionBounceOffset(SELECTION_BOUNCE_PERIOD_MS))
        assertFloatEquals(SELECTION_BOUNCE_HEIGHT, selectionBounceOffset(SELECTION_BOUNCE_PERIOD_MS / 2))
        // Never sinks below the board.
        for (ms in 0L..SELECTION_BOUNCE_PERIOD_MS * 3 step 17L) {
            if (selectionBounceOffset(ms) < 0f) throw AssertionError("bounce went below board at ${ms}ms")
        }
    }

    @Test
    fun testWithSelectionLift() {
        val scene = Board3DSceneMapper.fromFen("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1")
        val e2 = BoardSquare(6, 4)
        val baseY = scene.pieces.first { it.square == e2 }.position.y

        // No square / no offset returns the same scene unchanged.
        assertEquals(scene, scene.withSelectionLift(null, 0.2f))
        assertEquals(scene, scene.withSelectionLift(e2, 0f))

        val lifted = scene.withSelectionLift(e2, 0.3f)
        assertFloatEquals(baseY + 0.3f, lifted.pieces.first { it.square == e2 }.position.y)
        // Only the selected piece moves; every other piece keeps its y.
        for (p in lifted.pieces) {
            if (p.square != e2) {
                val original = scene.pieces.first { it.square == p.square }
                assertFloatEquals(original.position.y, p.position.y)
            }
        }
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
        val pickedSquare = BoardRayPicker.pickSquare(centerRay, null)
        
        // At origin (0,0,0) which is boundary of e4/e5/d4/d5. Let's pick a clear square.
        val e4Center = BoardGeometry.squareCenter(BoardSquare(4, 4))
        val e4Screen = CameraMath.worldToScreen(camera, e4Center)
        assertNotNull(e4Screen)
        
        val rayE4 = CameraMath.rayFromScreen(camera, e4Screen.first, e4Screen.second)
        val pickedE4 = BoardRayPicker.pickSquare(rayE4, null)
        assertEquals(BoardSquare(4, 4), pickedE4)
    }
}
