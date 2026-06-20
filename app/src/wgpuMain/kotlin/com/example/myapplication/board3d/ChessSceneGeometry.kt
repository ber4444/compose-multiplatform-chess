package com.example.myapplication.board3d

import com.example.myapplication.board3d.math.Matrix4f
import com.example.myapplication.board3d.math.Vector3f

/** Which embedded texture a draw group samples. */
enum class ChessTexture { BOARD, WHITE, BLACK, FRAME }

/** Interleaved vertices [pos(3), normal(3), uv(2), tint(3)] (11 floats) + indices for one texture.
 *  Optional [tangents] (4 floats per vertex, parallel to vertices) for tangent-space normal mapping
 *  — present only when the source glTF meshes had TANGENT attributes and a normal texture is bound. */
class SceneGroup(val vertices: FloatArray, val indices: IntArray, val tangents: FloatArray = FloatArray(0)) {
    val indexCount get() = indices.size
}

/**
 * Builds world-space, textured geometry for a [Board3DScene], split into one [SceneGroup] per
 * [ChessTexture] (board marble, white wood, black wood) so each can bind its own sampler. Lighting
 * is done per-pixel in the shader; vertices carry world position + world normal + the model's UVs +
 * a tint multiplier (frame stone-grey knock-down). Selection feedback is the piece bounce, not a
 * coloured square, so the board tiles are never tinted. Rebuilt only when the FEN changes; camera
 * moves don't touch this.
 */
class ChessSceneGeometry private constructor(val groups: Map<ChessTexture, SceneGroup>) {
    companion object {
        private const val FLOATS_PER_VERTEX = 11
        private const val BOARD_TILES = 4 // board3.jpg is a 4x4 marble-tile checkerboard
        private val NO_TINT = floatArrayOf(1f, 1f, 1f)
        // The frame's light marble blows out to near-white under the bright env IBL; knock it down to
        // a mid stone grey so the rim reads as stone, not a white halo around the board.
        internal const val FRAME_TINT_VALUE = 0.27f
        private val FRAME_TINT = floatArrayOf(FRAME_TINT_VALUE, FRAME_TINT_VALUE, FRAME_TINT_VALUE)

        fun build(
            scene: Board3DScene,
            meshes: Map<PieceKind, MeshData>,
            frameMesh: MeshData? = null,
            includeGround: Boolean = true,
        ): ChessSceneGeometry {
            val board = Builder()
            val white = Builder()
            val black = Builder()
            val frame = Builder()

            // The big grey floor only makes sense without an environment. With a skybox (wgpu/vkChess
            // look) it would occlude the sky, so callers can skip it and let the board sit in the env.
            if (includeGround) addGround(board)
            addBoard(board)

            if (frameMesh != null) {
                addMesh(frame, frameMesh, Matrix4f(), FRAME_TINT)
            }

            for (piece in scene.pieces) {
                val mesh = meshes[piece.kind] ?: continue
                val angleRad = piece.rotationYDegrees * (kotlin.math.PI.toFloat() / 180f)
                val model = Matrix4f()
                    .translate(piece.position.x, piece.position.y, piece.position.z)
                    .rotateY(angleRad)
                addMesh(if (piece.color == PieceColor.WHITE) white else black, mesh, model)
            }
            return ChessSceneGeometry(
                mapOf(
                    ChessTexture.BOARD to board.toGroup(),
                    ChessTexture.WHITE to white.toGroup(),
                    ChessTexture.BLACK to black.toGroup(),
                    ChessTexture.FRAME to frame.toGroup(),
                ).filterValues { it.indexCount > 0 }
            )
        }

        private fun addMesh(b: Builder, mesh: MeshData, model: Matrix4f, tint: FloatArray = NO_TINT) {
            val normalMatrix = Matrix4f(model).invert().transpose()
            val base = b.vertexCount()
            val p = Vector3f(); val n = Vector3f(); val t = com.example.myapplication.board3d.math.Vector3f()
            val meshHasTan = mesh.hasTangents
            for (v in 0 until mesh.vertexCount) {
                p.set(mesh.positions[v * 3], mesh.positions[v * 3 + 1], mesh.positions[v * 3 + 2])
                model.transformPosition(p)
                n.set(mesh.normals[v * 3], mesh.normals[v * 3 + 1], mesh.normals[v * 3 + 2])
                normalMatrix.transformDirection(n).normalize()
                val tan: FloatArray = if (meshHasTan) {
                    // glTF tangents are already in model space; rotate them by the model matrix's
                    // upper-3x3 to land in world space (chess pieces have no non-uniform scale).
                    t.set(mesh.tangents[v * 4], mesh.tangents[v * 4 + 1], mesh.tangents[v * 4 + 2])
                    model.transformDirection(t)
                    floatArrayOf(t.x, t.y, t.z, mesh.tangents[v * 4 + 3])
                } else {
                    floatArrayOf(1f, 0f, 0f, 1f) // flat fallback so the parallel array stays aligned
                }
                b.vertex(p.x, p.y, p.z, n.x, n.y, n.z, mesh.uvs[v * 2], mesh.uvs[v * 2 + 1], tint, tan)
            }
            for (i in mesh.indices) b.index(base + i)
        }

        /** A large stone-gray floor under/around the board, so it sits on a ground (horizon) rather
         *  than floating on the sky. Sampled from a near-uniform spot of the marble texture + a grey
         *  tint so it reads as flat stone. Part of the BOARD group so it also receives shadows. */
        private fun addGround(b: Builder) {
            val ext = 24f
            val y = -0.02f
            val u = 0.42f; val v = 0.12f               // near-uniform white-marble interior
            val tint = floatArrayOf(0.5f, 0.52f, 0.55f) // flat stone grey
            val base = b.vertexCount()
            // Flat ground: N=+Y, so T=+X, B = N×T×handedness = +Z (handedness +1).
            b.vertex(-ext, y, -ext, 0f, 1f, 0f, u, v, tint, floatArrayOf(1f, 0f, 0f, 1f))
            b.vertex(ext, y, -ext, 0f, 1f, 0f, u, v, tint, floatArrayOf(1f, 0f, 0f, 1f))
            b.vertex(ext, y, ext, 0f, 1f, 0f, u, v, tint, floatArrayOf(1f, 0f, 0f, 1f))
            b.vertex(-ext, y, ext, 0f, 1f, 0f, u, v, tint, floatArrayOf(1f, 0f, 0f, 1f))
            b.index(base); b.index(base + 1); b.index(base + 2)
            b.index(base); b.index(base + 2); b.index(base + 3)
        }

        private fun addBoard(b: Builder) {
            val h = BoardGeometry.SQUARE_SIZE / 2f
            // Procedural board squares are flat (N=+Y), so T=+X, B=+Z (handedness +1) for every vertex.
            val tan = floatArrayOf(1f, 0f, 0f, 1f)
            for (row in 0 until 8) {
                for (col in 0 until 8) {
                    val square = BoardSquare(row, col)
                    val c = BoardGeometry.squareCenter(square)
                    val tint = NO_TINT
                    // Map the square to one marble tile whose colour matches the chess square.
                    val isDark = (row + col) % 2 == 1
                    var tr = row % BOARD_TILES
                    var tc = col % BOARD_TILES
                    val wantBlackTile = isDark // tile (0,0) is black marble
                    if (((tr + tc) % 2 == 0) != wantBlackTile) tc = (tc + 1) % BOARD_TILES
                    val u0 = tc.toFloat() / BOARD_TILES; val u1 = (tc + 1f) / BOARD_TILES
                    val v0 = tr.toFloat() / BOARD_TILES; val v1 = (tr + 1f) / BOARD_TILES
                    val base = b.vertexCount()
                    b.vertex(c.x - h, 0f, c.z - h, 0f, 1f, 0f, u0, v0, tint, tan)
                    b.vertex(c.x + h, 0f, c.z - h, 0f, 1f, 0f, u1, v0, tint, tan)
                    b.vertex(c.x + h, 0f, c.z + h, 0f, 1f, 0f, u1, v1, tint, tan)
                    b.vertex(c.x - h, 0f, c.z + h, 0f, 1f, 0f, u0, v1, tint, tan)
                    b.index(base); b.index(base + 1); b.index(base + 2)
                    b.index(base); b.index(base + 2); b.index(base + 3)
                }
            }
        }
    }

    private class Builder {
        private val verts = ArrayList<Float>(1 shl 14)
        private val tans = ArrayList<Float>(1 shl 14)
        private val idx = ArrayList<Int>(1 shl 14)
        fun vertexCount() = verts.size / FLOATS_PER_VERTEX
        fun vertex(x: Float, y: Float, z: Float, nx: Float, ny: Float, nz: Float, u: Float, v: Float, tint: FloatArray, tan: FloatArray = floatArrayOf(1f, 0f, 0f, 1f)) {
            verts.add(x); verts.add(y); verts.add(z); verts.add(nx); verts.add(ny); verts.add(nz)
            verts.add(u); verts.add(v); verts.add(tint[0]); verts.add(tint[1]); verts.add(tint[2])
            tans.add(tan[0]); tans.add(tan[1]); tans.add(tan[2]); tans.add(tan[3])
        }
        fun index(i: Int) { idx.add(i) }
        fun toGroup() = SceneGroup(verts.toFloatArray(), idx.toIntArray(), tans.toFloatArray())
    }
}
