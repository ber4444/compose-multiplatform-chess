package com.example.myapplication.board3d

import org.joml.Matrix4f
import org.joml.Vector3f
import kotlin.math.max

/**
 * Builds a single world-space, vertex-lit triangle soup for an entire [Board3DScene] — board
 * squares plus every piece — ready to upload to one Vulkan vertex/index buffer and draw in one
 * call. Lighting is computed on the CPU per vertex (a fixed directional light + ambient, using the
 * world-space normal) and baked into the vertex colour; because the light is world-space the result
 * is view-independent, so the camera can move without rebuilding this — only the position FEN
 * changing requires a rebuild. Vertex layout: [x, y, z, r, g, b].
 */
class ChessSceneGeometry(val vertices: FloatArray, val indices: IntArray) {
    val vertexCount get() = vertices.size / 6
    val indexCount get() = indices.size

    companion object {
        private val LIGHT_DIR = Vector3f(-0.4f, -1f, -0.3f).normalize()
        private const val AMBIENT = 0.35f

        private val WHITE_PIECE = floatArrayOf(0.92f, 0.90f, 0.85f)
        private val BLACK_PIECE = floatArrayOf(0.18f, 0.17f, 0.20f)
        private val LIGHT_SQUARE = floatArrayOf(0.85f, 0.82f, 0.72f)
        private val DARK_SQUARE = floatArrayOf(0.36f, 0.45f, 0.55f)
        private val SELECTED_SQUARE = floatArrayOf(0.30f, 0.70f, 0.35f)

        fun build(scene: Board3DScene, meshes: Map<PieceKind, MeshData>): ChessSceneGeometry {
            val verts = ArrayList<Float>(1 shl 16)
            val idx = ArrayList<Int>(1 shl 16)

            addBoard(scene.selectedSquare, verts, idx)

            for (piece in scene.pieces) {
                val mesh = meshes[piece.kind] ?: continue
                val color = if (piece.color == PieceColor.WHITE) WHITE_PIECE else BLACK_PIECE
                val model = Matrix4f()
                    .translate(piece.position.x, piece.position.y, piece.position.z)
                    .rotateY(Math.toRadians(piece.rotationYDegrees.toDouble()).toFloat())
                addMesh(mesh, model, color, verts, idx)
            }
            return ChessSceneGeometry(verts.toFloatArray(), idx.toIntArray())
        }

        private fun addMesh(
            mesh: MeshData,
            model: Matrix4f,
            baseColor: FloatArray,
            verts: ArrayList<Float>,
            idx: ArrayList<Int>,
        ) {
            val normalMatrix = Matrix4f(model).invert().transpose()
            val base = verts.size / 6
            val p = Vector3f()
            val n = Vector3f()
            val vc = mesh.vertexCount
            for (v in 0 until vc) {
                p.set(mesh.positions[v * 3], mesh.positions[v * 3 + 1], mesh.positions[v * 3 + 2])
                model.transformPosition(p)
                n.set(mesh.normals[v * 3], mesh.normals[v * 3 + 1], mesh.normals[v * 3 + 2])
                normalMatrix.transformDirection(n).normalize()
                val lit = shade(n, baseColor)
                verts.add(p.x); verts.add(p.y); verts.add(p.z)
                verts.add(lit[0]); verts.add(lit[1]); verts.add(lit[2])
            }
            for (i in mesh.indices) idx.add(base + i)
        }

        private fun addBoard(selected: BoardSquare?, verts: ArrayList<Float>, idx: ArrayList<Int>) {
            val up = Vector3f(0f, 1f, 0f)
            for (row in 0 until 8) {
                for (col in 0 until 8) {
                    val square = BoardSquare(row, col)
                    val c = BoardGeometry.squareCenter(square)
                    val isDark = (row + col) % 2 == 1
                    val color = when {
                        selected == square -> SELECTED_SQUARE
                        isDark -> DARK_SQUARE
                        else -> LIGHT_SQUARE
                    }
                    val lit = shade(up, color)
                    val h = BoardGeometry.SQUARE_SIZE / 2f
                    val base = verts.size / 6
                    // Top-facing quad at y=0.
                    val corners = arrayOf(
                        floatArrayOf(c.x - h, 0f, c.z - h),
                        floatArrayOf(c.x + h, 0f, c.z - h),
                        floatArrayOf(c.x + h, 0f, c.z + h),
                        floatArrayOf(c.x - h, 0f, c.z + h),
                    )
                    for (corner in corners) {
                        verts.add(corner[0]); verts.add(corner[1]); verts.add(corner[2])
                        verts.add(lit[0]); verts.add(lit[1]); verts.add(lit[2])
                    }
                    idx.add(base); idx.add(base + 1); idx.add(base + 2)
                    idx.add(base); idx.add(base + 2); idx.add(base + 3)
                }
            }
        }

        private fun shade(worldNormal: Vector3f, baseColor: FloatArray): FloatArray {
            val ndl = max(0f, -worldNormal.dot(LIGHT_DIR))
            val intensity = (AMBIENT + (1f - AMBIENT) * ndl).coerceIn(0f, 1f)
            return floatArrayOf(baseColor[0] * intensity, baseColor[1] * intensity, baseColor[2] * intensity)
        }
    }
}
