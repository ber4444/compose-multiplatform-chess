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
}

data class Ray(val origin: Vec3, val direction: Vec3)

data class CameraParams(
    val position: Vec3, val target: Vec3, val up: Vec3,
    val fovYDegrees: Float, val aspect: Float, val near: Float, val far: Float,
)

object CameraMath {
    /** xNorm/yNorm in [0,1], origin top-left (matches Board3DInput.Tap). */
    fun rayFromScreen(camera: CameraParams, xNorm: Float, yNorm: Float): Ray {
        val fovYRad = camera.fovYDegrees * PI.toFloat() / 180f
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
        
        val fovYRad = camera.fovYDegrees * PI.toFloat() / 180f
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
    /** Intersects the ray with the y=0 plane, then BoardGeometry.squareFromWorld.
     *  Null if parallel/behind/off-board. */
    fun pickSquare(ray: Ray): BoardSquare? {
        // Plane y = 0. t = -ray.origin.y / ray.direction.y
        if (kotlin.math.abs(ray.direction.y) < 1e-6f) return null // Parallel
        val t = -ray.origin.y / ray.direction.y
        if (t < 0f) return null // Behind origin
        
        val hitPoint = ray.origin + (ray.direction * t)
        return BoardGeometry.squareFromWorld(hitPoint.x, hitPoint.z)
    }
}

/** Pure visual camera state machine (yaw/pitch/distance around board center). */
class OrbitCameraController(private var aspect: Float) {
    private var yawDegrees = 0f
    private var pitchDegrees = 22f   // matches DEFAULT_WHITE_VIEW so a fresh controller == the default white view
    private var distance = 10.5f
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
                fovYDegrees = 42f,
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
        if (distance > 20f) distance = 20f
    }

    fun onResize(newAspect: Float) {
        aspect = newAspect
    }

    companion object {
        val DEFAULT_WHITE_VIEW: CameraParams
            get() = OrbitCameraController(1f).apply {
                yawDegrees = 0f
                pitchDegrees = 22f
                distance = 10.5f
            }.camera
    }
}
