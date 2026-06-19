package com.example.myapplication.board3d

import de.javagl.jgltf.model.io.GltfModelReader
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO

/** Decoded RGBA8 image (row-major, top-left origin) for upload as a Vulkan sampled image. */


/**
 * Full PBR material bundle for one [ChessTexture] group: the three glTF textures the renderer needs
 * (albedo, metallic-roughness, normal) plus the scalar factors that modulate them. Mirrors the
 * glTF material spec so per-pixel metallic/roughness come from the MR texture's B/G channels
 * scaled by [metallicFactor] / [roughnessFactor], and baseColor = baseColorFactor × albedo RGB.
 *
 * - [normal] is null for piece materials (whites/blacks) which have no tangent-space normal map;
 *   the caller binds a flat 1×1 default tangent-space normal (128,128,255) in that case.
 * - All chess.glb materials happen to set metallicFactor = roughnessFactor = 1.0; the per-pixel
 *   values come from the MR texture, so the factors are passed through verbatim.
 */
data class ChessMaterialSet(
    val albedo: TextureImage,
    val metallicRoughness: TextureImage,
    val normal: TextureImage?,
    val baseColorFactor: FloatArray,   // RGBA, defaults to [1,1,1,1]
    val metallicFactor: Float,         // defaults to 1.0
    val roughnessFactor: Float,        // defaults to 1.0
) {
    override fun equals(other: Any?): Boolean = this === other
    override fun hashCode(): Int = System.identityHashCode(this)
}

/**
 * Decodes the embedded base-colour textures from `chess.glb` (by glTF image name) into RGBA8 bytes:
 * `board3` (marble checkerboard) for the board, `whites` / `blacks` (wood atlases) for the pieces.
 */
object GltfChessTextures {
    private val texNames = mapOf(
        ChessTexture.BOARD to "board3",
        ChessTexture.WHITE to "whites",
        ChessTexture.BLACK to "blacks",
        ChessTexture.FRAME to "marble-speckled-albedo"
    )

    /**
     * Full material bundle per [ChessTexture]: looks up the glTF material that uses each group's
     * albedo image and pulls its MR/normal textures + baseColor/metallic/roughness factors from
     * there. Returns only groups whose albedo could be resolved.
     */
    fun loadMaterialSet(glb: ByteArray): Map<ChessTexture, ChessMaterialSet> {
        val model = GltfModelReader().readWithoutReferences(ByteArrayInputStream(glb))
        val imageByName = model.imageModels.associateBy { it.name }
        val result = HashMap<ChessTexture, ChessMaterialSet>()

        // Index materials by the glTF image their baseColorTexture points at, so we can find the
        // MR / normal textures + scalar factors that travel with each ChessTexture's albedo.
        // Cast to MaterialModelV2 because the MaterialModel interface is bare in jgltf-model v2.
        val materialByAlbedoImage = HashMap<String, de.javagl.jgltf.model.v2.MaterialModelV2>()
        for (material in model.materialModels) {
            if (material !is de.javagl.jgltf.model.v2.MaterialModelV2) continue
            val texModel = material.baseColorTexture ?: continue
            val imageModel = texModel.imageModel ?: continue
            val name = imageModel.name ?: continue
            materialByAlbedoImage[name] = material
        }

        for ((tex, albedoName) in texNames) {
            val albedoImage = imageByName[albedoName] ?: continue
            val albedo = decodeRgba(albedoImage.imageData ?: continue) ?: continue
            val material = materialByAlbedoImage[albedoName]

            val mrName = material?.metallicRoughnessTexture?.imageModel?.name
            val mr = mrName?.let { imageByName[it]?.imageData }?.let { decodeRgba(it) }
            val normalName = material?.normalTexture?.imageModel?.name
            val normal = normalName?.let { imageByName[it]?.imageData }?.let { decodeRgba(it) }

            val baseColorArr = material?.baseColorFactor ?: floatArrayOf(1f, 1f, 1f, 1f)
            val baseColor = floatArrayOf(
                baseColorArr.getOrElse(0) { 1f },
                baseColorArr.getOrElse(1) { 1f },
                baseColorArr.getOrElse(2) { 1f },
                baseColorArr.getOrElse(3) { 1f },
            )
            val metallic = material?.metallicFactor ?: 1f
            val rough = material?.roughnessFactor ?: 1f

            // The pieces' MR texture (chess.glb image "metallicRoughness") has its B channel pinned
            // at 255 everywhere, so glTF's metallic = factor × tex.b evaluates to 1.0 — making the
            // wood render as fully-metallic chrome that reflects the env instead of showing its
            // warm-wood diffuse albedo (avg RGB ≈ (109,92,67) for whites, (45,29,19) for blacks).
            // Wood is dielectric, so force metallic=0 for piece groups.
            val effectiveMetallic = if (tex == ChessTexture.WHITE || tex == ChessTexture.BLACK) 0f else metallic

            if (mr != null) {
                result[tex] = ChessMaterialSet(albedo, mr, normal, baseColor, effectiveMetallic, rough)
            }
        }
        return result
    }

    fun load(glb: ByteArray): Map<ChessTexture, TextureImage> {
        val model = GltfModelReader().readWithoutReferences(ByteArrayInputStream(glb))
        val byName = model.imageModels.associateBy { it.name }
        val result = HashMap<ChessTexture, TextureImage>()
        for ((tex, name) in texNames) {
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
        return decodeRgba(data)
    }

    private fun decodeRgba(imageData: java.nio.ByteBuffer): TextureImage? {
        val bytes = ByteArray(imageData.remaining()).also { imageData.duplicate().get(it) }
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
