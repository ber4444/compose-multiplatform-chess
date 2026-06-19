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
 * Loads the six piece template meshes (positions + UVs + smooth normals) from `chess.glb`, keyed by
 * [PieceKind] via [ChessSetMeshNames] node names. Pieces are recentred in x/z with base on y=0 and
 * scaled by a single factor (from the tallest piece) so relative sizes are preserved and the tallest
 * is ~[TARGET_KING_HEIGHT] world units. UVs are the model's TEXCOORD_0, used to sample the wood atlas.
 */
object GltfChessMeshes {
    private const val TARGET_KING_HEIGHT = 1.9f

    fun load(glb: ByteArray): Map<PieceKind, MeshData> {
        val model: GltfModel = GltfModelReader().readWithoutReferences(ByteArrayInputStream(glb))
        val nameToKind: Map<String, PieceKind> =
            PieceKind.entries.associateBy { ChessSetMeshNames.getMeshName(it, PieceColor.WHITE) }

        class Raw(val pos: FloatArray, val uv: FloatArray, val tan: FloatArray, val idx: IntArray)
        val raw = HashMap<PieceKind, Raw>()

        for (node in model.nodeModels) {
            val kind = node.name?.let { nameToKind[it] } ?: continue
            if (raw.containsKey(kind)) continue
            val collected = collectNodeGeometry(node) ?: continue
            if (collected.first.isNotEmpty() && collected.fourth.isNotEmpty()) {
                raw[kind] = Raw(collected.first, collected.second, collected.third, collected.fourth)
            }
        }
        if (raw.isEmpty()) return emptyMap()

        var maxHeight = 0f
        for ((_, r) in raw) {
            var minY = Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
            var i = 1
            while (i < r.pos.size) { val y = r.pos[i]; if (y < minY) minY = y; if (y > maxY) maxY = y; i += 3 }
            val h = maxY - minY
            if (h > maxHeight) maxHeight = h
        }
        val scale = if (maxHeight > 0f) TARGET_KING_HEIGHT / maxHeight else 1f

        return raw.mapValues { (_, r) -> normalize(r.pos, r.uv, r.tan, r.idx, scale) }
    }

    /**
     * Loads the engraved board frame (the `frame`/`Cube` node, material `Material`) from `chess.glb`,
     * scaled by 0.5 so the glb board (square size 2, 8x8 spanning +/-8) lines up with the procedural
     * board (square size 1, +/-4): glb a1 center (-7,7) -> (-3.5,3.5). UVs map the A-H/rank labels onto
     * the marble-speckled texture. Returns null if the node is absent.
     */
    fun loadFrame(glb: ByteArray): MeshData? {
        val model = GltfModelReader().readWithoutReferences(ByteArrayInputStream(glb))
        val node = model.nodeModels.firstOrNull { it.name == "frame" } ?: return null
        val (pos, uv, tan, idx) = collectNodeGeometry(node) ?: return null
        val scaled = FloatArray(pos.size) { pos[it] * 0.5f }
        val uvs = if (uv.size == (pos.size / 3) * 2) uv else FloatArray((pos.size / 3) * 2)
        return MeshData(scaled, computeSmoothNormals(scaled, idx), uvs, idx, scaleTangents(tan, 0.5f))
    }

    private fun normalize(pos: FloatArray, uv: FloatArray, tan: FloatArray, idx: IntArray, scale: Float): MeshData {
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
        val uvs = if (uv.size == (pos.size / 3) * 2) uv else FloatArray((pos.size / 3) * 2)
        return MeshData(out, computeSmoothNormals(out, idx), uvs, idx, scaleTangents(tan, scale))
    }

    /** Scale tangent XYZ by [scale]; w (handedness) is scale-invariant. Pass-through if empty. */
    private fun scaleTangents(tan: FloatArray, scale: Float): FloatArray {
        if (tan.isEmpty()) return tan
        val out = FloatArray(tan.size)
        var i = 0
        while (i < tan.size) {
            out[i] = tan[i] * scale
            out[i + 1] = tan[i + 1] * scale
            out[i + 2] = tan[i + 2] * scale
            out[i + 3] = tan[i + 3] // handedness sign, not a position
            i += 4
        }
        return out
    }

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
            for (vi in intArrayOf(a, b, c)) { normals[vi] += nx; normals[vi + 1] += ny; normals[vi + 2] += nz }
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

    data class Quad<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)

    fun collectNodeGeometry(node: NodeModel): Quad<FloatArray, FloatArray, FloatArray, IntArray>? {
        val positions = ArrayList<Float>()
        val uvs = ArrayList<Float>()
        val tangents = ArrayList<Float>()
        val indices = ArrayList<Int>()
        val transform = Matrix4f().set(node.computeLocalTransform(null))
        appendNode(node, transform, positions, uvs, tangents, indices)
        if (positions.isEmpty() || indices.isEmpty()) return null
        return Quad(positions.toFloatArray(), uvs.toFloatArray(), tangents.toFloatArray(), indices.toIntArray())
    }

    private fun appendNode(node: NodeModel, transform: Matrix4f, positions: ArrayList<Float>, uvs: ArrayList<Float>, tangents: ArrayList<Float>, indices: ArrayList<Int>) {
        for (mesh in node.meshModels) for (prim in mesh.meshPrimitiveModels) appendPrimitive(prim, transform, positions, uvs, tangents, indices)
        for (child in node.children) {
            val childTransform = Matrix4f(transform).mul(Matrix4f().set(child.computeLocalTransform(null)))
            appendNode(child, childTransform, positions, uvs, tangents, indices)
        }
    }

    private fun appendPrimitive(prim: MeshPrimitiveModel, transform: Matrix4f, positions: ArrayList<Float>, uvs: ArrayList<Float>, tangents: ArrayList<Float>, indices: ArrayList<Int>) {
        if (prim.mode != 4) return // TRIANGLES only
        val posAccessor = prim.attributes["POSITION"] ?: return
        val floats = AccessorDatas.createFloat(posAccessor)
        val uvAccessor = prim.attributes["TEXCOORD_0"]
        val uvFloats = uvAccessor?.let { AccessorDatas.createFloat(it) }
        val tanAccessor = prim.attributes["TANGENT"]
        val tanFloats = tanAccessor?.let { AccessorDatas.createFloat(it) }
        val baseVertex = positions.size / 3
        val v = Vector3f()
        for (e in 0 until floats.numElements) {
            v.set(floats.get(e, 0), floats.get(e, 1), floats.get(e, 2))
            transform.transformPosition(v)
            positions.add(v.x); positions.add(v.y); positions.add(v.z)
            if (uvFloats != null) { uvs.add(uvFloats.get(e, 0)); uvs.add(uvFloats.get(e, 1)) } else { uvs.add(0f); uvs.add(0f) }
            // glTF TANGENT is vec4: xyz (direction) + w (handedness sign). Transform xyz by the
            // upper-3x3 (rotation/scale) — tangents are directions in model space, so the normal
            // matrix (inverse-transpose) is technically correct, but the chess set has no
            // non-uniform scaling so the plain transform3x3 is fine.
            if (tanFloats != null) {
                val tx = tanFloats.get(e, 0); val ty = tanFloats.get(e, 1); val tz = tanFloats.get(e, 2); val tw = tanFloats.get(e, 3)
                val transformed = Vector3f(tx, ty, tz).mulPosition(transform)
                tangents.add(transformed.x); tangents.add(transformed.y); tangents.add(transformed.z); tangents.add(tw)
            } else if (tangents.isNotEmpty()) {
                tangents.add(1f); tangents.add(0f); tangents.add(0f); tangents.add(1f) // keep parallel arrays aligned
            }
        }
        val idxAccessor = prim.indices
        if (idxAccessor != null) {
            val data: AccessorData = idxAccessor.accessorData
            val n = data.numElements
            val buf = data.createByteBuffer()
            when (idxAccessor.componentType) {
                5121 -> for (k in 0 until n) indices.add(baseVertex + (buf.get(k).toInt() and 0xFF))
                5125 -> for (k in 0 until n) indices.add(baseVertex + buf.getInt(k * 4))
                else -> for (k in 0 until n) indices.add(baseVertex + (buf.getShort(k * 2).toInt() and 0xFFFF))
            }
        } else {
            for (k in 0 until floats.numElements) indices.add(baseVertex + k)
        }
    }
}
