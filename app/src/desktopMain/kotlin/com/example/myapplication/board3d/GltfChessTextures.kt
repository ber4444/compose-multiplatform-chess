package com.example.myapplication.board3d

import de.javagl.jgltf.model.io.GltfModelReader
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO

/** Decoded RGBA8 image (row-major, top-left origin) for upload as a Vulkan sampled image. */


/**
 * Decodes the embedded base-colour textures from `chess.glb` (by glTF image name) into RGBA8 bytes:
 * `board3` (marble checkerboard) for the board, `whites` / `blacks` (wood atlases) for the pieces.
 */
object GltfChessTextures {
    private val NAME_BY_TEXTURE = mapOf(
        ChessTexture.BOARD to "board3",
        ChessTexture.WHITE to "whites",
        ChessTexture.BLACK to "blacks",
    )

    fun load(glb: ByteArray): Map<ChessTexture, TextureImage> {
        val model = GltfModelReader().readWithoutReferences(ByteArrayInputStream(glb))
        val byName = model.imageModels.associateBy { it.name }
        val result = HashMap<ChessTexture, TextureImage>()
        for ((tex, name) in NAME_BY_TEXTURE) {
            val image = byName[name] ?: continue
            val data = image.imageData ?: continue
            val bytes = ByteArray(data.remaining()).also { data.duplicate().get(it) }
            val decoded = ImageIO.read(ByteArrayInputStream(bytes)) ?: continue
            result[tex] = toRgba(decoded)
        }
        return result
    }

    /** Decode a single embedded image by its glTF name (e.g. the frame's `marble-speckled-albedo`). */
    fun loadImage(glb: ByteArray, name: String): TextureImage? {
        val model = GltfModelReader().readWithoutReferences(ByteArrayInputStream(glb))
        val image = model.imageModels.firstOrNull { it.name == name } ?: return null
        val data = image.imageData ?: return null
        val bytes = ByteArray(data.remaining()).also { data.duplicate().get(it) }
        val decoded = ImageIO.read(ByteArrayInputStream(bytes)) ?: return null
        return toRgba(decoded)
    }

    private fun toRgba(img: java.awt.image.BufferedImage): TextureImage {
        val w = img.width; val h = img.height
        val rgba = ByteArray(w * h * 4)
        var i = 0
        for (y in 0 until h) {
            for (x in 0 until w) {
                val argb = img.getRGB(x, y)
                rgba[i] = ((argb shr 16) and 0xFF).toByte()      // R
                rgba[i + 1] = ((argb shr 8) and 0xFF).toByte()   // G
                rgba[i + 2] = (argb and 0xFF).toByte()           // B
                rgba[i + 3] = ((argb shr 24) and 0xFF).toByte()  // A
                i += 4
            }
        }
        return TextureImage(w, h, rgba)
    }
}
