package com.example.myapplication.board3d

import kotlinx.coroutines.await
import com.example.myapplication.board3d.math.Matrix4f
import com.example.myapplication.board3d.math.Vector3f
import org.w3c.dom.ImageBitmap
import kotlin.js.Promise

object WasmGltfLoader {
    private const val TARGET_KING_HEIGHT = 0.95f

    suspend fun loadMeshes(glb: ByteArray): Map<PieceKind, MeshData> {
        val (jsonStr, binData) = parseGlb(glb)
        val json = parseJson(jsonStr)

        val nameToKind: Map<String, PieceKind> =
            PieceKind.entries.associateBy { ChessSetMeshNames.getMeshName(it, PieceColor.WHITE) }

        val nodes = getArrayProp(json, "nodes") ?: return emptyMap()
        val meshes = getArrayProp(json, "meshes")
        val accessors = getArrayProp(json, "accessors")
        val bufferViews = getArrayProp(json, "bufferViews")

        class Raw(val pos: FloatArray, val uv: FloatArray, val idx: IntArray)
        val raw = HashMap<PieceKind, Raw>()

        for (i in 0 until nodes.length) {
            val node = nodes[i]!!
            val name = getStringProp(node, "name")
            val kind = name?.let { nameToKind[it] } ?: continue
            if (raw.containsKey(kind)) continue

            val collected = collectNodeGeometry(node, nodes, meshes, accessors, bufferViews, binData, Matrix4f()) ?: continue
            if (collected.first.isNotEmpty() && collected.third.isNotEmpty()) {
                raw[kind] = Raw(collected.first, collected.second, collected.third)
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

        return raw.mapValues { (_, r) -> normalize(r.pos, r.uv, r.idx, scale) }
    }

    suspend fun loadTextures(glb: ByteArray): Map<ChessTexture, TextureImage> {
        val (jsonStr, binData) = parseGlb(glb)
        val json = parseJson(jsonStr)
        val images = getArrayProp(json, "images") ?: return emptyMap()
        val bufferViews = getArrayProp(json, "bufferViews") ?: return emptyMap()

        val NAME_BY_TEXTURE = mapOf(
            ChessTexture.BOARD to "board3",
            ChessTexture.WHITE to "whites",
            ChessTexture.BLACK to "blacks",
        )

        val result = HashMap<ChessTexture, TextureImage>()

        for (i in 0 until images.length) {
            val imgNode = images[i]!!
            val name = getStringProp(imgNode, "name") ?: continue
            val tex = NAME_BY_TEXTURE.entries.firstOrNull { it.value == name }?.key ?: continue
            
            val bvIdx = getIntProp(imgNode, "bufferView") ?: continue
            val bv = bufferViews[bvIdx]!!
            val byteOffset = getIntProp(bv, "byteOffset") ?: 0
            val byteLength = getIntProp(bv, "byteLength") ?: 0
            
            val mimeType = getStringProp(imgNode, "mimeType") ?: "image/jpeg"
            
            val slice = binData.copyOfRange(byteOffset, byteOffset + byteLength)
            val textureImage = decodeImage(slice, mimeType)
            result[tex] = textureImage
        }
        return result
    }

    private suspend fun decodeImage(bytes: ByteArray, mimeType: String): TextureImage {
        val u8array = org.khronos.webgl.Uint8Array(bytes.size)
        for (i in bytes.indices) setByteJs(u8array.toJsReference(), i, bytes[i])
        val blob = createBlob(u8array, mimeType)
        val bitmap = createImageBitmap(blob).await<JsAny>()
        
        val w = getBitmapWidth(bitmap)
        val h = getBitmapHeight(bitmap)
        
        val canvas = createOffscreenCanvas(w, h)
        val ctx = getContext2D(canvas)
        drawImage(ctx, bitmap)
        
        val len = getImageDataLen(ctx, w, h)
        val arr = ByteArray(len)
        for (i in 0 until len) {
            arr[i] = getImageDataByte(ctx, w, h, i).toByte()
        }
        return TextureImage(w, h, arr)
    }

    private fun parseGlb(glb: ByteArray): Pair<String, ByteArray> {
        var pos = 12
        
        fun readInt(): Int {
            val res = (glb[pos].toInt() and 0xFF) or
                      ((glb[pos + 1].toInt() and 0xFF) shl 8) or
                      ((glb[pos + 2].toInt() and 0xFF) shl 16) or
                      ((glb[pos + 3].toInt() and 0xFF) shl 24)
            pos += 4
            return res
        }

        val chunk0Len = readInt()
        pos += 4 // type
        val jsonBytes = glb.copyOfRange(pos, pos + chunk0Len)
        val jsonStr = jsonBytes.decodeToString()
        pos += chunk0Len

        val chunk1Len = readInt()
        pos += 4 // type
        val binData = glb.copyOfRange(pos, pos + chunk1Len)
        
        return Pair(jsonStr, binData)
    }

    private fun collectNodeGeometry(
        node: JsAny, 
        nodes: JsArray<JsAny>?, 
        meshes: JsArray<JsAny>?, 
        accessors: JsArray<JsAny>?, 
        bufferViews: JsArray<JsAny>?, 
        binData: ByteArray,
        transform: Matrix4f
    ): Triple<FloatArray, FloatArray, IntArray>? {
        val positions = ArrayList<Float>()
        val uvs = ArrayList<Float>()
        val indices = ArrayList<Int>()

        val nodeTransform = computeLocalTransform(node)
        val combined = Matrix4f(transform).mul(nodeTransform)

        val meshIdx = getIntProp(node, "mesh")
        if (meshIdx != null && meshes != null) {
            val mesh = meshes[meshIdx]!!
            val prims = getArrayProp(mesh, "primitives")
            if (prims != null) {
                for (i in 0 until prims.length) {
                    appendPrimitive(prims[i]!!, combined, accessors, bufferViews, binData, positions, uvs, indices)
                }
            }
        }

        val children = getArrayProp(node, "children")
        if (children != null && nodes != null) {
            for (i in 0 until children.length) {
                val childIdxObj = children[i]!!
                val childIdx = jsNumberToInt(childIdxObj)
                collectNodeGeometry(nodes[childIdx]!!, nodes, meshes, accessors, bufferViews, binData, combined)?.let {
                    for (v in it.first) positions.add(v)
                    for (u in it.second) uvs.add(u)
                    val base = indices.size
                    for (idx in it.third) indices.add(base + idx)
                }
            }
        }

        if (positions.isEmpty() || indices.isEmpty()) return null
        return Triple(positions.toFloatArray(), uvs.toFloatArray(), indices.toIntArray())
    }

    private fun computeLocalTransform(node: JsAny): Matrix4f {
        val mat = Matrix4f()
        val matrix = getArrayProp(node, "matrix")
        if (matrix != null) {
            val f = FloatArray(16)
            for (i in 0 until 16) {
                f[i] = jsNumberToFloat(matrix[i]!!)
            }
            mat.m.copyInto(f) // this seems backward but Wait!
            for(i in 0 until 16) mat.m[i] = f[i]
        } else {
            val translation = getArrayProp(node, "translation")
            if (translation != null) {
                mat.translate(
                    jsNumberToFloat(translation[0]!!),
                    jsNumberToFloat(translation[1]!!),
                    jsNumberToFloat(translation[2]!!)
                )
            }
            val rotation = getArrayProp(node, "rotation")
            if (rotation != null) {
                val qx = jsNumberToFloat(rotation[0]!!)
                val qy = jsNumberToFloat(rotation[1]!!)
                val qz = jsNumberToFloat(rotation[2]!!)
                val qw = jsNumberToFloat(rotation[3]!!)
                mat.rotate(qx, qy, qz, qw)
            }
            val scale = getArrayProp(node, "scale")
            if (scale != null) {
                mat.scale(
                    jsNumberToFloat(scale[0]!!),
                    jsNumberToFloat(scale[1]!!),
                    jsNumberToFloat(scale[2]!!)
                )
            }
        }
        return mat
    }

    private fun appendPrimitive(
        prim: JsAny, 
        transform: Matrix4f, 
        accessors: JsArray<JsAny>?, 
        bufferViews: JsArray<JsAny>?, 
        binData: ByteArray, 
        positions: ArrayList<Float>, 
        uvs: ArrayList<Float>, 
        indices: ArrayList<Int>
    ) {
        val mode = getIntProp(prim, "mode") ?: 4
        if (mode != 4) return // TRIANGLES only

        val attributes = getProp(prim, "attributes") ?: return
        val posIdx = getIntProp(attributes, "POSITION") ?: return
        val uvIdx = getIntProp(attributes, "TEXCOORD_0")

        val posAccessor = accessors!![posIdx]!!
        val posCount = getIntProp(posAccessor, "count") ?: 0
        val posBvIdx = getIntProp(posAccessor, "bufferView") ?: 0
        val posBv = bufferViews!![posBvIdx]!!
        val posOffset = (getIntProp(posBv, "byteOffset") ?: 0) + (getIntProp(posAccessor, "byteOffset") ?: 0)

        val uvBvIdx = uvIdx?.let { getIntProp(accessors[it]!!, "bufferView") }
        val uvOffset = uvIdx?.let {
            (getIntProp(bufferViews[uvBvIdx!!]!!, "byteOffset") ?: 0) + (getIntProp(accessors[it]!!, "byteOffset") ?: 0)
        }

        val baseVertex = positions.size / 3
        val v = Vector3f()

        // Read positions (Float32x3)
        for (i in 0 until posCount) {
            val ox = posOffset + i * 12
            val x = Float.fromBits(readIntLE(binData, ox))
            val y = Float.fromBits(readIntLE(binData, ox + 4))
            val z = Float.fromBits(readIntLE(binData, ox + 8))
            
            v.set(x, y, z)
            transform.transformPosition(v)
            positions.add(v.x); positions.add(v.y); positions.add(v.z)

            if (uvOffset != null) {
                val ou = uvOffset + i * 8
                val u = Float.fromBits(readIntLE(binData, ou))
                val vv = Float.fromBits(readIntLE(binData, ou + 4))
                uvs.add(u); uvs.add(vv)
            } else {
                uvs.add(0f); uvs.add(0f)
            }
        }

        val idxIdx = getIntProp(prim, "indices")
        if (idxIdx != null) {
            val idxAccessor = accessors[idxIdx]!!
            val idxCount = getIntProp(idxAccessor, "count") ?: 0
            val compType = getIntProp(idxAccessor, "componentType") ?: 5123
            val idxBvIdx = getIntProp(idxAccessor, "bufferView") ?: 0
            val idxBv = bufferViews[idxBvIdx]!!
            val idxOffset = (getIntProp(idxBv, "byteOffset") ?: 0) + (getIntProp(idxAccessor, "byteOffset") ?: 0)

            for (i in 0 until idxCount) {
                val idx = when (compType) {
                    5121 -> binData[idxOffset + i].toInt() and 0xFF
                    5123 -> readShortLE(binData, idxOffset + i * 2)
                    5125 -> readIntLE(binData, idxOffset + i * 4)
                    else -> 0
                }
                indices.add(baseVertex + idx)
            }
        } else {
            for (i in 0 until posCount) indices.add(baseVertex + i)
        }
    }

    private fun readShortLE(b: ByteArray, offset: Int): Int {
        return (b[offset].toInt() and 0xFF) or ((b[offset + 1].toInt() and 0xFF) shl 8)
    }

    private fun readIntLE(b: ByteArray, offset: Int): Int {
        return (b[offset].toInt() and 0xFF) or
               ((b[offset + 1].toInt() and 0xFF) shl 8) or
               ((b[offset + 2].toInt() and 0xFF) shl 16) or
               ((b[offset + 3].toInt() and 0xFF) shl 24)
    }

    private fun normalize(pos: FloatArray, uv: FloatArray, idx: IntArray, scale: Float): MeshData {
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
        return MeshData(out, computeSmoothNormals(out, idx), uvs, idx)
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
}
