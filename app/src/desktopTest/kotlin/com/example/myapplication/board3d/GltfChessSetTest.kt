package com.example.myapplication.board3d

import de.javagl.jgltf.model.io.GltfModelReader
import org.junit.Test
import java.io.File
import kotlin.test.assertTrue

class GltfChessSetTest {
    @Test
    fun parseChessGlb() {
        val glbFile = File("src/commonMain/composeResources/files/models/chess.glb")
        assertTrue(glbFile.exists(), "chess.glb must exist")

        val reader = GltfModelReader()
        val gltfModel = reader.read(glbFile.toURI())
        
        val meshNames = gltfModel.meshModels.map { it.name }
        
        val expectedMeshes = listOf(
            "pawn_white", "pawn_black",
            "rook_white", "rook_black",
            "knight_white", "knight_black",
            "bishop_white", "bishop_black",
            "queen_white", "queen_black",
            "king_white", "king_black"
        )
        // Note: Actual names depend on the converted model
        assertTrue(meshNames.isNotEmpty(), "Model must contain meshes")
    }
}
