package com.example.myapplication.board3d

/**
 * Indexed, normalized geometry for one piece kind. Vertex streams are parallel
 * (one entry per vertex): [positions] (3), [normals] (3, smooth, unit), [uvs] (2, TEXCOORD_0).
 */
class MeshData(
    val positions: FloatArray,
    val normals: FloatArray,
    val uvs: FloatArray,
    val indices: IntArray,
) {
    val vertexCount get() = positions.size / 3
    val triangleCount get() = indices.size / 3
    fun boundingHeight(): Float {
        if (positions.isEmpty()) return 0f
        var minY = Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        var i = 1
        while (i < positions.size) { val y = positions[i]; if (y < minY) minY = y; if (y > maxY) maxY = y; i += 3 }
        return maxY - minY
    }
}
