package com.example.myapplication.board3d

import de.javagl.jgltf.model.AccessorData
import de.javagl.jgltf.model.AccessorDatas
import de.javagl.jgltf.model.GltfModel
import de.javagl.jgltf.model.MeshPrimitiveModel
import de.javagl.jgltf.model.NodeModel
import de.javagl.jgltf.model.io.GltfModelReader
import org.joml.Matrix4f
import org.joml.Vector3f
import java.io.ByteArrayInputStream

/**
 * Interleaved, indexed, normalized geometry for one piece kind.
 * Vertex layout for the Vulkan pipeline: [px, py, pz, nx, ny, nz] per vertex.
 */
class MeshData(
    val positions: FloatArray, // 3 floats per vertex (already normalized: base on y=0, centred in x/z)
    val normals: FloatArray,   // 3 floats per vertex, smooth, unit length
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

/**
 * Loads the six piece template meshes from `chess.glb` (self-contained GLB bytes), keyed by
 * [PieceKind]. Pieces are looked up by the glTF node names recorded in [ChessSetMeshNames]
 * (`king`/`queen`/…). Each piece is recentred in x/z with its base on y=0 and the whole set is
 * scaled by a single factor (derived from the tallest piece) so relative sizes are preserved and
 * the tallest piece is ~[TARGET_KING_HEIGHT] world units. Board squares are 1.0 unit (see
 * [BoardGeometry]); this keeps pieces proportional to the squares without depending on the
 * source model's absolute scale.
 */
object GltfChessMeshes {
    private const val TARGET_KING_HEIGHT = 0.95f

    fun load(glb: ByteArray): Map<PieceKind, MeshData> {
        val model: GltfModel = GltfModelReader().readWithoutReferences(ByteArrayInputStream(glb))
        val nameToKind: Map<String, PieceKind> =
            PieceKind.entries.associateBy { ChessSetMeshNames.getMeshName(it, PieceColor.WHITE) }

        // Raw (node-local-transformed) geometry per kind, before global normalization.
        data class Raw(val pos: FloatArray, val idx: IntArray)
        val raw = HashMap<PieceKind, Raw>()

        for (node in model.nodeModels) {
            val kind = node.name?.let { nameToKind[it] } ?: continue
            if (raw.containsKey(kind)) continue
            val (pos, idx) = collectNodeGeometry(node) ?: continue
            if (pos.isNotEmpty() && idx.isNotEmpty()) raw[kind] = Raw(pos, idx)
        }

        if (raw.isEmpty()) return emptyMap()

        // Single global scale so the tallest piece reaches TARGET_KING_HEIGHT (relative sizes kept).
        var maxHeight = 0f
        for ((_, r) in raw) {
            var minY = Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
            var i = 1
            while (i < r.pos.size) { val y = r.pos[i]; if (y < minY) minY = y; if (y > maxY) maxY = y; i += 3 }
            val h = maxY - minY
            if (h > maxHeight) maxHeight = h
        }
        val scale = if (maxHeight > 0f) TARGET_KING_HEIGHT / maxHeight else 1f

        return raw.mapValues { (_, r) -> normalize(r.pos, r.idx, scale) }
    }

    /** Recentre x/z to 0, drop base to y=0, apply [scale], then compute smooth normals. */
    private fun normalize(pos: FloatArray, idx: IntArray, scale: Float): MeshData {
        var minX = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var minZ = Float.MAX_VALUE; var maxZ = -Float.MAX_VALUE
        run {
            var i = 0
            while (i < pos.size) {
                val x = pos[i]; val y = pos[i + 1]; val z = pos[i + 2]
                if (x < minX) minX = x; if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (z < minZ) minZ = z; if (z > maxZ) maxZ = z
                i += 3
            }
        }
        val cx = (minX + maxX) / 2f
        val cz = (minZ + maxZ) / 2f
        val out = FloatArray(pos.size)
        var i = 0
        while (i < pos.size) {
            out[i] = (pos[i] - cx) * scale
            out[i + 1] = (pos[i + 1] - minY) * scale
            out[i + 2] = (pos[i + 2] - cz) * scale
            i += 3
        }
        return MeshData(out, computeSmoothNormals(out, idx), idx)
    }

    /** Per-vertex normals = normalized sum of incident face normals. */
    private fun computeSmoothNormals(pos: FloatArray, idx: IntArray): FloatArray {
        val normals = FloatArray(pos.size)
        var t = 0
        while (t < idx.size) {
            val a = idx[t] * 3; val b = idx[t + 1] * 3; val c = idx[t + 2] * 3
            val ux = pos[b] - pos[a]; val uy = pos[b + 1] - pos[a + 1]; val uz = pos[b + 2] - pos[a + 2]
            val vx = pos[c] - pos[a]; val vy = pos[c + 1] - pos[a + 1]; val vz = pos[c + 2] - pos[a + 2]
            val nx = uy * vz - uz * vy
            val ny = uz * vx - ux * vz
            val nz = ux * vy - uy * vx
            for (vi in intArrayOf(a, b, c)) {
                normals[vi] += nx; normals[vi + 1] += ny; normals[vi + 2] += nz
            }
            t += 3
        }
        var i = 0
        while (i < normals.size) {
            val x = normals[i]; val y = normals[i + 1]; val z = normals[i + 2]
            val len = kotlin.math.sqrt(x * x + y * y + z * z)
            if (len > 1e-6f) { normals[i] = x / len; normals[i + 1] = y / len; normals[i + 2] = z / len }
            else { normals[i] = 0f; normals[i + 1] = 1f; normals[i + 2] = 0f }
            i += 3
        }
        return normals
    }

    /** Gather positions+indices from a node's mesh primitives (recursing children), with the
     *  node's local transform applied so geometry is in a consistent orientation. */
    private fun collectNodeGeometry(node: NodeModel): Pair<FloatArray, IntArray>? {
        val positions = ArrayList<Float>()
        val indices = ArrayList<Int>()
        val transform = Matrix4f().set(node.computeLocalTransform(null))
        appendNode(node, transform, positions, indices)
        if (positions.isEmpty() || indices.isEmpty()) return null
        return positions.toFloatArray() to indices.toIntArray()
    }

    private fun appendNode(node: NodeModel, transform: Matrix4f, positions: ArrayList<Float>, indices: ArrayList<Int>) {
        for (mesh in node.meshModels) {
            for (prim in mesh.meshPrimitiveModels) {
                appendPrimitive(prim, transform, positions, indices)
            }
        }
        for (child in node.children) {
            val childTransform = Matrix4f(transform).mul(Matrix4f().set(child.computeLocalTransform(null)))
            appendNode(child, childTransform, positions, indices)
        }
    }

    private fun appendPrimitive(prim: MeshPrimitiveModel, transform: Matrix4f, positions: ArrayList<Float>, indices: ArrayList<Int>) {
        if (prim.mode != 4) return // TRIANGLES only
        val posAccessor = prim.attributes["POSITION"] ?: return
        val floats = AccessorDatas.createFloat(posAccessor)
        val baseVertex = positions.size / 3
        val v = Vector3f()
        for (e in 0 until floats.numElements) {
            v.set(floats.get(e, 0), floats.get(e, 1), floats.get(e, 2))
            transform.transformPosition(v)
            positions.add(v.x); positions.add(v.y); positions.add(v.z)
        }
        val idxAccessor = prim.indices
        if (idxAccessor != null) {
            val data: AccessorData = idxAccessor.accessorData
            val n = data.numElements
            val buf = data.createByteBuffer()
            val componentType = idxAccessor.componentType
            for (k in 0 until n) {
                val index = when (componentType) {
                    5121 -> buf.get(k).toInt() and 0xFF                       // unsigned byte
                    5123 -> buf.getShort(k * 2).toInt() and 0xFFFF            // unsigned short
                    5125 -> buf.getInt(k * 4)                                 // unsigned int
                    else -> buf.getShort(k * 2).toInt() and 0xFFFF
                }
                indices.add(baseVertex + index)
            }
        } else {
            val count = floats.numElements
            for (k in 0 until count) indices.add(baseVertex + k)
        }
    }
}
