package com.example.myapplication.board3d

import org.junit.Assume
import org.junit.Test
import java.io.File
import kotlin.test.assertTrue

class GltfChessMeshesTest {
    private fun glbBytes(): ByteArray? {
        val f = File("src/commonMain/composeResources/files/models/chess.glb")
        return if (f.exists()) f.readBytes() else null
    }

    @Test
    fun loadsAllSixPieceMeshes() {
        val bytes = glbBytes()
        Assume.assumeTrue("chess.glb present", bytes != null)
        val meshes = GltfChessMeshes.load(bytes!!)

        for (kind in PieceKind.entries) {
            val mesh = meshes[kind]
            assertTrue(mesh != null, "missing mesh for $kind")
            assertTrue(mesh.vertexCount > 0, "$kind has no vertices")
            assertTrue(mesh.triangleCount > 0, "$kind has no triangles")
            assertTrue(mesh.normals.size == mesh.positions.size, "$kind normal count mismatch")
            // Normalized: base on y=0, positive height. Upper bound covers the 2× piece scale
            // (TARGET_KING_HEIGHT = 1.9 in GltfChessMeshes).
            val h = mesh.boundingHeight()
            assertTrue(h in 0.05f..2.0f, "$kind normalized height out of range: $h")
            var minY = Float.MAX_VALUE
            var i = 1
            while (i < mesh.positions.size) { if (mesh.positions[i] < minY) minY = mesh.positions[i]; i += 3 }
            assertTrue(kotlin.math.abs(minY) < 1e-3f, "$kind base not on y=0: minY=$minY")
        }

        // Relative sizes preserved: pawn shorter than king.
        val pawnH = meshes[PieceKind.PAWN]!!.boundingHeight()
        val kingH = meshes[PieceKind.KING]!!.boundingHeight()
        assertTrue(pawnH < kingH, "pawn ($pawnH) should be shorter than king ($kingH)")
    }
}
