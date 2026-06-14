package com.example.myapplication.board3d

import org.joml.Matrix4f
import org.joml.Vector3f

/** Which embedded texture a draw group samples. */
enum class ChessTexture { BOARD, WHITE, BLACK }

/** Interleaved vertices [pos(3), normal(3), uv(2), tint(3)] (11 floats) + indices for one texture. */
class SceneGroup(val vertices: FloatArray, val indices: IntArray) {
    val indexCount get() = indices.size
}

/**
 * Builds world-space, textured geometry for a [Board3DScene], split into one [SceneGroup] per
 * [ChessTexture] (board marble, white wood, black wood) so each can bind its own sampler. Lighting
 * is done per-pixel in the shader; vertices carry world position + world normal + the model's UVs +
 * a tint multiplier (used to flush the selected square green). Rebuilt only when the FEN/selection
 * changes; camera moves don't touch this.
 */
class ChessSceneGeometry private constructor(val groups: Map<ChessTexture, SceneGroup>) {
    companion object {
        private const val FLOATS_PER_VERTEX = 11
        private const val BOARD_TILES = 4 // board3.jpg is a 4x4 marble-tile checkerboard
        private val NO_TINT = floatArrayOf(1f, 1f, 1f)
        private val SELECT_TINT = floatArrayOf(0.45f, 1.7f, 0.55f)

        fun build(scene: Board3DScene, meshes: Map<PieceKind, MeshData>): ChessSceneGeometry {
            val board = Builder()
            val white = Builder()
            val black = Builder()

            addGround(board)
            addBoard(board, scene.selectedSquare)

            for (piece in scene.pieces) {
                val mesh = meshes[piece.kind] ?: continue
                val model = Matrix4f()
                    .translate(piece.position.x, piece.position.y, piece.position.z)
                    .rotateY(Math.toRadians(piece.rotationYDegrees.toDouble()).toFloat())
                addMesh(if (piece.color == PieceColor.WHITE) white else black, mesh, model)
            }
            return ChessSceneGeometry(
                mapOf(
                    ChessTexture.BOARD to board.toGroup(),
                    ChessTexture.WHITE to white.toGroup(),
                    ChessTexture.BLACK to black.toGroup(),
                )
            )
        }

        private fun addMesh(b: Builder, mesh: MeshData, model: Matrix4f) {
            val normalMatrix = Matrix4f(model).invert().transpose()
            val base = b.vertexCount()
            val p = Vector3f(); val n = Vector3f()
            for (v in 0 until mesh.vertexCount) {
                p.set(mesh.positions[v * 3], mesh.positions[v * 3 + 1], mesh.positions[v * 3 + 2])
                model.transformPosition(p)
                n.set(mesh.normals[v * 3], mesh.normals[v * 3 + 1], mesh.normals[v * 3 + 2])
                normalMatrix.transformDirection(n).normalize()
                b.vertex(p.x, p.y, p.z, n.x, n.y, n.z, mesh.uvs[v * 2], mesh.uvs[v * 2 + 1], NO_TINT)
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
            b.vertex(-ext, y, -ext, 0f, 1f, 0f, u, v, tint)
            b.vertex(ext, y, -ext, 0f, 1f, 0f, u, v, tint)
            b.vertex(ext, y, ext, 0f, 1f, 0f, u, v, tint)
            b.vertex(-ext, y, ext, 0f, 1f, 0f, u, v, tint)
            b.index(base); b.index(base + 1); b.index(base + 2)
            b.index(base); b.index(base + 2); b.index(base + 3)
        }

        private fun addBoard(b: Builder, selected: BoardSquare?) {
            val h = BoardGeometry.SQUARE_SIZE / 2f
            for (row in 0 until 8) {
                for (col in 0 until 8) {
                    val square = BoardSquare(row, col)
                    val c = BoardGeometry.squareCenter(square)
                    val tint = if (selected == square) SELECT_TINT else NO_TINT
                    // Map the square to one marble tile whose colour matches the chess square.
                    val isDark = (row + col) % 2 == 1
                    var tr = row % BOARD_TILES
                    var tc = col % BOARD_TILES
                    val wantBlackTile = isDark // tile (0,0) is black marble
                    if (((tr + tc) % 2 == 0) != wantBlackTile) tc = (tc + 1) % BOARD_TILES
                    val u0 = tc.toFloat() / BOARD_TILES; val u1 = (tc + 1f) / BOARD_TILES
                    val v0 = tr.toFloat() / BOARD_TILES; val v1 = (tr + 1f) / BOARD_TILES
                    val base = b.vertexCount()
                    b.vertex(c.x - h, 0f, c.z - h, 0f, 1f, 0f, u0, v0, tint)
                    b.vertex(c.x + h, 0f, c.z - h, 0f, 1f, 0f, u1, v0, tint)
                    b.vertex(c.x + h, 0f, c.z + h, 0f, 1f, 0f, u1, v1, tint)
                    b.vertex(c.x - h, 0f, c.z + h, 0f, 1f, 0f, u0, v1, tint)
                    b.index(base); b.index(base + 1); b.index(base + 2)
                    b.index(base); b.index(base + 2); b.index(base + 3)
                }
            }
        }
    }

    private class Builder {
        private val verts = ArrayList<Float>(1 shl 14)
        private val idx = ArrayList<Int>(1 shl 14)
        fun vertexCount() = verts.size / FLOATS_PER_VERTEX
        fun vertex(x: Float, y: Float, z: Float, nx: Float, ny: Float, nz: Float, u: Float, v: Float, tint: FloatArray) {
            verts.add(x); verts.add(y); verts.add(z); verts.add(nx); verts.add(ny); verts.add(nz)
            verts.add(u); verts.add(v); verts.add(tint[0]); verts.add(tint[1]); verts.add(tint[2])
        }
        fun index(i: Int) { idx.add(i) }
        fun toGroup() = SceneGroup(verts.toFloatArray(), idx.toIntArray())
    }
}
