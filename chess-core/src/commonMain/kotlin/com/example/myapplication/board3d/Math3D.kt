package com.example.myapplication.board3d

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

data class Vec3(val x: Float, val y: Float, val z: Float) {
    operator fun plus(other: Vec3) = Vec3(x + other.x, y + other.y, z + other.z)
    operator fun minus(other: Vec3) = Vec3(x - other.x, y - other.y, z - other.z)
    operator fun times(scalar: Float) = Vec3(x * scalar, y * scalar, z * scalar)
    fun dot(other: Vec3) = x * other.x + y * other.y + z * other.z
    fun cross(other: Vec3) = Vec3(
        y * other.z - z * other.y,
        z * other.x - x * other.z,
        x * other.y - y * other.x
    )
    fun length() = sqrt(x * x + y * y + z * z)
    fun normalized(): Vec3 {
        val len = length()
        return if (len > 0f) this * (1f / len) else Vec3(0f, 0f, 0f)
    }
    fun lerp(other: Vec3, t: Float): Vec3 {
        return Vec3(
            x + (other.x - x) * t,
            y + (other.y - y) * t,
            z + (other.z - z) * t
        )
    }
}

/**
 * Piece-move animation tuning, carried over from vkChess (`VkChess::animatePce`). vkChess lifts the
 * spline's outer control points by 10 units on a 2-unit-square board (peak hop ~0.6 squares) over
 * ~0.5s; our board uses 1-unit squares, so [PIECE_MOVE_LIFT] is scaled for the same relative hop.
 */
internal const val PIECE_MOVE_LIFT: Float = 4f
internal const val PIECE_MOVE_DURATION_MS: Long = 500L

/**
 * Arc/hop interpolation carried over from vkChess (`animatePce`): a Catmull-Rom spline through
 * [start] and [end] whose two outer control points are lifted by [liftHeight]. The piece eases out
 * of its square, arcs up off the board, and settles back down onto the target instead of sliding
 * flat across it. [t] is normalized progress in [0,1]; it returns [start] at 0 and [end] at 1, with
 * a parabolic vertical hop peaking at [liftHeight]/8 around the midpoint.
 */
internal fun catmullRomArc(start: Vec3, end: Vec3, t: Float, liftHeight: Float = PIECE_MOVE_LIFT): Vec3 {
    // vUp points "down" (-y) so the lifted outer control points pull the spline's tangents upward,
    // mirroring vkChess where the same vec3(0,-10,0) produced an upward hop in its camera convention.
    val up = Vec3(0f, -liftHeight, 0f)
    val p0 = start + up
    val p3 = end + up
    val s2 = t * t
    val s3 = s2 * t
    val f0 = -s3 + 2f * s2 - t
    val f1 = 3f * s3 - 5f * s2 + 2f
    val f2 = -3f * s3 + 4f * s2 + t
    val f3 = s3 - s2
    return (p0 * f0 + start * f1 + end * f2 + p3 * f3) * 0.5f
}

/**
 * Selection feedback: instead of a coloured square/disc under the picked piece, the piece itself
 * bounces in place. [SELECTION_BOUNCE_HEIGHT] is the peak lift (world units, ~⅙ of a square) and
 * [SELECTION_BOUNCE_PERIOD_MS] is one full hop. [selectionBounceOffset] returns the current lift for
 * the time elapsed since selection: a `|sin|` curve so the piece springs up off the board and lands
 * back on it each cycle.
 */
internal const val SELECTION_BOUNCE_HEIGHT: Float = 0.16f
internal const val SELECTION_BOUNCE_PERIOD_MS: Long = 520L

internal fun selectionBounceOffset(elapsedMs: Long): Float {
    val phase = (elapsedMs % SELECTION_BOUNCE_PERIOD_MS).toFloat() / SELECTION_BOUNCE_PERIOD_MS
    return SELECTION_BOUNCE_HEIGHT * kotlin.math.abs(sin(phase * PI.toFloat()))
}

/** Copy of this scene with the piece on [square] raised by [dy] (drives the selection bounce). */
internal fun Board3DScene.withSelectionLift(square: BoardSquare?, dy: Float): Board3DScene {
    if (square == null || dy == 0f) return this
    return copy(pieces = pieces.map { p ->
        if (p.square == square) p.copy(position = p.position.copy(y = p.position.y + dy)) else p
    })
}

data class Ray(val origin: Vec3, val direction: Vec3)

data class CameraParams(
    val position: Vec3, val target: Vec3, val up: Vec3,
    val fovYDegrees: Float, val aspect: Float, val near: Float, val far: Float,
)

object CameraMath {
    /**
     * The vertical FOV the renderers actually project with. In portrait (aspect < 1) every backend
     * widens the vertical FOV to hold a fixed ~60° horizontal FOV so the board still fits the narrow
     * width (see the `aspect < 1` branch in each renderer's camera update). Picking has to invert the
     * projection that's on screen, so ray/screen math must use this, not the raw fovYDegrees —
     * otherwise taps land one or more ranks off in portrait. Landscape (aspect ≥ 1) is unchanged.
     */
    private fun effectiveFovYRad(camera: CameraParams): Float =
        if (camera.aspect < 1f) {
            val tanHalfFovX = kotlin.math.tan((60f * PI.toFloat() / 180f) / 2f)
            2f * kotlin.math.atan(tanHalfFovX / camera.aspect)
        } else {
            camera.fovYDegrees * PI.toFloat() / 180f
        }

    /** xNorm/yNorm in [0,1], origin top-left (matches Board3DInput.Tap). */
    fun rayFromScreen(camera: CameraParams, xNorm: Float, yNorm: Float): Ray {
        val fovYRad = effectiveFovYRad(camera)
        val tanHalfFov = kotlin.math.tan(fovYRad / 2f)
        val ndcX = (2f * xNorm - 1f)
        val ndcY = -(2f * yNorm - 1f) // flip Y so top-left is +y in camera space
        
        // Ray direction in camera space
        val viewX = ndcX * camera.aspect * tanHalfFov
        val viewY = ndcY * tanHalfFov
        val viewZ = -1f
        
        val camDir = Vec3(viewX, viewY, viewZ).normalized()
        
        // Transform to world space
        val forward = (camera.target - camera.position).normalized()
        val right = forward.cross(camera.up).normalized()
        val up = right.cross(forward).normalized()
        
        val worldDir = (right * camDir.x) + (up * camDir.y) - (forward * camDir.z)
        
        return Ray(camera.position, worldDir.normalized())
    }

    /** Inverse, for round-trip tests: world point -> normalized screen coords (null if behind camera). */
    fun worldToScreen(camera: CameraParams, point: Vec3): Pair<Float, Float>? {
        val forward = (camera.target - camera.position).normalized()
        val right = forward.cross(camera.up).normalized()
        val up = right.cross(forward).normalized()
        
        val toPoint = point - camera.position
        val z = -toPoint.dot(forward)
        
        if (z >= 0f) return null // behind camera
        
        val x = toPoint.dot(right)
        val y = toPoint.dot(up)

        val fovYRad = effectiveFovYRad(camera)
        val tanHalfFov = kotlin.math.tan(fovYRad / 2f)
        
        val viewX = x / -z
        val viewY = y / -z
        
        val ndcX = viewX / (camera.aspect * tanHalfFov)
        val ndcY = viewY / tanHalfFov
        
        val xNorm = (ndcX + 1f) / 2f
        val yNorm = (1f - ndcY) / 2f // undo Y flip
        
        return Pair(xNorm, yNorm)
    }
}

object BoardRayPicker {
    private data class PieceHitProxy(val radius: Float, val height: Float)

    /**
     * Cylinders approximate the rendered chess meshes. **[height] is the piece's real on-board
     * height** — the `chess.glb` node's POSITION bounds times the fixed 0.5 model scale every
     * backend instances with (`kModelScale` on iOS, its twin in `AndroidBoard3D`). These were
     * originally half these values, i.e. the 0.5 scale applied twice, which made the **upper half of
     * every piece transparent to taps**: a tap on the white king's crown fell through to the board
     * plane and picked e3, two ranks behind it, so selecting a back-rank piece by its visible top
     * silently selected an empty square instead. Measured on an iPhone 17 simulator, the king
     * renders ~77pt tall and the top ~30pt of it did not select it.
     *
     * [radius] is deliberately *not* the mesh's max radius: that is the base flare (0.31–0.46),
     * while the body above it is much narrower, and a cylinder that wide would shadow the squares
     * behind a piece far beyond what it actually occludes. These are body-width values and are
     * unchanged.
     *
     * A consequence worth knowing before "fixing" it: from the default white camera the e1 king
     * really does occlude the e2 pawn completely (verified against the render, not just the math),
     * so that pawn cannot be tapped until the camera is orbited. Shortening the proxies to make it
     * reachable is what produced the see-through tops.
     */
    private fun hitProxy(kind: PieceKind): PieceHitProxy = when (kind) {
        PieceKind.KING -> PieceHitProxy(radius = 0.26f, height = 1.95f)
        PieceKind.QUEEN -> PieceHitProxy(radius = 0.24f, height = 1.69f)
        PieceKind.BISHOP -> PieceHitProxy(radius = 0.21f, height = 1.45f)
        PieceKind.KNIGHT -> PieceHitProxy(radius = 0.22f, height = 1.29f)
        PieceKind.ROOK -> PieceHitProxy(radius = 0.22f, height = 1.21f)
        PieceKind.PAWN -> PieceHitProxy(radius = 0.20f, height = 1.06f)
    }

    /**
     * Picks the board square the ray meets first. Candidates are the y=0 board plane and (when a
     * [scene] is given) one vertical cylinder per piece; the nearest hit to the camera wins, so a
     * tap on a tall piece selects that piece rather than the empty square its top projects onto.
     * Null if the ray is parallel/behind/off-board.
     */
    fun pickSquare(ray: Ray, scene: Board3DScene?): BoardSquare? {
        // Board plane y = 0.
        var planeT = Float.MAX_VALUE
        var planeSquare: BoardSquare? = null
        if (kotlin.math.abs(ray.direction.y) >= 1e-6f) {
            val t = -ray.origin.y / ray.direction.y
            if (t >= 0f) {
                val hit = ray.origin + (ray.direction * t)
                planeSquare = BoardGeometry.squareFromWorld(hit.x, hit.z)
                planeT = t
            }
        }

        // Nearest piece body the ray enters (if any). Sorting by entry distance, NOT perpendicular
        // distance, is what makes this correct: the ray passes within radius of several pieces, but
        // only the one it reaches first is the one actually on screen at that pixel.
        var bestT = planeT
        var bestSquare = planeSquare
        if (scene != null) {
            for (piece in scene.pieces) {
                val proxy = hitProxy(piece.kind)
                val t = rayCylinderEntry(ray, piece.position.x, piece.position.z, proxy.radius, proxy.height)
                if (t != null && t < bestT) {
                    bestT = t
                    bestSquare = piece.square
                }
            }
        }
        return bestSquare
    }

    /** Nearest positive t at which [ray] enters a vertical cylinder (center x/z, radius r, y in
     *  [0,height]), via the side wall or the top cap; null if it misses. */
    private fun rayCylinderEntry(ray: Ray, cx: Float, cz: Float, r: Float, height: Float): Float? {
        var best = Float.MAX_VALUE
        val ox = ray.origin.x - cx
        val oz = ray.origin.z - cz
        val dx = ray.direction.x
        val dz = ray.direction.z

        // Side wall: |horizontal(origin + t*dir)| = r.
        val a = dx * dx + dz * dz
        if (a > 1e-8f) {
            val b = 2f * (ox * dx + oz * dz)
            val c = ox * ox + oz * oz - r * r
            val disc = b * b - 4f * a * c
            if (disc >= 0f) {
                val sq = kotlin.math.sqrt(disc)
                for (t in floatArrayOf((-b - sq) / (2f * a), (-b + sq) / (2f * a))) {
                    if (t > 1e-4f) {
                        val y = ray.origin.y + ray.direction.y * t
                        if (y in 0f..height && t < best) best = t
                    }
                }
            }
        }

        // Top cap (y = height): relevant when looking down onto a piece.
        if (kotlin.math.abs(ray.direction.y) > 1e-6f) {
            val t = (height - ray.origin.y) / ray.direction.y
            if (t > 1e-4f && t < best) {
                val hx = ox + dx * t
                val hz = oz + dz * t
                if (hx * hx + hz * hz <= r * r) best = t
            }
        }

        return if (best == Float.MAX_VALUE) null else best
    }
}

/** Pure visual camera state machine (yaw/pitch/distance around board center). */
class OrbitCameraController(private var aspect: Float) {
    private var yawDegrees = 0f
    // Defaults match DEFAULT_WHITE_VIEW so a fresh controller == the default white view.
    private var pitchDegrees = DEFAULT_PITCH_DEG
    private var distance = DEFAULT_DISTANCE
    private val center = Vec3(0f, 0f, 0f)

    val camera: CameraParams
        get() {
            val yawRad = yawDegrees * PI.toFloat() / 180f
            val pitchRad = pitchDegrees * PI.toFloat() / 180f
            val x = distance * sin(yawRad) * cos(pitchRad)
            val y = distance * sin(pitchRad)
            val z = distance * cos(yawRad) * cos(pitchRad)
            return CameraParams(
                position = Vec3(x, y, z),
                target = center,
                up = Vec3(0f, 1f, 0f),
                fovYDegrees = FOV_Y_DEG,
                aspect = aspect,
                near = 0.1f,
                far = 100f
            )
        }

    fun onDrag(deltaXNorm: Float, deltaYNorm: Float) {
        yawDegrees -= deltaXNorm * 180f
        pitchDegrees += deltaYNorm * 90f
        if (pitchDegrees < 15f) pitchDegrees = 15f
        if (pitchDegrees > 85f) pitchDegrees = 85f
    }

    fun onZoom(factor: Float) {
        distance *= factor
        if (distance < 6f) distance = 6f
        if (distance > 26f) distance = 26f
    }

    fun onResize(newAspect: Float) {
        aspect = newAspect
    }

    companion object {
        // The 3D board is laid out square (GameScreen: fillMaxWidth().aspectRatio(1f)), so the
        // viewport aspect is ~1 on every platform. These defaults keep the playable 8x8 board
        // inside the square while allowing the decorative rim to crop at the screen edge.
        private const val DEFAULT_PITCH_DEG = 33f
        private const val DEFAULT_DISTANCE = 12.0f
        /** Vertical FOV the renderers project with (equals horizontal FOV in the square viewport). */
        const val FOV_Y_DEG = 50f

        val DEFAULT_WHITE_VIEW: CameraParams
            get() = OrbitCameraController(1f).apply {
                yawDegrees = 0f
                pitchDegrees = DEFAULT_PITCH_DEG
                distance = DEFAULT_DISTANCE
            }.camera
    }
}

/**
 * Camera owner whose lifetime is the game screen, not an individual platform renderer.
 *
 * Renderer surfaces are disposable (notably SceneView during 2D/3D switches), but camera input is
 * user state. Keeping one controller above those surfaces means every renderer creation consumes
 * the same canonical snapshot instead of deriving a new projection from backend state.
 */
class Board3DSessionState(initialAspect: Float = 1f) {
    private val controller = OrbitCameraController(initialAspect)

    val camera: CameraParams get() = controller.camera

    fun cameraForRenderer(): CameraParams = camera

    fun onDrag(deltaXNorm: Float, deltaYNorm: Float) = controller.onDrag(deltaXNorm, deltaYNorm)

    fun onZoom(factor: Float) = controller.onZoom(factor)

    fun onResize(aspect: Float) = controller.onResize(aspect)
}
