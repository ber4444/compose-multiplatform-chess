package com.example.myapplication.board3d

import org.junit.Test
import java.io.File

class ObjExportTest {
    @Test
    fun `export chess glb to obj files`() {
        val glbBytes = File("src/commonMain/composeResources/files/models/chess.glb").readBytes()
        val meshes = GltfChessMeshes.load(glbBytes)
        
        val outputDir = File("build/obj_export")
        outputDir.mkdirs()
        
        val model: de.javagl.jgltf.model.GltfModel = de.javagl.jgltf.model.io.GltfModelReader().readWithoutReferences(java.io.ByteArrayInputStream(glbBytes))
        // First pass: extract pieces
        for (node in model.nodeModels) {
            val name = node.name ?: continue
            val kind = node.name?.let { PieceKind.entries.find { pk -> ChessSetMeshNames.getMeshName(pk, PieceColor.WHITE) == it } }
            if (kind == null) continue
            val meshData = meshes[kind] ?: continue
            
            val sb = java.lang.StringBuilder()
            sb.append("o $name\n")
            val positions = meshData.positions
            val normals = meshData.normals
            val indices = meshData.indices
            val uvs = meshData.uvs
            
            for (i in 0 until positions.size - 2 step 3) {
                sb.append("v ${positions[i]} ${positions[i+1]} ${positions[i+2]}\n")
            }
            for (i in 0 until uvs.size - 1 step 2) {
                sb.append("vt ${uvs[i]} ${1.0f - uvs[i+1]}\n") // Obj uses flipped V
            }
            for (i in 0 until normals.size - 2 step 3) {
                sb.append("vn ${normals[i]} ${normals[i+1]} ${normals[i+2]}\n")
            }
            for (i in 0 until indices.size - 2 step 3) {
                val i1 = indices[i] + 1
                val i2 = indices[i+1] + 1
                val i3 = indices[i+2] + 1
                sb.append("f $i1/$i1/$i1 $i2/$i2/$i2 $i3/$i3/$i3\n")
            }
            val outFile = File(outputDir, "${kind.name}.obj")
            outFile.writeText(sb.toString())
            println("Exported ${outFile.absolutePath}")
        }
        
        // Second pass: extract Board
        val sbBoard = java.lang.StringBuilder()
        sbBoard.append("o BOARD\n")
        
        val h = 0.5f // BoardGeometry.SQUARE_SIZE / 2f
        val BOARD_TILES = 4
        var vertexOffset = 1
        
        for (row in 0 until 8) {
            for (col in 0 until 8) {
                // c is center of square
                val cx = (col - 3.5f) * 1.0f // BoardGeometry.squareCenter
                val cz = (row - 3.5f) * 1.0f
                
                val isDark = (row + col) % 2 == 1
                var tr = row % BOARD_TILES
                var tc = col % BOARD_TILES
                val wantBlackTile = isDark
                if (((tr + tc) % 2 == 0) != wantBlackTile) tc = (tc + 1) % BOARD_TILES
                val u0 = tc.toFloat() / BOARD_TILES
                val u1 = (tc + 1f) / BOARD_TILES
                val v0 = tr.toFloat() / BOARD_TILES
                val v1 = (tr + 1f) / BOARD_TILES
                
                // 4 vertices per square
                sbBoard.append("v ${cx - h} 0.0 ${cz - h}\n")
                sbBoard.append("v ${cx + h} 0.0 ${cz - h}\n")
                sbBoard.append("v ${cx + h} 0.0 ${cz + h}\n")
                sbBoard.append("v ${cx - h} 0.0 ${cz + h}\n")
                
                sbBoard.append("vt $u0 ${1.0f - v0}\n")
                sbBoard.append("vt $u1 ${1.0f - v0}\n")
                sbBoard.append("vt $u1 ${1.0f - v1}\n")
                sbBoard.append("vt $u0 ${1.0f - v1}\n")
                
                sbBoard.append("vn 0.0 1.0 0.0\n")
                sbBoard.append("vn 0.0 1.0 0.0\n")
                sbBoard.append("vn 0.0 1.0 0.0\n")
                sbBoard.append("vn 0.0 1.0 0.0\n")
                
                val i1 = vertexOffset
                val i2 = vertexOffset + 1
                val i3 = vertexOffset + 2
                val i4 = vertexOffset + 3
                
                sbBoard.append("f $i1/$i1/$i1 $i3/$i3/$i3 $i2/$i2/$i2\n")
                sbBoard.append("f $i1/$i1/$i1 $i4/$i4/$i4 $i3/$i3/$i3\n")
                
                vertexOffset += 4
            }
        }
        
        val outFileBoard = File(outputDir, "BOARD.obj")
        outFileBoard.writeText(sbBoard.toString())
        println("Exported ${outFileBoard.absolutePath}")

        // Third pass: extract Textures
        for (img in model.imageModels) {
            val name = img.name ?: continue
            val data = img.imageData ?: continue
            val bytes = ByteArray(data.remaining()).also { data.duplicate().get(it) }
            val ext = if (bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte()) "png" else "jpg"
            val texFile = File(outputDir, "$name.$ext")
            texFile.writeBytes(bytes)
            println("Exported Texture: ${texFile.absolutePath}")
        }
    }
}
