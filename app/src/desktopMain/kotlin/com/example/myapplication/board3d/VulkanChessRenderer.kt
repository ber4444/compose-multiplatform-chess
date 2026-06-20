package com.example.myapplication.board3d

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.runBlocking
import org.joml.Matrix4f
import org.lwjgl.system.MemoryStack
import org.lwjgl.util.shaderc.Shaderc
import org.lwjgl.vulkan.*
import org.lwjgl.vulkan.KHRGetPhysicalDeviceProperties2.VK_KHR_GET_PHYSICAL_DEVICE_PROPERTIES_2_EXTENSION_NAME
import org.lwjgl.vulkan.KHRPortabilityEnumeration.VK_INSTANCE_CREATE_ENUMERATE_PORTABILITY_BIT_KHR
import org.lwjgl.vulkan.KHRPortabilityEnumeration.VK_KHR_PORTABILITY_ENUMERATION_EXTENSION_NAME
import org.lwjgl.vulkan.VK10.*
import java.nio.ByteBuffer

/**
 * Desktop 3D backend: a headless (no window/swapchain) Vulkan renderer that draws the textured
 * board + pieces into an offscreen image, copies it to host memory, and hands the RGBA pixels to the
 * attached [ImageBitmapChess3DSurface] as a Compose `ImageBitmap`. See overview Decision C/D.
 *
 * The scene is split by texture ([ChessSceneGeometry] groups: marble board, white wood, black wood)
 * and baked into world space; each group is one indexed draw binding its own sampler. Lighting is
 * per-pixel (diffuse + Blinn-Phong specular) in the fragment shader, using a fixed directional light
 * and the camera position (push constant). Geometry is rebuilt only when the FEN/selection changes;
 * camera moves only update the push constant. All Vulkan calls run on one dedicated thread.
 *
 * Any failure during init throws, so the factory returns null and the UI falls back to 2D.
 */
@OptIn(DelicateCoroutinesApi::class, ExperimentalCoroutinesApi::class)
class VulkanChessRenderer(glb: ByteArray) : Chess3DBoardRenderer {

    private val renderDispatcher = newSingleThreadContext("chess3d-render")
    private val renderScope = CoroutineScope(renderDispatcher + Job())

    private val meshes: Map<PieceKind, MeshData>
    /** Engraved stone frame (the glTF `frame`/Cube node); null if absent (no rim rendered). */
    private val frameMesh: MeshData?
    private val materialSets: Map<ChessTexture, ChessMaterialSet>

    private lateinit var instance: VkInstance
    private lateinit var physicalDevice: VkPhysicalDevice
    private lateinit var device: VkDevice
    private lateinit var queue: VkQueue
    private var queueFamily = 0
    private var commandPool = VK_NULL_HANDLE
    private lateinit var commandBuffer: VkCommandBuffer
    private var renderPass = VK_NULL_HANDLE
    private var descriptorSetLayout = VK_NULL_HANDLE
    private var descriptorPool = VK_NULL_HANDLE
    private var sampler = VK_NULL_HANDLE
    private var pipelineLayout = VK_NULL_HANDLE
    private var pipeline = VK_NULL_HANDLE
    private var skyPipelineLayout = VK_NULL_HANDLE
    private var skyPipeline = VK_NULL_HANDLE
    private var fence = VK_NULL_HANDLE

    // Shadow map (directional light depth pass)
    private val shadowSize = 2048
    private var shadowImage = VK_NULL_HANDLE; private var shadowMem = VK_NULL_HANDLE; private var shadowView = VK_NULL_HANDLE
    private var shadowSampler = VK_NULL_HANDLE
    private var shadowRenderPass = VK_NULL_HANDLE
    private var shadowFramebuffer = VK_NULL_HANDLE
    private var shadowPipelineLayout = VK_NULL_HANDLE
    private var shadowPipeline = VK_NULL_HANDLE
    private var uboBuffer = VK_NULL_HANDLE; private var uboMem = VK_NULL_HANDLE
    // UBOParams: light direction + tonemap exposure/gamma + prefiltered-cube mip count (32 bytes).
    private var uboParamsBuffer = VK_NULL_HANDLE; private var uboParamsMem = VK_NULL_HANDLE
    private val lightDir = org.joml.Vector3f(0.35f, 1.4f, -0.3f).normalize()  // higher sun, slightly behind the board
    // Look tunables — vkChess's exposure for papermill HDR is in this range; HIGH_QUALITY_WEBGPU uses 5.0.
    private val exposure = 4.3f   // Pass-2: was 4.0 — warmer, less flat (Part A.4)
    private val gamma = 2.2f
    // Part A look tunables (Pass-2 parity). These are baked as GLSL literals in FRAG_GLSL (constants,
    // not animated) — kept here as the documented source of the chosen numbers. Eyeball via
    // DesktopRendererSmokeTest (app/build/chess3d-*.png).
    private val iblSpecularScale = 0.85f   // was a hard-coded 0.5 in FRAG_GLSL; restores gloss + board reflections
    private val aoStrength = 1.0f          // how strongly mrTex.r (glTF occlusion) darkens the IBL ambient term
    private val contactStrength = 0.35f    // how much the shadow factor (sh) deepens ambient at piece/board contact

    private val colorFormat = VK_FORMAT_R8G8B8A8_UNORM
    private val depthFormat = VK_FORMAT_D32_SFLOAT
    private val envFormat = VK_FORMAT_R16G16B16A16_SFLOAT
    private val brdfLutFormat = VK_FORMAT_R16G16_SFLOAT
    // Part B: HDR scene resolve + bloom format. The scene pass now resolves into an RGBA16F target
    // (linear HDR), and a post chain (bright -> blur -> composite) tonemaps in the final pass.
    private val hdrFormat = VK_FORMAT_R16G16B16A16_SFLOAT

    // Part B bloom tunables (env-overridable). CHESS_DESKTOP_BLOOM=0 keeps the HDR composite path
    // but skips the bright/blur passes — i.e. reproduces the Part-A look (a one-line kill switch).
    private val bloomEnabled = System.getenv("CHESS_DESKTOP_BLOOM")?.trim() != "0"
    private val bloomThreshold = 1.1f   // HDR luma above which pixels start to bloom
    private val bloomKnee = 0.5f        // soft-knee width below the threshold
    private val bloomIntensity = 0.5f   // additive bloom strength in the composite pass
    private val bloomIterations = 2     // number of H+V separable blur passes

    private var width = 0
    private var height = 0
    private var samples = VK_SAMPLE_COUNT_1_BIT // MSAA sample count (chosen in initVulkan)
    private var colorImage = VK_NULL_HANDLE; private var colorMem = VK_NULL_HANDLE; private var colorView = VK_NULL_HANDLE // MSAA color
    private var depthImage = VK_NULL_HANDLE; private var depthMem = VK_NULL_HANDLE; private var depthView = VK_NULL_HANDLE // MSAA depth
    private var resolveImage = VK_NULL_HANDLE; private var resolveMem = VK_NULL_HANDLE; private var resolveView = VK_NULL_HANDLE // single-sample resolve (read back)
    // Part B post pipeline. sceneHdr = HDR resolve of the scene pass (sampled by bloom + composite).
    // resolveImage above is REPURPOSED as the composite (LDR) output that the readback copies — the
    // names are kept to minimize churn, but its usage/role changed (see ensureTargets).
    private var sceneHdrImage = VK_NULL_HANDLE; private var sceneHdrMem = VK_NULL_HANDLE; private var sceneHdrView = VK_NULL_HANDLE
    private var bloomW = 0; private var bloomH = 0
    private var bloomBright = VK_NULL_HANDLE; private var bloomBrightMem = VK_NULL_HANDLE; private var bloomBrightView = VK_NULL_HANDLE
    private var bloomA = VK_NULL_HANDLE; private var bloomAMem = VK_NULL_HANDLE; private var bloomAView = VK_NULL_HANDLE
    private var bloomB = VK_NULL_HANDLE; private var bloomBMem = VK_NULL_HANDLE; private var bloomBView = VK_NULL_HANDLE
    private var postRenderPass = VK_NULL_HANDLE       // 1 hdr attachment, shared by bright + blur passes
    private var compositeRenderPass = VK_NULL_HANDLE  // 1 ldr attachment (the read-back resolveImage)
    private var postSetLayout = VK_NULL_HANDLE; private var postPool = VK_NULL_HANDLE
    private var brightPipelineLayout = VK_NULL_HANDLE; private var brightPipeline = VK_NULL_HANDLE
    private var blurPipelineLayout = VK_NULL_HANDLE; private var blurPipeline = VK_NULL_HANDLE
    private var compositePipelineLayout = VK_NULL_HANDLE; private var compositePipeline = VK_NULL_HANDLE
    private var brightFb = VK_NULL_HANDLE; private var aFb = VK_NULL_HANDLE; private var bFb = VK_NULL_HANDLE; private var compositeFb = VK_NULL_HANDLE
    private var dsSceneHdr = VK_NULL_HANDLE; private var dsBright = VK_NULL_HANDLE
    private var dsA = VK_NULL_HANDLE; private var dsB = VK_NULL_HANDLE; private var dsComposite = VK_NULL_HANDLE
    // Two env cubes (matching Android's Filament asset split, for natural colours + blurred bg):
    //  - skybox: papermill_skybox.ktx (R11F_G11F_B10F, 1 mip, blurred) — drawn behind everything.
    //  - ibl:    papermill_ibl.ktx    (R11F_G11F_B10F, 5 mip chain, already prefiltered by cmgen) —
    //            used as the prefilteredMap AND as the convolution source for the irradiance cube.
    // Declared up here (not after `init`) so the property initializers run before the init block.
    private var skybox = CubeTexture(VK_NULL_HANDLE, VK_NULL_HANDLE, VK_NULL_HANDLE, 1)
    private var ibl = CubeTexture(VK_NULL_HANDLE, VK_NULL_HANDLE, VK_NULL_HANDLE, 1)
    private var iblMipLevels = 1
    // Precomputed IBL resources — built once at init.
    // The prefiltered env is the IBL cube itself (papermill_ibl.ktx comes prefiltered from Filament's
    // cmgen — see `ibl`). Only the diffuse irradiance cube is generated at runtime (convolution of
    // the IBL), plus the BRDF LUT (pure math).
    private var irradianceImage = VK_NULL_HANDLE; private var irradianceMem = VK_NULL_HANDLE; private var irradianceView = VK_NULL_HANDLE
    private var brdfLutImage = VK_NULL_HANDLE; private var brdfLutMem = VK_NULL_HANDLE; private var brdfLutView = VK_NULL_HANDLE
    // Cube sampler with proper mip range (used for env / irradiance / prefilteredMap bindings).
    private var cubeSampler = VK_NULL_HANDLE
    // 2D sampler for the BRDF LUT (CLAMP_TO_EDGE, no mip).
    private var brdfLutSampler = VK_NULL_HANDLE
    /** 1×1 flat tangent-space normal (128,128,255 ≈ +Z) — bound for materials with no normal map. */
    private var defaultNormalImage = VK_NULL_HANDLE; private var defaultNormalMem = VK_NULL_HANDLE; private var defaultNormalView = VK_NULL_HANDLE
    private var framebuffer = VK_NULL_HANDLE
    private var readbackBuffer = VK_NULL_HANDLE; private var readbackMem = VK_NULL_HANDLE; private var readbackSize = 0L

    private class Texture(
        var image: Long = VK_NULL_HANDLE, var mem: Long = VK_NULL_HANDLE, var view: Long = VK_NULL_HANDLE,
        var mrImage: Long = VK_NULL_HANDLE, var mrMem: Long = VK_NULL_HANDLE, var mrView: Long = VK_NULL_HANDLE,
        var normalView: Long = VK_NULL_HANDLE, // view bound at binding 9; points at the material's normal tex or [defaultNormalView]
        var descriptorSet: Long = VK_NULL_HANDLE,
    )
    private val textures = HashMap<ChessTexture, Texture>()

    private class GroupBuffers {
        var vBuf = VK_NULL_HANDLE; var vMem = VK_NULL_HANDLE; var vCap = 0L
        var tBuf = VK_NULL_HANDLE; var tMem = VK_NULL_HANDLE; var tCap = 0L  // tangent stream (binding 1)
        var iBuf = VK_NULL_HANDLE; var iMem = VK_NULL_HANDLE; var iCap = 0L
        var indexCount = 0
    }
    private val groupBuffers = HashMap<ChessTexture, GroupBuffers>()

    private val hostVisible = VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT or VK_MEMORY_PROPERTY_HOST_COHERENT_BIT

    private var surface: ImageBitmapChess3DSurface? = null
    private var pendingFen: String? = null
    private var camera: CameraParams = OrbitCameraController.DEFAULT_WHITE_VIEW
    private var disposed = false

    init {
        meshes = GltfChessMeshes.load(glb)
        require(meshes.isNotEmpty()) { "No chess piece meshes found in glb" }
        frameMesh = GltfChessMeshes.loadFrame(glb)
        materialSets = GltfChessTextures.loadMaterialSet(glb)
        require(materialSets.isNotEmpty()) { "No chess material sets found in glb" }
        runBlocking(renderDispatcher) {
            initVulkan()
        }
    }

    // Move arc + selection bounce, frame-paced. The driver runs on renderScope (the single render
    // thread), so every render() call lands on the thread Vulkan must be driven from. render()
    // blocks on the GPU fence (≈10ms), and the driver subtracts that from the 16ms frame budget so
    // pacing targets ~60fps instead of stacking a flat delay on top of the render.
    private val driver = Board3DAnimationDriver(renderScope, FRAME_BUDGET_MS) { scene ->
        if (surface != null) {
            val geo = ChessSceneGeometry.build(scene, meshes, frameMesh, includeGround = false)
            for ((tex, group) in geo.groups) uploadGroup(tex, group)
            renderFrame()
        }
    }

    override fun attach(surface: Chess3DSurface) {
        if (surface !is ImageBitmapChess3DSurface) return
        post {
            this.surface = surface
            ensureTargets(surface.widthPx.coerceAtLeast(1), surface.heightPx.coerceAtLeast(1))
            camera = camera.copy(aspect = width.toFloat() / height.toFloat())
            // Seed the driver's resting scene and draw the initial position.
            driver.setPosition(Board3DSceneMapper.fromFen(pendingFen ?: FenStart), null)
        }
    }

    override fun detach() { post { surface = null } }

    override fun updatePosition(fen: String) = updatePosition(fen, null)

    override fun updatePosition(fen: String, transition: Board3DTransition?) {
        pendingFen = fen
        post {
            if (surface != null) {
                driver.setPosition(Board3DSceneMapper.fromFen(fen), transition)
            }
        }
    }

    override fun setSelectedSquare(square: BoardSquare?) {
        post { driver.setSelected(square) }
    }

    override fun onUserInteraction(event: Board3DInput) {
        when (event) {
            is Board3DInput.SetCamera -> post { camera = event.camera; renderFrame() }
            is Board3DInput.Resize -> post {
                if (surface != null && (event.widthPx != width || event.heightPx != height)) {
                    ensureTargets(event.widthPx.coerceAtLeast(1), event.heightPx.coerceAtLeast(1))
                    camera = camera.copy(aspect = width.toFloat() / height.toFloat())
                    renderFrame()
                }
            }
            else -> {}
        }
    }

    override fun dispose() {
        if (disposed) return
        disposed = true
        runCatching { runBlocking(renderDispatcher) { destroyVulkan() } }
        renderScope.cancel()
        renderDispatcher.close()
    }

    private fun post(block: () -> Unit) {
        if (disposed) return
        renderScope.launch {
            runCatching { block() }.onFailure { co.touchlab.kermit.Logger.w(it) { "chess3d render step failed" } }
        }
    }

    private fun renderFrame() {
        val surf = surface ?: return
        if (width == 0 || height == 0) return
        if (groupBuffers.values.all { it.indexCount == 0 }) return
        MemoryStack.stackPush().use { stack ->
            vkResetFences(device, stack.longs(fence))
            vkResetCommandBuffer(commandBuffer, 0)
            recordCommandBuffer(stack)
            val submit = VkSubmitInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_SUBMIT_INFO).pCommandBuffers(stack.pointers(commandBuffer))
            check(vkQueueSubmit(queue, submit, fence) == VK_SUCCESS) { "vkQueueSubmit failed" }
            vkWaitForFences(device, stack.longs(fence), true, Long.MAX_VALUE)
        }
        surf.onFrame(readbackToImageBitmap())
    }

    private fun recordCommandBuffer(stack: MemoryStack) {
        val lightVP = lightViewProj()
        updateUbo(stack, lightVP)

        val begin = VkCommandBufferBeginInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO)
        check(vkBeginCommandBuffer(commandBuffer, begin) == VK_SUCCESS)

        // --- Shadow pass: render scene depth from the light's POV into the shadow map ---
        run {
            val clearShadow = VkClearValue.calloc(1, stack)
            clearShadow[0].depthStencil().set(1f, 0)
            val shadowArea = VkRect2D.calloc(stack)
            shadowArea.offset().set(0, 0); shadowArea.extent().set(shadowSize, shadowSize)
            val begin2 = VkRenderPassBeginInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO).renderPass(shadowRenderPass).framebuffer(shadowFramebuffer).renderArea(shadowArea).pClearValues(clearShadow)
            vkCmdBeginRenderPass(commandBuffer, begin2, VK_SUBPASS_CONTENTS_INLINE)
            vkCmdBindPipeline(commandBuffer, VK_PIPELINE_BIND_POINT_GRAPHICS, shadowPipeline)
            val vp = VkViewport.calloc(1, stack).x(0f).y(0f).width(shadowSize.toFloat()).height(shadowSize.toFloat()).minDepth(0f).maxDepth(1f)
            vkCmdSetViewport(commandBuffer, 0, vp)
            val sc = VkRect2D.calloc(1, stack); sc.get(0).offset().set(0, 0); sc.get(0).extent().set(shadowSize, shadowSize)
            vkCmdSetScissor(commandBuffer, 0, sc)
            val push = stack.malloc(64); lightVP.get(0, push)
            vkCmdPushConstants(commandBuffer, shadowPipelineLayout, VK_SHADER_STAGE_VERTEX_BIT, 0, push)
            for (tex in ChessTexture.entries) {
                val g = groupBuffers[tex] ?: continue
                if (g.indexCount == 0) continue
                // Shadow pass doesn't read tangents — only bind binding 0.
                vkCmdBindVertexBuffers(commandBuffer, 0, stack.longs(g.vBuf), stack.longs(0))
                vkCmdBindIndexBuffer(commandBuffer, g.iBuf, 0, VK_INDEX_TYPE_UINT32)
                vkCmdDrawIndexed(commandBuffer, g.indexCount, 1, 0, 0, 0)
            }
            vkCmdEndRenderPass(commandBuffer)
        }

        // --- Main pass ---
        val clear = VkClearValue.calloc(2, stack)
        clear[0].color().float32(0, 0.10f).float32(1, 0.11f).float32(2, 0.13f).float32(3, 1f)
        clear[1].depthStencil().set(1f, 0)
        val area = VkRect2D.calloc(stack)
        area.offset().set(0, 0); area.extent().set(width, height)
        val rpBegin = VkRenderPassBeginInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO).renderPass(renderPass).framebuffer(framebuffer).renderArea(area).pClearValues(clear)
        vkCmdBeginRenderPass(commandBuffer, rpBegin, VK_SUBPASS_CONTENTS_INLINE)

        val viewport = VkViewport.calloc(1, stack).x(0f).y(0f).width(width.toFloat()).height(height.toFloat()).minDepth(0f).maxDepth(1f)
        vkCmdSetViewport(commandBuffer, 0, viewport)
        val scissor = VkRect2D.calloc(1, stack)
        scissor.get(0).offset().set(0, 0); scissor.get(0).extent().set(width, height)
        vkCmdSetScissor(commandBuffer, 0, scissor)

        vkCmdBindPipeline(commandBuffer, VK_PIPELINE_BIND_POINT_GRAPHICS, skyPipeline)
        textures.values.firstOrNull()?.descriptorSet?.let { ds ->
            vkCmdBindDescriptorSets(commandBuffer, VK_PIPELINE_BIND_POINT_GRAPHICS, skyPipelineLayout, 0, stack.longs(ds), null)
        }
        vkCmdDraw(commandBuffer, 3, 1, 0, 0)

        vkCmdBindPipeline(commandBuffer, VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline)
        for (tex in ChessTexture.entries) {
            val g = groupBuffers[tex] ?: continue
            if (g.indexCount == 0) continue
            val matSet = materialSets[tex] ?: continue
            val ds = textures[tex]?.descriptorSet ?: continue
            // Push per-material scalar factors (baseColorFactor + metallicFactor + roughnessFactor)
            // that modulate the albedo and MR textures in the fragment shader.
            val isPiece = tex == ChessTexture.WHITE || tex == ChessTexture.BLACK
            // De-band: the piece albedo + MR textures bake very high-contrast horizontal wood grain,
            // so on the lathe-turned geometry the pieces read as harsh rings. For pieces, soften the
            // albedo grain toward the per-material mean wood colour and flatten roughness so the
            // surface has an even sheen instead of alternating glossy/matte stripes. Board keeps its
            // textured albedo/roughness. Mean wood colours measured from the glb whites/blacks albedo.
            val meanR: Float; val meanG: Float; val meanB: Float
            when (tex) {
                ChessTexture.WHITE -> { meanR = 0.427f; meanG = 0.361f; meanB = 0.263f }
                ChessTexture.BLACK -> { meanR = 0.176f; meanG = 0.114f; meanB = 0.075f }
                else -> { meanR = 1f; meanG = 1f; meanB = 1f }
            }
            val grainStrength = if (isPiece) 0.5f else 1f       // keep 50% of the grain on pieces
            val roughnessOverride = if (isPiece) 0.4f else 0f   // >0 = flat roughness; 0 = use texture
            // Part A.2: per-material roughness scale at offset 24. Pieces glossier; board stays 1.0.
            val roughScale = if (isPiece) 0.8f else 1.0f
            val matPush = stack.malloc(48)
            matPush.putFloat(0, matSet.baseColorFactor.getOrElse(0) { 1f })
            matPush.putFloat(4, matSet.baseColorFactor.getOrElse(1) { 1f })
            matPush.putFloat(8, matSet.baseColorFactor.getOrElse(2) { 1f })
            matPush.putFloat(12, matSet.baseColorFactor.getOrElse(3) { 1f })
            matPush.putFloat(16, matSet.metallicFactor)
            matPush.putFloat(20, matSet.roughnessFactor)
            matPush.putFloat(24, roughScale)
            matPush.putFloat(28, grainStrength)
            matPush.putFloat(32, meanR); matPush.putFloat(36, meanG); matPush.putFloat(40, meanB)
            matPush.putFloat(44, roughnessOverride)
            vkCmdPushConstants(commandBuffer, pipelineLayout, VK_SHADER_STAGE_FRAGMENT_BIT, 0, matPush)
            vkCmdBindDescriptorSets(commandBuffer, VK_PIPELINE_BIND_POINT_GRAPHICS, pipelineLayout, 0, stack.longs(ds), null)
            // Bind both vertex buffers (binding 0 = pos/normal/uv/tint, binding 1 = tangents).
            // When a group has no tangent stream (empty array), bind vBuf twice so binding 1 reads
            // harmless garbage rather than tripping validation on a missing binding.
            val tanBuf = if (g.tBuf != VK_NULL_HANDLE) g.tBuf else g.vBuf
            vkCmdBindVertexBuffers(commandBuffer, 0, stack.longs(g.vBuf, tanBuf), stack.longs(0, 0))
            vkCmdBindIndexBuffer(commandBuffer, g.iBuf, 0, VK_INDEX_TYPE_UINT32)
            vkCmdDrawIndexed(commandBuffer, g.indexCount, 1, 0, 0, 0)
        }

        vkCmdEndRenderPass(commandBuffer)

        // --- Part B post chain (bright -> blur×N -> composite) tonemaps + writes the LDR result into
        // resolveImage, which the trailing copy then reads back. With CHESS_DESKTOP_BLOOM=0 it's a
        // composite-only pass (intensity 0) that reproduces the Part-A look over the HDR pipeline. ---
        if (bloomEnabled) {
            recordPostPass(stack, postRenderPass, brightFb, bloomW, bloomH, brightPipeline, brightPipelineLayout, dsSceneHdr,
                floatArrayOf(bloomThreshold, bloomKnee, 0f, 0f))
            for (i in 0 until bloomIterations) {
                val blurSrc = if (i == 0) dsBright else dsB
                val texelX = 1f / bloomW; val texelY = 1f / bloomH
                recordPostPass(stack, postRenderPass, aFb, bloomW, bloomH, blurPipeline, blurPipelineLayout, blurSrc,
                    floatArrayOf(texelX, texelY, 1f, 0f))
                recordPostPass(stack, postRenderPass, bFb, bloomW, bloomH, blurPipeline, blurPipelineLayout, dsA,
                    floatArrayOf(texelX, texelY, 0f, 1f))
            }
            recordPostPass(stack, compositeRenderPass, compositeFb, width, height, compositePipeline, compositePipelineLayout, dsComposite,
                floatArrayOf(bloomIntensity, exposure, gamma, 0f))
        } else {
            recordPostPass(stack, compositeRenderPass, compositeFb, width, height, compositePipeline, compositePipelineLayout, dsComposite,
                floatArrayOf(0f, exposure, gamma, 0f))
        }

        val region = VkBufferImageCopy.calloc(1, stack)
        region.bufferOffset(0).bufferRowLength(0).bufferImageHeight(0)
        region.imageSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1)
        region.imageOffset().set(0, 0, 0); region.imageExtent().set(width, height, 1)
        vkCmdCopyImageToBuffer(commandBuffer, resolveImage, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, readbackBuffer, region)

        check(vkEndCommandBuffer(commandBuffer) == VK_SUCCESS)
    }

    /** Records one fullscreen-triangle post pass (begin render pass, viewport/scissor, bind pipeline +
     *  descriptor set, push the 4-float fragment constant, draw 3 verts, end). All post attachments
     *  use loadOp DONT_CARE so no clear values are passed. */
    private fun recordPostPass(stack: MemoryStack, rp: Long, fb: Long, w: Int, h: Int, pipe: Long, layout: Long, ds: Long, push: FloatArray) {
        val area = VkRect2D.calloc(stack)
        area.offset().set(0, 0); area.extent().set(w, h)
        val begin = VkRenderPassBeginInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO).renderPass(rp).framebuffer(fb).renderArea(area)
        vkCmdBeginRenderPass(commandBuffer, begin, VK_SUBPASS_CONTENTS_INLINE)
        val vp = VkViewport.calloc(1, stack).x(0f).y(0f).width(w.toFloat()).height(h.toFloat()).minDepth(0f).maxDepth(1f)
        vkCmdSetViewport(commandBuffer, 0, vp)
        val sc = VkRect2D.calloc(1, stack); sc.get(0).offset().set(0, 0); sc.get(0).extent().set(w, h)
        vkCmdSetScissor(commandBuffer, 0, sc)
        vkCmdBindPipeline(commandBuffer, VK_PIPELINE_BIND_POINT_GRAPHICS, pipe)
        vkCmdBindDescriptorSets(commandBuffer, VK_PIPELINE_BIND_POINT_GRAPHICS, layout, 0, stack.longs(ds), null)
        val pc = stack.malloc(16)
        pc.putFloat(0, push[0]).putFloat(4, push[1]).putFloat(8, push[2]).putFloat(12, push[3])
        vkCmdPushConstants(commandBuffer, layout, VK_SHADER_STAGE_FRAGMENT_BIT, 0, pc)
        vkCmdDraw(commandBuffer, 3, 1, 0, 0)
        vkCmdEndRenderPass(commandBuffer)
    }

    private fun updateUbo(stack: MemoryStack, lightVP: Matrix4f) {
        val pp = stack.mallocPointer(1)
        vkMapMemory(device, uboMem, 0, 208, 0, pp)
        val buf = pp.getByteBuffer(0, 208)
        val vp = viewProjMatrix()
        vp.get(0, buf)
        lightVP.get(64, buf)
        buf.putFloat(128, camera.position.x).putFloat(132, camera.position.y).putFloat(136, camera.position.z).putFloat(140, 1f)
        vp.invert(Matrix4f()).get(144, buf)
        vkUnmapMemory(device, uboMem)
    }

    private fun viewProjMatrix(): Matrix4f {
        val proj = Matrix4f().perspective(
            Math.toRadians(camera.fovYDegrees.toDouble()).toFloat(),
            (width.toFloat() / height.toFloat()).coerceAtLeast(0.01f),
            camera.near, camera.far, true,
        )
        proj.m11(proj.m11() * -1f)
        val view = Matrix4f().lookAt(
            camera.position.x, camera.position.y, camera.position.z,
            camera.target.x, camera.target.y, camera.target.z,
            camera.up.x, camera.up.y, camera.up.z,
        )
        return proj.mul(view)
    }

    private fun readbackToImageBitmap() = MemoryStack.stackPush().use { stack ->
        val pp = stack.mallocPointer(1)
        vkMapMemory(device, readbackMem, 0, readbackSize, 0, pp)
        val buf = pp.getByteBuffer(0, width * height * 4)
        val bytes = ByteArray(width * height * 4)
        buf.get(bytes)
        vkUnmapMemory(device, readbackMem)
        rgbaBytesToImageBitmap(bytes, width, height)
    }

    // --- Vulkan setup ---

    private fun initVulkan() {
        MemoryStack.stackPush().use { stack ->
            val portability = hasInstanceExtension(stack, VK_KHR_PORTABILITY_ENUMERATION_EXTENSION_NAME)
            val appInfo = VkApplicationInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_APPLICATION_INFO)
                .pApplicationName(stack.UTF8("chess3d")).apiVersion(VK_API_VERSION_1_0)
            val exts = mutableListOf<String>()
            if (portability) { exts += VK_KHR_PORTABILITY_ENUMERATION_EXTENSION_NAME; exts += VK_KHR_GET_PHYSICAL_DEVICE_PROPERTIES_2_EXTENSION_NAME }
            val pExts = if (exts.isEmpty()) null else stack.pointers(*exts.map { stack.UTF8(it) }.toTypedArray())
            val ici = VkInstanceCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO).pApplicationInfo(appInfo).ppEnabledExtensionNames(pExts)
            if (portability) ici.flags(VK_INSTANCE_CREATE_ENUMERATE_PORTABILITY_BIT_KHR)
            val pInstance = stack.mallocPointer(1)
            check(vkCreateInstance(ici, null, pInstance) == VK_SUCCESS) { "vkCreateInstance failed" }
            instance = VkInstance(pInstance.get(0), ici)

            val count = stack.mallocInt(1)
            vkEnumeratePhysicalDevices(instance, count, null)
            check(count.get(0) > 0) { "no Vulkan physical devices" }
            val devices = stack.mallocPointer(count.get(0))
            vkEnumeratePhysicalDevices(instance, count, devices)
            physicalDevice = VkPhysicalDevice(devices.get(0), instance)
            samples = getMaxUsableSampleCount(stack)
            // Part B.0: the scene color target is now HDR (RGBA16F) AND multisampled AND sampled-from
            // (after resolve). Clamp `samples` to whatever the device allows for hdrFormat as a
            // multisampled COLOR_ATTACHMENT — MoltenVK supports 4×/8× RGBA16F, this guards weird drivers.
            samples = samples and hdrColorSampleCounts(stack)
            queueFamily = findGraphicsQueueFamily(stack)

            val devExts = mutableListOf<String>()
            if (hasDeviceExtension(stack, "VK_KHR_portability_subset")) devExts += "VK_KHR_portability_subset"
            val pDevExts = if (devExts.isEmpty()) null else stack.pointers(*devExts.map { stack.UTF8(it) }.toTypedArray())
            val queueInfo = VkDeviceQueueCreateInfo.calloc(1, stack).sType(VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO).queueFamilyIndex(queueFamily).pQueuePriorities(stack.floats(1f))
            // Enable samplerAnisotropy so the albedo/MR samplers can do up to 16× anisotropic
            // filtering (essential for the wood atlas textures, which are 512² and otherwise alias
            // badly at grazing angles). Falls back to no-anisotropy if the device lacks the feature.
            val supportedFeatures = VkPhysicalDeviceFeatures.calloc(stack)
            vkGetPhysicalDeviceFeatures(physicalDevice, supportedFeatures)
            val enabledFeatures = VkPhysicalDeviceFeatures.calloc(stack)
            if (supportedFeatures.samplerAnisotropy()) {
                enabledFeatures.samplerAnisotropy(true)
            }
            val dci = VkDeviceCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO).pQueueCreateInfos(queueInfo).ppEnabledExtensionNames(pDevExts).pEnabledFeatures(enabledFeatures)
            val pDevice = stack.mallocPointer(1)
            check(vkCreateDevice(physicalDevice, dci, null, pDevice) == VK_SUCCESS) { "vkCreateDevice failed" }
            device = VkDevice(pDevice.get(0), physicalDevice, dci)
            val pQueue = stack.mallocPointer(1)
            vkGetDeviceQueue(device, queueFamily, 0, pQueue)
            queue = VkQueue(pQueue.get(0), device)

            val poolInfo = VkCommandPoolCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO).queueFamilyIndex(queueFamily).flags(VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT)
            val pPool = stack.mallocLong(1)
            check(vkCreateCommandPool(device, poolInfo, null, pPool) == VK_SUCCESS); commandPool = pPool.get(0)

            val cbAlloc = VkCommandBufferAllocateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO).commandPool(commandPool).level(VK_COMMAND_BUFFER_LEVEL_PRIMARY).commandBufferCount(1)
            val pCb = stack.mallocPointer(1)
            check(vkAllocateCommandBuffers(device, cbAlloc, pCb) == VK_SUCCESS); commandBuffer = VkCommandBuffer(pCb.get(0), device)

            val fenceInfo = VkFenceCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_FENCE_CREATE_INFO)
            val pFence = stack.mallocLong(1)
            check(vkCreateFence(device, fenceInfo, null, pFence) == VK_SUCCESS); fence = pFence.get(0)

            createRenderPass(stack)
            createSampler(stack)
            createDescriptorLayoutAndPool(stack)
            createShadowResources(stack)
            createUboParamsBuffer(stack)
            createPipeline(stack)
            createSkyPipeline(stack)
            createPostPipeline(stack)
        }
        // Load the papermill env cubes Android uses (skybox = blurred background, ibl = prefiltered
        // mip chain). Both fall back gracefully to "no env" if the asset is missing — the renderer
        // will still draw pieces, just without IBL.
        loadEnvCube("/papermill_skybox.ktx") { cube -> skybox = cube }
        loadEnvCube("/papermill_ibl.ktx") { cube ->
            ibl = cube
            iblMipLevels = cube.mipLevels
        }
        // BRDF LUT is independent of the env; irradiance cube convolves the IBL, so order matters.
        buildBrdfLut()
        buildIrradianceCube()
        // UBOParams depends on iblMipLevels (set by loadEnvCube for the IBL).
        writeUboParams()
        // Now that UBO / UBOParams / skybox / ibl / irradiance / brdfLut all exist, allocate
        // per-material descriptor sets and wire all 9 bindings into each.
        uploadAllTextures()
    }

    private data class CubeTexture(var image: Long, var mem: Long, var view: Long, var mipLevels: Int)

    /** Loads a KTX env cube from the JVM resources and passes the result to [assign]. */
    private fun loadEnvCube(resourcePath: String, assign: (CubeTexture) -> Unit) {
        val bytes = runCatching { this::class.java.getResourceAsStream(resourcePath)?.readAllBytes() }.getOrNull() ?: return
        val ktx = KtxLoader.load(bytes) ?: return
        val vulkanFormat = when (ktx.glInternalFormat) {
            0x881A -> VK_FORMAT_R16G16B16A16_SFLOAT          // GL_RGBA16F
            0x8C3A -> VK_FORMAT_B10G11R11_UFLOAT_PACK32      // GL_R11F_G11F_B10F
            0x881B -> VK_FORMAT_R16G16B16_SFLOAT             // GL_RGB16F (no alpha)
            else -> VK_FORMAT_R16G16B16A16_SFLOAT            // assume HDR RGBA as a safe default
        }
        val cube = uploadKtxCube(ktx, vulkanFormat)
        ktx.free()
        assign(cube)
    }

    private fun createShadowResources(stack: MemoryStack) {
        val (ubo, uboM) = createBuffer(208L, VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT, hostVisible)
        uboBuffer = ubo; uboMem = uboM

        val (img, mem) = createImage(shadowSize, shadowSize, depthFormat, VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT or VK_IMAGE_USAGE_SAMPLED_BIT)
        shadowImage = img; shadowMem = mem
        shadowView = createImageView(shadowImage, depthFormat, VK_IMAGE_ASPECT_DEPTH_BIT)

        val samplerInfo = VkSamplerCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO)
            .magFilter(VK_FILTER_LINEAR).minFilter(VK_FILTER_LINEAR)
            .addressModeU(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_BORDER).addressModeV(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_BORDER).addressModeW(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_BORDER)
            .borderColor(VK_BORDER_COLOR_FLOAT_OPAQUE_WHITE).mipmapMode(VK_SAMPLER_MIPMAP_MODE_NEAREST).maxLod(0f).minLod(0f)
        val pSampler = stack.mallocLong(1)
        check(vkCreateSampler(device, samplerInfo, null, pSampler) == VK_SUCCESS); shadowSampler = pSampler.get(0)

        // Depth-only render pass.
        val attachment = VkAttachmentDescription.calloc(1, stack)
        attachment[0].format(depthFormat).samples(VK_SAMPLE_COUNT_1_BIT)
            .loadOp(VK_ATTACHMENT_LOAD_OP_CLEAR).storeOp(VK_ATTACHMENT_STORE_OP_STORE)
            .stencilLoadOp(VK_ATTACHMENT_LOAD_OP_DONT_CARE).stencilStoreOp(VK_ATTACHMENT_STORE_OP_DONT_CARE)
            .initialLayout(VK_IMAGE_LAYOUT_UNDEFINED).finalLayout(VK_IMAGE_LAYOUT_DEPTH_STENCIL_READ_ONLY_OPTIMAL)
        val depthRef = VkAttachmentReference.calloc(stack).attachment(0).layout(VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL)
        val subpass = VkSubpassDescription.calloc(1, stack).pipelineBindPoint(VK_PIPELINE_BIND_POINT_GRAPHICS).colorAttachmentCount(0).pDepthStencilAttachment(depthRef)
        val rpInfo = VkRenderPassCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_RENDER_PASS_CREATE_INFO).pAttachments(attachment).pSubpasses(subpass)
        val pRp = stack.mallocLong(1)
        check(vkCreateRenderPass(device, rpInfo, null, pRp) == VK_SUCCESS); shadowRenderPass = pRp.get(0)

        val fbInfo = VkFramebufferCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO).renderPass(shadowRenderPass).pAttachments(stack.longs(shadowView)).width(shadowSize).height(shadowSize).layers(1)
        val pFb = stack.mallocLong(1)
        check(vkCreateFramebuffer(device, fbInfo, null, pFb) == VK_SUCCESS); shadowFramebuffer = pFb.get(0)

        createShadowPipeline(stack)
    }

    /** Allocates the 32-byte UBOParams buffer; contents are written by [writeUboParams]. */
    private fun createUboParamsBuffer(stack: MemoryStack) {
        val (buf, mem) = createBuffer(32L, VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT, hostVisible)
        uboParamsBuffer = buf; uboParamsMem = mem
    }

    /**
     * Writes the static UBOParams: light direction (matching [lightDir]) + tonemap exposure/gamma +
     * the IBL cube's mip count (used by the fragment shader's prefilteredReflection lod math).
     * Must be called after [loadEnvCube] has populated [iblMipLevels].
     */
    private fun writeUboParams() = MemoryStack.stackPush().use { stack ->
        val pp = stack.mallocPointer(1)
        vkMapMemory(device, uboParamsMem, 0, 32L, 0, pp)
        val buf = pp.getByteBuffer(0, 32)
        buf.putFloat(0, lightDir.x).putFloat(4, lightDir.y).putFloat(8, lightDir.z).putFloat(12, 0f)
        buf.putFloat(16, exposure).putFloat(20, gamma).putFloat(24, (iblMipLevels - 1).toFloat()).putFloat(28, 0f)
        vkUnmapMemory(device, uboParamsMem)
    }

    /**
     * Option-B IBL precompute #1: the 2D BRDF integration LUT (512² RG16F).
     * Renders a single fullscreen triangle through [BRDF_LUT_FRAG] / [FSQ_VERT] into an offscreen
     * RG16F target. Pure math (no env sampling), so order-independent.
     */
    private fun buildBrdfLut() {
        val size = 512
        val (img, mem) = createImage(size, size, brdfLutFormat,
            VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT or VK_IMAGE_USAGE_SAMPLED_BIT)
        brdfLutImage = img; brdfLutMem = mem
        brdfLutView = createImageView(brdfLutImage, brdfLutFormat, VK_IMAGE_ASPECT_COLOR_BIT)

        MemoryStack.stackPush().use { stack ->
            // Offscreen color-only render pass for the LUT.
            val attachment = VkAttachmentDescription.calloc(1, stack)
            attachment[0].format(brdfLutFormat).samples(VK_SAMPLE_COUNT_1_BIT)
                .loadOp(VK_ATTACHMENT_LOAD_OP_CLEAR).storeOp(VK_ATTACHMENT_STORE_OP_STORE)
                .stencilLoadOp(VK_ATTACHMENT_LOAD_OP_DONT_CARE).stencilStoreOp(VK_ATTACHMENT_STORE_OP_DONT_CARE)
                .initialLayout(VK_IMAGE_LAYOUT_UNDEFINED).finalLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL)
            val colorRef = VkAttachmentReference.calloc(1, stack).attachment(0).layout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL)
            val subpass = VkSubpassDescription.calloc(1, stack).pipelineBindPoint(VK_PIPELINE_BIND_POINT_GRAPHICS).colorAttachmentCount(1).pColorAttachments(colorRef)
            val rpInfo = VkRenderPassCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_RENDER_PASS_CREATE_INFO).pAttachments(attachment).pSubpasses(subpass)
            val pRp = stack.mallocLong(1)
            check(vkCreateRenderPass(device, rpInfo, null, pRp) == VK_SUCCESS); val brdfRp = pRp.get(0)

            val fbInfo = VkFramebufferCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO).renderPass(brdfRp).pAttachments(stack.longs(brdfLutView)).width(size).height(size).layers(1)
            val pFb = stack.mallocLong(1)
            check(vkCreateFramebuffer(device, fbInfo, null, pFb) == VK_SUCCESS); val brdfFb = pFb.get(0)

            val vert = createShaderModule(stack, FSQ_VERT, Shaderc.shaderc_glsl_vertex_shader, "brdfLutVert")
            val frag = createShaderModule(stack, BRDF_LUT_FRAG, Shaderc.shaderc_glsl_fragment_shader, "brdfLutFrag")
            val stages = VkPipelineShaderStageCreateInfo.calloc(2, stack)
            stages[0].sType(VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO).stage(VK_SHADER_STAGE_VERTEX_BIT).module(vert).pName(stack.UTF8("main"))
            stages[1].sType(VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO).stage(VK_SHADER_STAGE_FRAGMENT_BIT).module(frag).pName(stack.UTF8("main"))
            // No vertex buffer — fullscreen triangle is generated from gl_VertexIndex in the shader.
            val vertexInput = VkPipelineVertexInputStateCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO)
            val inputAssembly = VkPipelineInputAssemblyStateCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO).topology(VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST)
            val viewportState = VkPipelineViewportStateCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO).viewportCount(1).scissorCount(1)
            val raster = VkPipelineRasterizationStateCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO).polygonMode(VK_POLYGON_MODE_FILL).cullMode(VK_CULL_MODE_NONE).frontFace(VK_FRONT_FACE_COUNTER_CLOCKWISE).lineWidth(1f)
            val multisample = VkPipelineMultisampleStateCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO).rasterizationSamples(VK_SAMPLE_COUNT_1_BIT)
            val blendAttachment = VkPipelineColorBlendAttachmentState.calloc(1, stack).colorWriteMask(VK_COLOR_COMPONENT_R_BIT or VK_COLOR_COMPONENT_G_BIT or VK_COLOR_COMPONENT_B_BIT or VK_COLOR_COMPONENT_A_BIT).blendEnable(false)
            val blend = VkPipelineColorBlendStateCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO).pAttachments(blendAttachment)
            val dynamic = VkPipelineDynamicStateCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_PIPELINE_DYNAMIC_STATE_CREATE_INFO).pDynamicStates(stack.ints(VK_DYNAMIC_STATE_VIEWPORT, VK_DYNAMIC_STATE_SCISSOR))
            val layoutInfo = VkPipelineLayoutCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO)
            val pLayout = stack.mallocLong(1)
            check(vkCreatePipelineLayout(device, layoutInfo, null, pLayout) == VK_SUCCESS); val brdfLayout = pLayout.get(0)
            val pipelineInfo = VkGraphicsPipelineCreateInfo.calloc(1, stack).sType(VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO)
                .pStages(stages).pVertexInputState(vertexInput).pInputAssemblyState(inputAssembly).pViewportState(viewportState)
                .pRasterizationState(raster).pMultisampleState(multisample).pColorBlendState(blend).pDynamicState(dynamic)
                .layout(brdfLayout).renderPass(brdfRp).subpass(0)
            val pPipeline = stack.mallocLong(1)
            check(vkCreateGraphicsPipelines(device, VK_NULL_HANDLE, pipelineInfo, null, pPipeline) == VK_SUCCESS) { "BRDF LUT pipeline failed" }
            val brdfPipeline = pPipeline.get(0)

            singleTimeCommands { cmd ->
                MemoryStack.stackPush().use { s ->
                    val clear = VkClearValue.calloc(1, s)
                    clear[0].color().float32(0, 0f).float32(1, 0f).float32(2, 0f).float32(3, 1f)
                    val area = VkRect2D.calloc(s); area.offset().set(0, 0); area.extent().set(size, size)
                    val rpBegin = VkRenderPassBeginInfo.calloc(s).sType(VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO).renderPass(brdfRp).framebuffer(brdfFb).renderArea(area).pClearValues(clear)
                    vkCmdBeginRenderPass(cmd, rpBegin, VK_SUBPASS_CONTENTS_INLINE)
                    vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, brdfPipeline)
                    val vp = VkViewport.calloc(1, s).x(0f).y(0f).width(size.toFloat()).height(size.toFloat()).minDepth(0f).maxDepth(1f)
                    vkCmdSetViewport(cmd, 0, vp)
                    val sc = VkRect2D.calloc(1, s); sc.get(0).offset().set(0, 0); sc.get(0).extent().set(size, size)
                    vkCmdSetScissor(cmd, 0, sc)
                    vkCmdDraw(cmd, 3, 1, 0, 0) // fullscreen triangle
                    vkCmdEndRenderPass(cmd)
                }
            }

            vkDestroyPipeline(device, brdfPipeline, null)
            vkDestroyPipelineLayout(device, brdfLayout, null)
            vkDestroyFramebuffer(device, brdfFb, null)
            vkDestroyRenderPass(device, brdfRp, null)
            vkDestroyShaderModule(device, vert, null); vkDestroyShaderModule(device, frag, null)
        }
    }

    /**
     * Option-B IBL precompute #2: convolve the IBL cube over the hemisphere to produce a 64²-per-face
     * diffuse irradiance cube. Renders each of 6 faces into its own layer of a cube-compatible
     * color attachment via [FILTERCUBE_VERT] / [IRRADIANCE_FRAG]; the fragment shader samples the
     * env cube and accumulates a cosine-weighted hemisphere integral.
     *
     * Equivalent to Sascha-Willems' `VulkanExampleBase::generateIrradianceCube` / vkChess's setup.
     */
    private fun buildIrradianceCube() {
        if (ibl.image == VK_NULL_HANDLE) return // no IBL cube loaded — shaders will sample an undefined view; init still succeeds
        val size = 64
        // Cube color image (6 layers, single mip), RG16B16A16F to preserve HDR range.
        MemoryStack.stackPush().use { stack ->
            val info = VkImageCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO)
                .imageType(VK_IMAGE_TYPE_2D).format(envFormat).mipLevels(1).arrayLayers(6).samples(VK_SAMPLE_COUNT_1_BIT)
                .tiling(VK_IMAGE_TILING_OPTIMAL)
                .usage(VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT or VK_IMAGE_USAGE_SAMPLED_BIT or VK_IMAGE_USAGE_TRANSFER_DST_BIT)
                .sharingMode(VK_SHARING_MODE_EXCLUSIVE).initialLayout(VK_IMAGE_LAYOUT_UNDEFINED)
                .flags(VK_IMAGE_CREATE_CUBE_COMPATIBLE_BIT)
            info.extent().set(size, size, 1)
            val pImage = stack.mallocLong(1)
            check(vkCreateImage(device, info, null, pImage) == VK_SUCCESS); irradianceImage = pImage.get(0)
            val req = VkMemoryRequirements.calloc(stack); vkGetImageMemoryRequirements(device, irradianceImage, req)
            val alloc = VkMemoryAllocateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO).allocationSize(req.size())
                .memoryTypeIndex(findMemoryType(stack, req.memoryTypeBits(), VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT))
            val pMem = stack.mallocLong(1)
            check(vkAllocateMemory(device, alloc, null, pMem) == VK_SUCCESS); irradianceMem = pMem.get(0)
            vkBindImageMemory(device, irradianceImage, irradianceMem, 0)
        }
        irradianceView = createImageView(irradianceImage, envFormat, VK_IMAGE_ASPECT_COLOR_BIT,
            viewType = VK_IMAGE_VIEW_TYPE_CUBE, layerCount = 6, mipLevels = 1)

        // Per-face 2D views (one per cube layer) so each face can be attached to its own framebuffer.
        val faceViewsWithLayer = LongArray(6) { face ->
            MemoryStack.stackPush().use { stack ->
                val info = VkImageViewCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO)
                    .image(irradianceImage).viewType(VK_IMAGE_VIEW_TYPE_2D).format(envFormat)
                info.subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).baseMipLevel(0).levelCount(1).baseArrayLayer(face).layerCount(1)
                val p = stack.mallocLong(1)
                check(vkCreateImageView(device, info, null, p) == VK_SUCCESS); p.get(0)
            }
        }

        MemoryStack.stackPush().use { stack ->
            // Color-only render pass for irradiance faces (one layer, transfer-dst final so we can barrier).
            val attachment = VkAttachmentDescription.calloc(1, stack)
            attachment[0].format(envFormat).samples(VK_SAMPLE_COUNT_1_BIT)
                .loadOp(VK_ATTACHMENT_LOAD_OP_CLEAR).storeOp(VK_ATTACHMENT_STORE_OP_STORE)
                .stencilLoadOp(VK_ATTACHMENT_LOAD_OP_DONT_CARE).stencilStoreOp(VK_ATTACHMENT_STORE_OP_DONT_CARE)
                .initialLayout(VK_IMAGE_LAYOUT_UNDEFINED).finalLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL)
            val colorRef = VkAttachmentReference.calloc(1, stack).attachment(0).layout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL)
            val subpass = VkSubpassDescription.calloc(1, stack).pipelineBindPoint(VK_PIPELINE_BIND_POINT_GRAPHICS).colorAttachmentCount(1).pColorAttachments(colorRef)
            val rpInfo = VkRenderPassCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_RENDER_PASS_CREATE_INFO).pAttachments(attachment).pSubpasses(subpass)
            val pRp = stack.mallocLong(1)
            check(vkCreateRenderPass(device, rpInfo, null, pRp) == VK_SUCCESS); val irrRp = pRp.get(0)

            // One framebuffer per face.
            val fbs = LongArray(6) { face ->
                val fbInfo = VkFramebufferCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO).renderPass(irrRp).pAttachments(stack.longs(faceViewsWithLayer[face])).width(size).height(size).layers(1)
                val pFb = stack.mallocLong(1)
                check(vkCreateFramebuffer(device, fbInfo, null, pFb) == VK_SUCCESS); pFb.get(0)
            }

            // Descriptor for the env cube sampler (input to the convolution shader).
            val dsLayoutBinding = VkDescriptorSetLayoutBinding.calloc(1, stack)
                .binding(0).descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1).stageFlags(VK_SHADER_STAGE_FRAGMENT_BIT)
            val dsLayoutInfo = VkDescriptorSetLayoutCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO).pBindings(dsLayoutBinding)
            val pDsLayout = stack.mallocLong(1)
            check(vkCreateDescriptorSetLayout(device, dsLayoutInfo, null, pDsLayout) == VK_SUCCESS); val irrDsLayout = pDsLayout.get(0)
            val poolSize = VkDescriptorPoolSize.calloc(1, stack).type(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1)
            val poolInfo = VkDescriptorPoolCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO).pPoolSizes(poolSize).maxSets(1)
            val pPool = stack.mallocLong(1)
            check(vkCreateDescriptorPool(device, poolInfo, null, pPool) == VK_SUCCESS); val irrPool = pPool.get(0)
            val allocInfo = VkDescriptorSetAllocateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO).descriptorPool(irrPool).pSetLayouts(stack.longs(irrDsLayout))
            val pSet = stack.mallocLong(1)
            check(vkAllocateDescriptorSets(device, allocInfo, pSet) == VK_SUCCESS); val irrSet = pSet.get(0)
            val envInfo = VkDescriptorImageInfo.calloc(1, stack).imageLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL).imageView(ibl.view).sampler(cubeSampler)
            val write = VkWriteDescriptorSet.calloc(1, stack).sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET).dstSet(irrSet).dstBinding(0).descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1).pImageInfo(envInfo)
            vkUpdateDescriptorSets(device, write, null)

            // Pipeline: filtercube.vert (push-constant MVP) + irradiancecube.frag (env sampler).
            val vert = createShaderModule(stack, FILTERCUBE_VERT, Shaderc.shaderc_glsl_vertex_shader, "filterCubeVert")
            val frag = createShaderModule(stack, IRRADIANCE_FRAG, Shaderc.shaderc_glsl_fragment_shader, "irradianceFrag")
            val stages = VkPipelineShaderStageCreateInfo.calloc(2, stack)
            stages[0].sType(VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO).stage(VK_SHADER_STAGE_VERTEX_BIT).module(vert).pName(stack.UTF8("main"))
            stages[1].sType(VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO).stage(VK_SHADER_STAGE_FRAGMENT_BIT).module(frag).pName(stack.UTF8("main"))
            // Cube face vertex: just vec3 position.
            val binding = VkVertexInputBindingDescription.calloc(1, stack).binding(0).stride(3 * 4).inputRate(VK_VERTEX_INPUT_RATE_VERTEX)
            val attrs = VkVertexInputAttributeDescription.calloc(1, stack); attrs[0].location(0).binding(0).format(VK_FORMAT_R32G32B32_SFLOAT).offset(0)
            val vertexInput = VkPipelineVertexInputStateCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO).pVertexBindingDescriptions(binding).pVertexAttributeDescriptions(attrs)
            val inputAssembly = VkPipelineInputAssemblyStateCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO).topology(VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST)
            val viewportState = VkPipelineViewportStateCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO).viewportCount(1).scissorCount(1)
            // Render the inside of the unit cube (faces point inward) — cull FRONT to keep back faces.
            val raster = VkPipelineRasterizationStateCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO).polygonMode(VK_POLYGON_MODE_FILL).cullMode(VK_CULL_MODE_FRONT_BIT).frontFace(VK_FRONT_FACE_COUNTER_CLOCKWISE).lineWidth(1f)
            val multisample = VkPipelineMultisampleStateCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO).rasterizationSamples(VK_SAMPLE_COUNT_1_BIT)
            val blendAttachment = VkPipelineColorBlendAttachmentState.calloc(1, stack).colorWriteMask(VK_COLOR_COMPONENT_R_BIT or VK_COLOR_COMPONENT_G_BIT or VK_COLOR_COMPONENT_B_BIT or VK_COLOR_COMPONENT_A_BIT).blendEnable(false)
            val blend = VkPipelineColorBlendStateCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO).pAttachments(blendAttachment)
            val dynamic = VkPipelineDynamicStateCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_PIPELINE_DYNAMIC_STATE_CREATE_INFO).pDynamicStates(stack.ints(VK_DYNAMIC_STATE_VIEWPORT, VK_DYNAMIC_STATE_SCISSOR))
            // Push constants: mat4 mvp (offset 0) + floats deltaPhi/deltaTheta (offsets 64/68).
            val pushRange = VkPushConstantRange.calloc(1, stack).stageFlags(VK_SHADER_STAGE_VERTEX_BIT or VK_SHADER_STAGE_FRAGMENT_BIT).offset(0).size(72)
            val layoutInfo = VkPipelineLayoutCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO)
                .pSetLayouts(stack.longs(irrDsLayout)).pPushConstantRanges(pushRange)
            val pLayout = stack.mallocLong(1)
            check(vkCreatePipelineLayout(device, layoutInfo, null, pLayout) == VK_SUCCESS); val irrPipelineLayout = pLayout.get(0)
            val pipelineInfo = VkGraphicsPipelineCreateInfo.calloc(1, stack).sType(VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO)
                .pStages(stages).pVertexInputState(vertexInput).pInputAssemblyState(inputAssembly).pViewportState(viewportState)
                .pRasterizationState(raster).pMultisampleState(multisample).pColorBlendState(blend).pDynamicState(dynamic)
                .layout(irrPipelineLayout).renderPass(irrRp).subpass(0)
            val pPipeline = stack.mallocLong(1)
            check(vkCreateGraphicsPipelines(device, VK_NULL_HANDLE, pipelineInfo, null, pPipeline) == VK_SUCCESS) { "irradiance pipeline failed" }
            val irrPipeline = pPipeline.get(0)

            // 1x1x1 unit cube as a triangle list (12 tris / 36 verts). Centered at origin.
            val cubeVerts = floatArrayOf(
                -1f,-1f,-1f, -1f,-1f, 1f,  1f,-1f, 1f,   1f,-1f, 1f,   1f,-1f,-1f, -1f,-1f,-1f, // bottom
                -1f, 1f,-1f,  1f, 1f,-1f,  1f, 1f, 1f,   1f, 1f, 1f,  -1f, 1f, 1f,  -1f, 1f,-1f, // top
                -1f,-1f, 1f,   1f,-1f, 1f,  1f, 1f, 1f,   1f, 1f, 1f,  -1f, 1f, 1f,  -1f,-1f, 1f, // front (+z)
                -1f, 1f,-1f,  -1f, 1f, 1f, -1f,-1f, 1f,  -1f,-1f, 1f, -1f,-1f,-1f,  -1f, 1f,-1f, // left (-x)
                 1f,-1f,-1f,   1f,-1f, 1f,  1f, 1f, 1f,   1f, 1f, 1f,   1f, 1f,-1f,   1f,-1f,-1f, // right (+x)
                -1f,-1f,-1f,  -1f, 1f,-1f,  1f, 1f,-1f,   1f, 1f,-1f,   1f,-1f,-1f,  -1f,-1f,-1f, // back (-z)
            )
            val (cubeVBuf, cubeVMem) = createBuffer(cubeVerts.size.toLong() * 4, VK_BUFFER_USAGE_VERTEX_BUFFER_BIT, hostVisible)
            writeFloats(cubeVMem, cubeVerts)

            // Per-face view matrices looking at the cube center, matching the Vulkan cube-face convention
            // ( Sascha-Willems Table: +X, -X, +Y, -Y, +Z, -Z ).
            val proj = Matrix4f().perspective(Math.toRadians(90.0).toFloat(), 1f, 0.1f, 10f, true)
            val faceViewMats = arrayOf(
                Matrix4f().lookAt( 1f, 0f, 0f,  0f, 0f, 0f,  0f,-1f, 0f), // +X
                Matrix4f().lookAt(-1f, 0f, 0f,  0f, 0f, 0f,  0f,-1f, 0f), // -X
                Matrix4f().lookAt( 0f, 1f, 0f,  0f, 0f, 0f,  0f, 0f, 1f), // +Y (up = +Z)
                Matrix4f().lookAt( 0f,-1f, 0f,  0f, 0f, 0f,  0f, 0f,-1f), // -Y (up = -Z)
                Matrix4f().lookAt( 0f, 0f, 1f,  0f, 0f, 0f,  0f,-1f, 0f), // +Z
                Matrix4f().lookAt( 0f, 0f,-1f,  0f, 0f, 0f,  0f,-1f, 0f), // -Z
            )
            val deltaPhi = 0.035f    // ~PI/90 azimuth step
            val deltaTheta = 0.025f  // ~PI/60 polar step

            singleTimeCommands { cmd ->
                MemoryStack.stackPush().use { s ->
                    val vp = VkViewport.calloc(1, s).x(0f).y(0f).width(size.toFloat()).height(size.toFloat()).minDepth(0f).maxDepth(1f)
                    val sc = VkRect2D.calloc(1, s); sc.get(0).offset().set(0, 0); sc.get(0).extent().set(size, size)
                    val clear = VkClearValue.calloc(1, s); clear[0].color().float32(0, 0f).float32(1, 0f).float32(2, 0f).float32(3, 1f)
                    val area = VkRect2D.calloc(s); area.offset().set(0, 0); area.extent().set(size, size)
                    vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, irrPipeline)
                    vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, irrPipelineLayout, 0, s.longs(irrSet), null)
                    vkCmdBindVertexBuffers(cmd, 0, s.longs(cubeVBuf), s.longs(0))
                    vkCmdSetViewport(cmd, 0, vp)
                    vkCmdSetScissor(cmd, 0, sc)
                    val push = s.malloc(72)
                    for (face in 0 until 6) {
                        val viewProj = proj.mul(faceViewMats[face], Matrix4f())
                        viewProj.get(0, push)
                        push.putFloat(64, deltaPhi)
                        push.putFloat(68, deltaTheta)
                        vkCmdPushConstants(cmd, irrPipelineLayout, VK_SHADER_STAGE_VERTEX_BIT or VK_SHADER_STAGE_FRAGMENT_BIT, 0, push)
                        val rpBegin = VkRenderPassBeginInfo.calloc(s).sType(VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO).renderPass(irrRp).framebuffer(fbs[face]).renderArea(area).pClearValues(clear)
                        vkCmdBeginRenderPass(cmd, rpBegin, VK_SUBPASS_CONTENTS_INLINE)
                        vkCmdDraw(cmd, 36, 1, 0, 0)
                        vkCmdEndRenderPass(cmd)
                    }
                    // Transition the whole irradiance cube (all 6 layers) to shader-read.
                    val barrier = VkImageMemoryBarrier.calloc(1, s).sType(VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER)
                        .oldLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL).newLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL)
                        .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                        .image(irradianceImage).srcAccessMask(VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT).dstAccessMask(VK_ACCESS_SHADER_READ_BIT)
                    barrier.subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(6)
                    vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT, VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT, 0, null, null, barrier)
                }
            }

            // Cleanup everything except the irradiance image/view/mem (kept for the main pass).
            destroyBuffer(cubeVBuf, cubeVMem)
            for (fb in fbs) vkDestroyFramebuffer(device, fb, null)
            vkDestroyPipeline(device, irrPipeline, null)
            vkDestroyPipelineLayout(device, irrPipelineLayout, null)
            vkDestroyDescriptorPool(device, irrPool, null)
            vkDestroyDescriptorSetLayout(device, irrDsLayout, null)
            for (v in faceViewsWithLayer) vkDestroyImageView(device, v, null)
            vkDestroyRenderPass(device, irrRp, null)
            vkDestroyShaderModule(device, vert, null); vkDestroyShaderModule(device, frag, null)
        }
    }


    private fun createShadowPipeline(stack: MemoryStack) {
        val vert = createShaderModule(stack, SHADOW_VERT, Shaderc.shaderc_glsl_vertex_shader, "shadowVert")
        val frag = createShaderModule(stack, SHADOW_FRAG, Shaderc.shaderc_glsl_fragment_shader, "shadowFrag")
        val stages = VkPipelineShaderStageCreateInfo.calloc(2, stack)
        stages[0].sType(VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO).stage(VK_SHADER_STAGE_VERTEX_BIT).module(vert).pName(stack.UTF8("main"))
        stages[1].sType(VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO).stage(VK_SHADER_STAGE_FRAGMENT_BIT).module(frag).pName(stack.UTF8("main"))
        val binding = VkVertexInputBindingDescription.calloc(1, stack).binding(0).stride(11 * 4).inputRate(VK_VERTEX_INPUT_RATE_VERTEX)
        val attrs = VkVertexInputAttributeDescription.calloc(1, stack)
        attrs[0].location(0).binding(0).format(VK_FORMAT_R32G32B32_SFLOAT).offset(0)
        val vertexInput = VkPipelineVertexInputStateCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO).pVertexBindingDescriptions(binding).pVertexAttributeDescriptions(attrs)
        val inputAssembly = VkPipelineInputAssemblyStateCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO).topology(VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST)
        val viewportState = VkPipelineViewportStateCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO).viewportCount(1).scissorCount(1)
        val raster = VkPipelineRasterizationStateCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO)
            .polygonMode(VK_POLYGON_MODE_FILL).cullMode(VK_CULL_MODE_FRONT_BIT).frontFace(VK_FRONT_FACE_COUNTER_CLOCKWISE).lineWidth(1f)
            .depthBiasEnable(true).depthBiasConstantFactor(2.5f).depthBiasSlopeFactor(2.5f)
        val multisample = VkPipelineMultisampleStateCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO).rasterizationSamples(VK_SAMPLE_COUNT_1_BIT)
        val depth = VkPipelineDepthStencilStateCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_PIPELINE_DEPTH_STENCIL_STATE_CREATE_INFO).depthTestEnable(true).depthWriteEnable(true).depthCompareOp(VK_COMPARE_OP_LESS)
        val blend = VkPipelineColorBlendStateCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO)
        val dynamic = VkPipelineDynamicStateCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_PIPELINE_DYNAMIC_STATE_CREATE_INFO).pDynamicStates(stack.ints(VK_DYNAMIC_STATE_VIEWPORT, VK_DYNAMIC_STATE_SCISSOR))
        val pushConstant = VkPushConstantRange.calloc(1, stack).stageFlags(VK_SHADER_STAGE_VERTEX_BIT).offset(0).size(64)
        val layoutInfo = VkPipelineLayoutCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO).pPushConstantRanges(pushConstant)
        val pLayout = stack.mallocLong(1)
        check(vkCreatePipelineLayout(device, layoutInfo, null, pLayout) == VK_SUCCESS); shadowPipelineLayout = pLayout.get(0)
        val pipelineInfo = VkGraphicsPipelineCreateInfo.calloc(1, stack).sType(VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO)
            .pStages(stages).pVertexInputState(vertexInput).pInputAssemblyState(inputAssembly).pViewportState(viewportState)
            .pRasterizationState(raster).pMultisampleState(multisample).pDepthStencilState(depth).pColorBlendState(blend).pDynamicState(dynamic)
            .layout(shadowPipelineLayout).renderPass(shadowRenderPass).subpass(0)
        val pPipeline = stack.mallocLong(1)
        check(vkCreateGraphicsPipelines(device, VK_NULL_HANDLE, pipelineInfo, null, pPipeline) == VK_SUCCESS) { "shadow pipeline failed" }
        shadowPipeline = pPipeline.get(0)
        vkDestroyShaderModule(device, vert, null); vkDestroyShaderModule(device, frag, null)
    }

    private fun lightViewProj(): Matrix4f {
        val dist = 14f
        val eye = org.joml.Vector3f(lightDir).mul(dist)
        val proj = Matrix4f().ortho(-5.5f, 5.5f, -5.5f, 5.5f, 0.1f, 30f, true)
        val view = Matrix4f().lookAt(eye.x, eye.y, eye.z, 0f, 0f, 0f, 0f, 1f, 0f)
        return proj.mul(view)
    }

    private fun createSkyPipeline(stack: MemoryStack) {
        val vert = createShaderModule(stack, SKY_VERT, Shaderc.shaderc_glsl_vertex_shader, "skyVert")
        val frag = createShaderModule(stack, SKY_FRAG, Shaderc.shaderc_glsl_fragment_shader, "skyFrag")
        val stages = VkPipelineShaderStageCreateInfo.calloc(2, stack)
        stages[0].sType(VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO).stage(VK_SHADER_STAGE_VERTEX_BIT).module(vert).pName(stack.UTF8("main"))
        stages[1].sType(VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO).stage(VK_SHADER_STAGE_FRAGMENT_BIT).module(frag).pName(stack.UTF8("main"))
        val vertexInput = VkPipelineVertexInputStateCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO)
        val inputAssembly = VkPipelineInputAssemblyStateCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO).topology(VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST)
        val viewportState = VkPipelineViewportStateCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO).viewportCount(1).scissorCount(1)
        val raster = VkPipelineRasterizationStateCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO).polygonMode(VK_POLYGON_MODE_FILL).cullMode(VK_CULL_MODE_NONE).frontFace(VK_FRONT_FACE_COUNTER_CLOCKWISE).lineWidth(1f)
        val multisample = VkPipelineMultisampleStateCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO).rasterizationSamples(samples)
        val depth = VkPipelineDepthStencilStateCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_PIPELINE_DEPTH_STENCIL_STATE_CREATE_INFO).depthTestEnable(false).depthWriteEnable(false).depthCompareOp(VK_COMPARE_OP_ALWAYS)
        val blendAttachment = VkPipelineColorBlendAttachmentState.calloc(1, stack).colorWriteMask(VK_COLOR_COMPONENT_R_BIT or VK_COLOR_COMPONENT_G_BIT or VK_COLOR_COMPONENT_B_BIT or VK_COLOR_COMPONENT_A_BIT).blendEnable(false)
        val blend = VkPipelineColorBlendStateCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO).pAttachments(blendAttachment)
        val dynamic = VkPipelineDynamicStateCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_PIPELINE_DYNAMIC_STATE_CREATE_INFO).pDynamicStates(stack.ints(VK_DYNAMIC_STATE_VIEWPORT, VK_DYNAMIC_STATE_SCISSOR))

        val layoutInfo = VkPipelineLayoutCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO).pSetLayouts(stack.longs(descriptorSetLayout))
        val pLayout = stack.mallocLong(1)
        check(vkCreatePipelineLayout(device, layoutInfo, null, pLayout) == VK_SUCCESS); skyPipelineLayout = pLayout.get(0)

        val pipelineInfo = VkGraphicsPipelineCreateInfo.calloc(1, stack).sType(VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO)
            .pStages(stages).pVertexInputState(vertexInput).pInputAssemblyState(inputAssembly).pViewportState(viewportState)
            .pRasterizationState(raster).pMultisampleState(multisample).pDepthStencilState(depth).pColorBlendState(blend).pDynamicState(dynamic)
            .layout(skyPipelineLayout).renderPass(renderPass).subpass(0)
        val pPipeline = stack.mallocLong(1)
        check(vkCreateGraphicsPipelines(device, VK_NULL_HANDLE, pipelineInfo, null, pPipeline) == VK_SUCCESS) { "sky pipeline failed" }
        skyPipeline = pPipeline.get(0)
        vkDestroyShaderModule(device, vert, null); vkDestroyShaderModule(device, frag, null)
    }

    private fun hasInstanceExtension(stack: MemoryStack, name: String): Boolean {
        val count = stack.mallocInt(1)
        vkEnumerateInstanceExtensionProperties(null as ByteBuffer?, count, null)
        val props = VkExtensionProperties.calloc(count.get(0), stack)
        vkEnumerateInstanceExtensionProperties(null as ByteBuffer?, count, props)
        return (0 until count.get(0)).any { props[it].extensionNameString() == name }
    }

    private fun hasDeviceExtension(stack: MemoryStack, name: String): Boolean {
        val count = stack.mallocInt(1)
        vkEnumerateDeviceExtensionProperties(physicalDevice, null as ByteBuffer?, count, null)
        val props = VkExtensionProperties.calloc(count.get(0), stack)
        vkEnumerateDeviceExtensionProperties(physicalDevice, null as ByteBuffer?, count, props)
        return (0 until count.get(0)).any { props[it].extensionNameString() == name }
    }

    private fun findGraphicsQueueFamily(stack: MemoryStack): Int {
        val count = stack.mallocInt(1)
        vkGetPhysicalDeviceQueueFamilyProperties(physicalDevice, count, null)
        val props = VkQueueFamilyProperties.calloc(count.get(0), stack)
        vkGetPhysicalDeviceQueueFamilyProperties(physicalDevice, count, props)
        for (i in 0 until count.get(0)) if (props[i].queueFlags() and VK_QUEUE_GRAPHICS_BIT != 0) return i
        error("no graphics queue family")
    }

    private fun getMaxUsableSampleCount(stack: MemoryStack): Int {
        val props = VkPhysicalDeviceProperties.calloc(stack)
        vkGetPhysicalDeviceProperties(physicalDevice, props)
        val counts = props.limits().framebufferColorSampleCounts() and props.limits().framebufferDepthSampleCounts()
        if ((counts and VK_SAMPLE_COUNT_8_BIT) != 0) return VK_SAMPLE_COUNT_8_BIT
        if ((counts and VK_SAMPLE_COUNT_4_BIT) != 0) return VK_SAMPLE_COUNT_4_BIT
        if ((counts and VK_SAMPLE_COUNT_2_BIT) != 0) return VK_SAMPLE_COUNT_2_BIT
        return VK_SAMPLE_COUNT_1_BIT
    }

    /** Part B.0: sample counts supported by `hdrFormat` as a multisampled COLOR_ATTACHMENT (used to
     *  clamp `samples` for the HDR scene target). Falls back to 1× if the query is unavailable. */
    private fun hdrColorSampleCounts(stack: MemoryStack): Int {
        val props = VkImageFormatProperties.calloc(stack)
        val usage = VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT or VK_IMAGE_USAGE_SAMPLED_BIT
        val r = vkGetPhysicalDeviceImageFormatProperties(physicalDevice, hdrFormat, VK_IMAGE_TYPE_2D, VK_IMAGE_TILING_OPTIMAL, usage, 0, props)
        if (r != VK_SUCCESS) return VK_SAMPLE_COUNT_1_BIT
        return props.sampleCounts()
    }

    private fun createRenderPass(stack: MemoryStack) {
        // 0: MSAA color (rendered into, HDR linear), 1: MSAA depth, 2: single-sample HDR resolve
        // (sceneHdr — sampled by the bloom/composite passes, so finalLayout SHADER_READ_ONLY).
        val attachments = VkAttachmentDescription.calloc(3, stack)
        attachments[0].format(hdrFormat).samples(samples)
            .loadOp(VK_ATTACHMENT_LOAD_OP_CLEAR).storeOp(VK_ATTACHMENT_STORE_OP_DONT_CARE)
            .stencilLoadOp(VK_ATTACHMENT_LOAD_OP_DONT_CARE).stencilStoreOp(VK_ATTACHMENT_STORE_OP_DONT_CARE)
            .initialLayout(VK_IMAGE_LAYOUT_UNDEFINED).finalLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL)
        attachments[1].format(depthFormat).samples(samples)
            .loadOp(VK_ATTACHMENT_LOAD_OP_CLEAR).storeOp(VK_ATTACHMENT_STORE_OP_DONT_CARE)
            .stencilLoadOp(VK_ATTACHMENT_LOAD_OP_DONT_CARE).stencilStoreOp(VK_ATTACHMENT_STORE_OP_DONT_CARE)
            .initialLayout(VK_IMAGE_LAYOUT_UNDEFINED).finalLayout(VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL)
        attachments[2].format(hdrFormat).samples(VK_SAMPLE_COUNT_1_BIT)
            .loadOp(VK_ATTACHMENT_LOAD_OP_DONT_CARE).storeOp(VK_ATTACHMENT_STORE_OP_STORE)
            .stencilLoadOp(VK_ATTACHMENT_LOAD_OP_DONT_CARE).stencilStoreOp(VK_ATTACHMENT_STORE_OP_DONT_CARE)
            .initialLayout(VK_IMAGE_LAYOUT_UNDEFINED).finalLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL)
        val colorRef = VkAttachmentReference.calloc(1, stack).attachment(0).layout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL)
        val depthRef = VkAttachmentReference.calloc(stack).attachment(1).layout(VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL)
        val resolveRef = VkAttachmentReference.calloc(1, stack).attachment(2).layout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL)
        val subpass = VkSubpassDescription.calloc(1, stack).pipelineBindPoint(VK_PIPELINE_BIND_POINT_GRAPHICS)
            .colorAttachmentCount(1).pColorAttachments(colorRef).pResolveAttachments(resolveRef).pDepthStencilAttachment(depthRef)
        // Part B: the bright/composite pass samples sceneHdr (attachment 2) right after this pass, so
        // signal the src COLOR_ATTACHMENT_WRITE must complete before dst FRAGMENT_SHADER reads.
        val dep = VkSubpassDependency.calloc(1, stack)
            .srcSubpass(0).dstSubpass(VK_SUBPASS_EXTERNAL)
            .srcStageMask(VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT).dstStageMask(VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT)
            .srcAccessMask(VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT).dstAccessMask(VK_ACCESS_SHADER_READ_BIT)
            .dependencyFlags(VK_DEPENDENCY_BY_REGION_BIT)
        val rpInfo = VkRenderPassCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_RENDER_PASS_CREATE_INFO).pAttachments(attachments).pSubpasses(subpass).pDependencies(dep)
        val p = stack.mallocLong(1)
        check(vkCreateRenderPass(device, rpInfo, null, p) == VK_SUCCESS) { "vkCreateRenderPass failed" }
        renderPass = p.get(0)
    }

    private fun createSampler(stack: MemoryStack) {
        // Material albedo/MR sampler — 2D, repeat, linear min/mag + anisotropic + full mip range.
        // The chess textures are uploaded with a generated mip chain (see upload2dImage), so letting
        // the sampler pick the right mip kills the shimmer/aliasing on distant pieces and board.
        val info = VkSamplerCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO)
            .magFilter(VK_FILTER_LINEAR).minFilter(VK_FILTER_LINEAR)
            .addressModeU(VK_SAMPLER_ADDRESS_MODE_REPEAT).addressModeV(VK_SAMPLER_ADDRESS_MODE_REPEAT).addressModeW(VK_SAMPLER_ADDRESS_MODE_REPEAT)
            .mipmapMode(VK_SAMPLER_MIPMAP_MODE_LINEAR).maxLod(VK_LOD_CLAMP_NONE).minLod(0f)
            .anisotropyEnable(true).maxAnisotropy(16f)
        val p = stack.mallocLong(1)
        check(vkCreateSampler(device, info, null, p) == VK_SUCCESS); sampler = p.get(0)

        // Cube sampler shared by env / irradiance / prefilteredMap — needs a real maxLod so the
        // prefilteredReflection() shader can sample higher mips for rougher surfaces.
        val cubeInfo = VkSamplerCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO)
            .magFilter(VK_FILTER_LINEAR).minFilter(VK_FILTER_LINEAR)
            .addressModeU(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE).addressModeV(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE).addressModeW(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
            .mipmapMode(VK_SAMPLER_MIPMAP_MODE_LINEAR).maxLod(VK_LOD_CLAMP_NONE).minLod(0f)
        val pc = stack.mallocLong(1)
        check(vkCreateSampler(device, cubeInfo, null, pc) == VK_SUCCESS); cubeSampler = pc.get(0)

        // BRDF LUT sampler — 2D, clamp, no mip (single 512² texture).
        val brdfInfo = VkSamplerCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO)
            .magFilter(VK_FILTER_LINEAR).minFilter(VK_FILTER_LINEAR)
            .addressModeU(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE).addressModeV(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE).addressModeW(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
            .mipmapMode(VK_SAMPLER_MIPMAP_MODE_LINEAR).maxLod(0f).minLod(0f)
        val pb = stack.mallocLong(1)
        check(vkCreateSampler(device, brdfInfo, null, pb) == VK_SUCCESS); brdfLutSampler = pb.get(0)

        // 1×1 flat normal (128,128,255 ≈ +Z in tangent space) for materials with no normal map.
        // Without this, piece materials (whites/blacks) would need a separate no-normal code path.
        createDefaultNormalTexture()
    }

    private fun createDefaultNormalTexture() {
        val flatBytes = byteArrayOf(128.toByte(), 128.toByte(), 255.toByte(), 255.toByte())
        val (stgBuf, stgMem) = createBuffer(4L, VK_BUFFER_USAGE_TRANSFER_SRC_BIT, hostVisible)
        writeBytes(stgMem, flatBytes)
        val (img, mem) = createImage(1, 1, VK_FORMAT_R8G8B8A8_UNORM, VK_IMAGE_USAGE_TRANSFER_DST_BIT or VK_IMAGE_USAGE_SAMPLED_BIT)
        defaultNormalImage = img; defaultNormalMem = mem
        singleTimeCommands { cmd ->
            MemoryStack.stackPush().use { s ->
                transition(cmd, s, img, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 0, VK_ACCESS_TRANSFER_WRITE_BIT, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT)
                val region = VkBufferImageCopy.calloc(1, s)
                region.bufferOffset(0).bufferRowLength(0).bufferImageHeight(0)
                region.imageSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1)
                region.imageOffset().set(0, 0, 0); region.imageExtent().set(1, 1, 1)
                vkCmdCopyBufferToImage(cmd, stgBuf, img, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, region)
                transition(cmd, s, img, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, VK_ACCESS_TRANSFER_WRITE_BIT, VK_ACCESS_SHADER_READ_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT)
            }
        }
        destroyBuffer(stgBuf, stgMem)
        defaultNormalView = createImageView(img, VK_FORMAT_R8G8B8A8_UNORM, VK_IMAGE_ASPECT_COLOR_BIT)
    }

    private fun createDescriptorLayoutAndPool(stack: MemoryStack) {
        // 10 bindings; one descriptor set per material. Bindings 7 (albedo), 8 (MR), 9 (normal) vary
        // per set; the rest point at shared scene resources. Materials without a normal map bind a
        // 1×1 flat-default normal so the same shader works for wood pieces and marble alike.
        val bindings = VkDescriptorSetLayoutBinding.calloc(10, stack)
        bindings[0].binding(0).descriptorType(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER).descriptorCount(1).stageFlags(VK_SHADER_STAGE_VERTEX_BIT or VK_SHADER_STAGE_FRAGMENT_BIT) // UBO (matrices)
        bindings[1].binding(1).descriptorType(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER).descriptorCount(1).stageFlags(VK_SHADER_STAGE_FRAGMENT_BIT) // UBOParams (light/exposure/gamma)
        bindings[2].binding(2).descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1).stageFlags(VK_SHADER_STAGE_FRAGMENT_BIT) // shadow map
        bindings[3].binding(3).descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1).stageFlags(VK_SHADER_STAGE_FRAGMENT_BIT) // skybox (background)
        bindings[4].binding(4).descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1).stageFlags(VK_SHADER_STAGE_FRAGMENT_BIT) // irradiance cube
        bindings[5].binding(5).descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1).stageFlags(VK_SHADER_STAGE_FRAGMENT_BIT) // prefilteredMap (ibl cube)
        bindings[6].binding(6).descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1).stageFlags(VK_SHADER_STAGE_FRAGMENT_BIT) // brdfLUT
        bindings[7].binding(7).descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1).stageFlags(VK_SHADER_STAGE_FRAGMENT_BIT) // material albedo
        bindings[8].binding(8).descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1).stageFlags(VK_SHADER_STAGE_FRAGMENT_BIT) // material metallicRoughness
        bindings[9].binding(9).descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1).stageFlags(VK_SHADER_STAGE_FRAGMENT_BIT) // material normal
        val layoutInfo = VkDescriptorSetLayoutCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO).pBindings(bindings)
        val pLayout = stack.mallocLong(1)
        check(vkCreateDescriptorSetLayout(device, layoutInfo, null, pLayout) == VK_SUCCESS); descriptorSetLayout = pLayout.get(0)

        // Per material: 9 image bindings + 2 UBO bindings (albedo/MR/normal are per-set).
        val n = ChessTexture.entries.size
        val poolSizes = VkDescriptorPoolSize.calloc(2, stack)
        poolSizes[0].type(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(n * 9)
        poolSizes[1].type(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER).descriptorCount(n * 2)
        val poolInfo = VkDescriptorPoolCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO).pPoolSizes(poolSizes).maxSets(n)
        val pPool = stack.mallocLong(1)
        check(vkCreateDescriptorPool(device, poolInfo, null, pPool) == VK_SUCCESS); descriptorPool = pPool.get(0)
    }

    private fun createPipeline(stack: MemoryStack) {
        val vert = createShaderModule(stack, VERT_GLSL, Shaderc.shaderc_glsl_vertex_shader, "vert")
        val frag = createShaderModule(stack, FRAG_GLSL, Shaderc.shaderc_glsl_fragment_shader, "frag")
        val stages = VkPipelineShaderStageCreateInfo.calloc(2, stack)
        stages[0].sType(VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO).stage(VK_SHADER_STAGE_VERTEX_BIT).module(vert).pName(stack.UTF8("main"))
        stages[1].sType(VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO).stage(VK_SHADER_STAGE_FRAGMENT_BIT).module(frag).pName(stack.UTF8("main"))

        // Two vertex bindings: binding 0 = interleaved pos/normal/uv/tint (11 floats), binding 1 =
        // parallel tangent stream (vec4, optional). Keeping tangents on a separate binding leaves the
        // wgpu path's 11-float format untouched and lets this pipeline read them at location 4.
        val bindings = VkVertexInputBindingDescription.calloc(2, stack)
        bindings[0].binding(0).stride(11 * 4).inputRate(VK_VERTEX_INPUT_RATE_VERTEX)
        bindings[1].binding(1).stride(4 * 4).inputRate(VK_VERTEX_INPUT_RATE_VERTEX)
        val attrs = VkVertexInputAttributeDescription.calloc(5, stack)
        attrs[0].location(0).binding(0).format(VK_FORMAT_R32G32B32_SFLOAT).offset(0)
        attrs[1].location(1).binding(0).format(VK_FORMAT_R32G32B32_SFLOAT).offset(3 * 4)
        attrs[2].location(2).binding(0).format(VK_FORMAT_R32G32_SFLOAT).offset(6 * 4)
        attrs[3].location(3).binding(0).format(VK_FORMAT_R32G32B32_SFLOAT).offset(8 * 4)
        attrs[4].location(4).binding(1).format(VK_FORMAT_R32G32B32A32_SFLOAT).offset(0) // tangent xyz + handedness w
        val vertexInput = VkPipelineVertexInputStateCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO).pVertexBindingDescriptions(bindings).pVertexAttributeDescriptions(attrs)
        val inputAssembly = VkPipelineInputAssemblyStateCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO).topology(VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST)
        val viewportState = VkPipelineViewportStateCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO).viewportCount(1).scissorCount(1)
        val raster = VkPipelineRasterizationStateCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO).polygonMode(VK_POLYGON_MODE_FILL).cullMode(VK_CULL_MODE_NONE).frontFace(VK_FRONT_FACE_COUNTER_CLOCKWISE).lineWidth(1f)
        val multisample = VkPipelineMultisampleStateCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO).rasterizationSamples(samples)
        val depth = VkPipelineDepthStencilStateCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_PIPELINE_DEPTH_STENCIL_STATE_CREATE_INFO).depthTestEnable(true).depthWriteEnable(true).depthCompareOp(VK_COMPARE_OP_LESS)
        val blendAttachment = VkPipelineColorBlendAttachmentState.calloc(1, stack).colorWriteMask(VK_COLOR_COMPONENT_R_BIT or VK_COLOR_COMPONENT_G_BIT or VK_COLOR_COMPONENT_B_BIT or VK_COLOR_COMPONENT_A_BIT).blendEnable(false)
        val blend = VkPipelineColorBlendStateCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO).pAttachments(blendAttachment)
        val dynamic = VkPipelineDynamicStateCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_PIPELINE_DYNAMIC_STATE_CREATE_INFO).pDynamicStates(stack.ints(VK_DYNAMIC_STATE_VIEWPORT, VK_DYNAMIC_STATE_SCISSOR))

        // Push constants: vec4 baseColorFactor (16B) + float metallicFactor + float roughnessFactor
        // + vec2 pad (16B) = 32 bytes per draw. Set per-material-group in recordCommandBuffer.
        val matPushRange = VkPushConstantRange.calloc(1, stack).stageFlags(VK_SHADER_STAGE_FRAGMENT_BIT).offset(0).size(48)
        val layoutInfo = VkPipelineLayoutCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO).pSetLayouts(stack.longs(descriptorSetLayout)).pPushConstantRanges(matPushRange)
        val pLayout = stack.mallocLong(1)
        check(vkCreatePipelineLayout(device, layoutInfo, null, pLayout) == VK_SUCCESS); pipelineLayout = pLayout.get(0)

        val pipelineInfo = VkGraphicsPipelineCreateInfo.calloc(1, stack).sType(VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO)
            .pStages(stages).pVertexInputState(vertexInput).pInputAssemblyState(inputAssembly).pViewportState(viewportState)
            .pRasterizationState(raster).pMultisampleState(multisample).pDepthStencilState(depth).pColorBlendState(blend).pDynamicState(dynamic)
            .layout(pipelineLayout).renderPass(renderPass).subpass(0)
        val pPipeline = stack.mallocLong(1)
        check(vkCreateGraphicsPipelines(device, VK_NULL_HANDLE, pipelineInfo, null, pPipeline) == VK_SUCCESS) { "pipeline failed" }
        pipeline = pPipeline.get(0)
        vkDestroyShaderModule(device, vert, null); vkDestroyShaderModule(device, frag, null)
    }

    /**
     * Part B post pipeline: builds the two post render passes, a shared descriptor layout/pool, and
     * the three fullscreen (FSQ_VERT) pipelines — bright (threshold HDR), blur (separable Gaussian),
     * composite (scene + bloom, then tonemap+gamma into the LDR read-back target). All draws are a
     * single `vkCmdDraw(3,1,0,0)` fullscreen triangle (no vertex buffers), mirroring buildBrdfLut.
     */
    private fun createPostPipeline(stack: MemoryStack) {
        // postRenderPass: 1 HDR attachment, shared by the bright + blur passes. Two external
        // dependencies (in/out) so consecutive bright/blur passes that read each other's output
        // serialize correctly via layout transitions rather than manual barriers.
        run {
            val att = VkAttachmentDescription.calloc(1, stack)
            att[0].format(hdrFormat).samples(VK_SAMPLE_COUNT_1_BIT)
                .loadOp(VK_ATTACHMENT_LOAD_OP_DONT_CARE).storeOp(VK_ATTACHMENT_STORE_OP_STORE)
                .stencilLoadOp(VK_ATTACHMENT_LOAD_OP_DONT_CARE).stencilStoreOp(VK_ATTACHMENT_STORE_OP_DONT_CARE)
                .initialLayout(VK_IMAGE_LAYOUT_UNDEFINED).finalLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL)
            val colorRef = VkAttachmentReference.calloc(1, stack).attachment(0).layout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL)
            val subpass = VkSubpassDescription.calloc(1, stack).pipelineBindPoint(VK_PIPELINE_BIND_POINT_GRAPHICS).colorAttachmentCount(1).pColorAttachments(colorRef)
            val deps = VkSubpassDependency.calloc(2, stack)
            deps[0].srcSubpass(VK_SUBPASS_EXTERNAL).dstSubpass(0)
                .srcStageMask(VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT).dstStageMask(VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT)
                .srcAccessMask(VK_ACCESS_SHADER_READ_BIT).dstAccessMask(VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT)
                .dependencyFlags(VK_DEPENDENCY_BY_REGION_BIT)
            deps[1].srcSubpass(0).dstSubpass(VK_SUBPASS_EXTERNAL)
                .srcStageMask(VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT).dstStageMask(VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT)
                .srcAccessMask(VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT).dstAccessMask(VK_ACCESS_SHADER_READ_BIT)
                .dependencyFlags(VK_DEPENDENCY_BY_REGION_BIT)
            val rpInfo = VkRenderPassCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_RENDER_PASS_CREATE_INFO).pAttachments(att).pSubpasses(subpass).pDependencies(deps)
            val p = stack.mallocLong(1)
            check(vkCreateRenderPass(device, rpInfo, null, p) == VK_SUCCESS) { "postRenderPass failed" }
            postRenderPass = p.get(0)
        }
        // compositeRenderPass: 1 LDR attachment (the read-back resolveImage), finalLayout TRANSFER_SRC
        // so the trailing vkCmdCopyImageToBuffer is the natural next step. Out-dependency lands on
        // the TRANSFER stage to order that copy.
        run {
            val att = VkAttachmentDescription.calloc(1, stack)
            att[0].format(colorFormat).samples(VK_SAMPLE_COUNT_1_BIT)
                .loadOp(VK_ATTACHMENT_LOAD_OP_DONT_CARE).storeOp(VK_ATTACHMENT_STORE_OP_STORE)
                .stencilLoadOp(VK_ATTACHMENT_LOAD_OP_DONT_CARE).stencilStoreOp(VK_ATTACHMENT_STORE_OP_DONT_CARE)
                .initialLayout(VK_IMAGE_LAYOUT_UNDEFINED).finalLayout(VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL)
            val colorRef = VkAttachmentReference.calloc(1, stack).attachment(0).layout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL)
            val subpass = VkSubpassDescription.calloc(1, stack).pipelineBindPoint(VK_PIPELINE_BIND_POINT_GRAPHICS).colorAttachmentCount(1).pColorAttachments(colorRef)
            val deps = VkSubpassDependency.calloc(2, stack)
            deps[0].srcSubpass(VK_SUBPASS_EXTERNAL).dstSubpass(0)
                .srcStageMask(VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT).dstStageMask(VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT)
                .srcAccessMask(VK_ACCESS_SHADER_READ_BIT).dstAccessMask(VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT)
                .dependencyFlags(VK_DEPENDENCY_BY_REGION_BIT)
            deps[1].srcSubpass(0).dstSubpass(VK_SUBPASS_EXTERNAL)
                .srcStageMask(VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT).dstStageMask(VK_PIPELINE_STAGE_TRANSFER_BIT)
                .srcAccessMask(VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT).dstAccessMask(VK_ACCESS_TRANSFER_READ_BIT)
                .dependencyFlags(VK_DEPENDENCY_BY_REGION_BIT)
            val rpInfo = VkRenderPassCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_RENDER_PASS_CREATE_INFO).pAttachments(att).pSubpasses(subpass).pDependencies(deps)
            val p = stack.mallocLong(1)
            check(vkCreateRenderPass(device, rpInfo, null, p) == VK_SUCCESS) { "compositeRenderPass failed" }
            compositeRenderPass = p.get(0)
        }
        // Descriptor layout (2 combined-image samplers, fragment stage) + pool for the 5 post sets.
        val bindings = VkDescriptorSetLayoutBinding.calloc(2, stack)
        for (i in 0..1) bindings[i].binding(i).descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1).stageFlags(VK_SHADER_STAGE_FRAGMENT_BIT)
        val layoutInfo = VkDescriptorSetLayoutCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO).pBindings(bindings)
        val pLayout = stack.mallocLong(1)
        check(vkCreateDescriptorSetLayout(device, layoutInfo, null, pLayout) == VK_SUCCESS); postSetLayout = pLayout.get(0)
        val poolSize = VkDescriptorPoolSize.calloc(1, stack).type(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(5 * 2)
        val poolInfo = VkDescriptorPoolCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO).pPoolSizes(poolSize).maxSets(5)
        val pPool = stack.mallocLong(1)
        check(vkCreateDescriptorPool(device, poolInfo, null, pPool) == VK_SUCCESS); postPool = pPool.get(0)

        val (brightLayout, brightPipe) = createFsqPipeline(stack, "brightFrag", BRIGHT_FRAG, postRenderPass)
        brightPipelineLayout = brightLayout; brightPipeline = brightPipe
        val (blurLayout, blurPipe) = createFsqPipeline(stack, "blurFrag", BLUR_FRAG, postRenderPass)
        blurPipelineLayout = blurLayout; blurPipeline = blurPipe
        val (compLayout, compPipe) = createFsqPipeline(stack, "compositeFrag", COMPOSITE_FRAG, compositeRenderPass)
        compositePipelineLayout = compLayout; compositePipeline = compPipe
    }

    /** Builds one fullscreen-triangle pipeline (FSQ_VERT + [fragSrc]) over [renderPass] using the
     *  shared [postSetLayout] + a 16-byte fragment push-constant range. Mirrors buildBrdfLut's setup. */
    private fun createFsqPipeline(stack: MemoryStack, fragName: String, fragSrc: String, renderPass: Long): Pair<Long, Long> {
        val vert = createShaderModule(stack, FSQ_VERT, Shaderc.shaderc_glsl_vertex_shader, fragName + "Vert")
        val frag = createShaderModule(stack, fragSrc, Shaderc.shaderc_glsl_fragment_shader, fragName)
        val stages = VkPipelineShaderStageCreateInfo.calloc(2, stack)
        stages[0].sType(VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO).stage(VK_SHADER_STAGE_VERTEX_BIT).module(vert).pName(stack.UTF8("main"))
        stages[1].sType(VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO).stage(VK_SHADER_STAGE_FRAGMENT_BIT).module(frag).pName(stack.UTF8("main"))
        val vertexInput = VkPipelineVertexInputStateCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO)
        val inputAssembly = VkPipelineInputAssemblyStateCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO).topology(VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST)
        val viewportState = VkPipelineViewportStateCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO).viewportCount(1).scissorCount(1)
        val raster = VkPipelineRasterizationStateCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO).polygonMode(VK_POLYGON_MODE_FILL).cullMode(VK_CULL_MODE_NONE).frontFace(VK_FRONT_FACE_COUNTER_CLOCKWISE).lineWidth(1f)
        val multisample = VkPipelineMultisampleStateCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO).rasterizationSamples(VK_SAMPLE_COUNT_1_BIT)
        val blendAttachment = VkPipelineColorBlendAttachmentState.calloc(1, stack).colorWriteMask(VK_COLOR_COMPONENT_R_BIT or VK_COLOR_COMPONENT_G_BIT or VK_COLOR_COMPONENT_B_BIT or VK_COLOR_COMPONENT_A_BIT).blendEnable(false)
        val blend = VkPipelineColorBlendStateCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO).pAttachments(blendAttachment)
        val dynamic = VkPipelineDynamicStateCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_PIPELINE_DYNAMIC_STATE_CREATE_INFO).pDynamicStates(stack.ints(VK_DYNAMIC_STATE_VIEWPORT, VK_DYNAMIC_STATE_SCISSOR))
        val pushRange = VkPushConstantRange.calloc(1, stack).stageFlags(VK_SHADER_STAGE_FRAGMENT_BIT).offset(0).size(16)
        val layoutInfo = VkPipelineLayoutCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO).pSetLayouts(stack.longs(postSetLayout)).pPushConstantRanges(pushRange)
        val pLayout = stack.mallocLong(1)
        check(vkCreatePipelineLayout(device, layoutInfo, null, pLayout) == VK_SUCCESS)
        val layout = pLayout.get(0)
        val pipelineInfo = VkGraphicsPipelineCreateInfo.calloc(1, stack).sType(VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO)
            .pStages(stages).pVertexInputState(vertexInput).pInputAssemblyState(inputAssembly).pViewportState(viewportState)
            .pRasterizationState(raster).pMultisampleState(multisample).pColorBlendState(blend).pDynamicState(dynamic)
            .layout(layout).renderPass(renderPass).subpass(0)
        val pPipe = stack.mallocLong(1)
        check(vkCreateGraphicsPipelines(device, VK_NULL_HANDLE, pipelineInfo, null, pPipe) == VK_SUCCESS) { "$fragName pipeline failed" }
        vkDestroyShaderModule(device, vert, null); vkDestroyShaderModule(device, frag, null)
        return layout to pPipe.get(0)
    }

    private fun createShaderModule(stack: MemoryStack, source: String, kind: Int, name: String): Long {
        val compiler = Shaderc.shaderc_compiler_initialize()
        val options = Shaderc.shaderc_compile_options_initialize()
        val result = Shaderc.shaderc_compile_into_spv(compiler, source, kind, name, "main", options)
        check(Shaderc.shaderc_result_get_compilation_status(result) == Shaderc.shaderc_compilation_status_success) {
            "shader compile failed: " + Shaderc.shaderc_result_get_error_message(result)
        }
        val spv = Shaderc.shaderc_result_get_bytes(result)!!
        val info = VkShaderModuleCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO).pCode(spv)
        val p = stack.mallocLong(1)
        check(vkCreateShaderModule(device, info, null, p) == VK_SUCCESS)
        Shaderc.shaderc_result_release(result); Shaderc.shaderc_compile_options_release(options); Shaderc.shaderc_compiler_release(compiler)
        return p.get(0)
    }

    private fun uploadAllTextures() {
        for (tex in ChessTexture.entries) {
            val set = materialSets[tex] ?: continue
            textures[tex] = uploadTexture(set)
        }
    }

    private fun uploadTexture(matSet: ChessMaterialSet): Texture {
        val (albedoImage, albedoMem, albedoView) = upload2dImage(matSet.albedo, VK_FORMAT_R8G8B8A8_UNORM)
        val (mrImage, mrMem, mrView) = upload2dImage(matSet.metallicRoughness, VK_FORMAT_R8G8B8A8_UNORM)
        // Materials without a normal map (whites/blacks) bind the 1×1 flat default so the same
        // perturbNormal() path produces the unmodified geometric normal.
        val (normalImage, normalMem, normalView) = if (matSet.normal != null) {
            upload2dImage(matSet.normal, VK_FORMAT_R8G8B8A8_UNORM)
        } else {
            Triple(VK_NULL_HANDLE, VK_NULL_HANDLE, defaultNormalView)
        }
        val descriptorSet = MemoryStack.stackPush().use { stack ->
            val alloc = VkDescriptorSetAllocateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO).descriptorPool(descriptorPool).pSetLayouts(stack.longs(descriptorSetLayout))
            val pSet = stack.mallocLong(1)
            check(vkAllocateDescriptorSets(device, alloc, pSet) == VK_SUCCESS)
            val ds = pSet.get(0)
            val matInfo = VkDescriptorImageInfo.calloc(1, stack).imageLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL).imageView(albedoView).sampler(sampler)
            val mrInfo = VkDescriptorImageInfo.calloc(1, stack).imageLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL).imageView(mrView).sampler(sampler)
            val normalInfo = VkDescriptorImageInfo.calloc(1, stack).imageLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL).imageView(normalView).sampler(sampler)
            val shadowInfo = VkDescriptorImageInfo.calloc(1, stack).imageLayout(VK_IMAGE_LAYOUT_DEPTH_STENCIL_READ_ONLY_OPTIMAL).imageView(shadowView).sampler(shadowSampler)
            val envInfo = VkDescriptorImageInfo.calloc(1, stack).imageLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL).imageView(skybox.view).sampler(cubeSampler)
            val irradianceInfo = VkDescriptorImageInfo.calloc(1, stack).imageLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL).imageView(irradianceView).sampler(cubeSampler)
            // Prefiltered env cube = the IBL cube itself (papermill_ibl.ktx ships prefiltered).
            val prefilterInfo = VkDescriptorImageInfo.calloc(1, stack).imageLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL).imageView(ibl.view).sampler(cubeSampler)
            val brdfInfo = VkDescriptorImageInfo.calloc(1, stack).imageLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL).imageView(brdfLutView).sampler(brdfLutSampler)
            val uboBufInfo = VkDescriptorBufferInfo.calloc(1, stack).buffer(uboBuffer).offset(0).range(208L)
            val uboParamsBufInfo = VkDescriptorBufferInfo.calloc(1, stack).buffer(uboParamsBuffer).offset(0).range(32L)
            val writes = VkWriteDescriptorSet.calloc(10, stack)
            writes[0].sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET).dstSet(ds).dstBinding(0).descriptorType(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER).descriptorCount(1).pBufferInfo(uboBufInfo)
            writes[1].sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET).dstSet(ds).dstBinding(1).descriptorType(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER).descriptorCount(1).pBufferInfo(uboParamsBufInfo)
            writes[2].sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET).dstSet(ds).dstBinding(2).descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1).pImageInfo(shadowInfo)
            writes[3].sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET).dstSet(ds).dstBinding(3).descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1).pImageInfo(envInfo)
            writes[4].sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET).dstSet(ds).dstBinding(4).descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1).pImageInfo(irradianceInfo)
            writes[5].sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET).dstSet(ds).dstBinding(5).descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1).pImageInfo(prefilterInfo)
            writes[6].sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET).dstSet(ds).dstBinding(6).descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1).pImageInfo(brdfInfo)
            writes[7].sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET).dstSet(ds).dstBinding(7).descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1).pImageInfo(matInfo)
            writes[8].sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET).dstSet(ds).dstBinding(8).descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1).pImageInfo(mrInfo)
            writes[9].sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET).dstSet(ds).dstBinding(9).descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1).pImageInfo(normalInfo)
            vkUpdateDescriptorSets(device, writes, null)
            ds
        }
        return Texture(
            image = albedoImage, mem = albedoMem, view = albedoView,
            mrImage = mrImage, mrMem = mrMem, mrView = mrView,
            normalView = normalView,
            descriptorSet = descriptorSet,
        )
    }

    /**
     * Uploads a single 2D RGBA image with a generated mip chain: stages mip 0 from host memory,
     * then uses vkCmdBlitImage to halve it down to 1×1. Returns (image, mem, view-over-all-mips).
     * The blit-with-mipmap-filter path is the standard way to generate mips at runtime; it requires
     * the image to be created with TRANSFER_SRC | TRANSFER_DST | SAMPLED usage and a real mip chain.
     */
    private fun upload2dImage(img: TextureImage, format: Int): Triple<Long, Long, Long> {
        val mipLevels = mipCount(img.width, img.height)
        val (image, mem) = createImageWithMips(img.width, img.height, mipLevels, format,
            VK_IMAGE_USAGE_TRANSFER_DST_BIT or VK_IMAGE_USAGE_TRANSFER_SRC_BIT or VK_IMAGE_USAGE_SAMPLED_BIT)
        val staging = createBuffer(img.rgba.size.toLong(), VK_BUFFER_USAGE_TRANSFER_SRC_BIT, hostVisible)
        writeBytes(staging.second, img.rgba)
        singleTimeCommands { cmd ->
            MemoryStack.stackPush().use { stack ->
                // Transition mip 0 UNDEFINED → TRANSFER_DST, then copy the staging buffer into it.
                transitionMip(cmd, stack, image, oldLayout = VK_IMAGE_LAYOUT_UNDEFINED,
                    newLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, mip = 0, mipLevels = 1,
                    srcAccess = 0, dstAccess = VK_ACCESS_TRANSFER_WRITE_BIT,
                    srcStage = VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, dstStage = VK_PIPELINE_STAGE_TRANSFER_BIT)
                val region = VkBufferImageCopy.calloc(1, stack)
                region.bufferOffset(0).bufferRowLength(0).bufferImageHeight(0)
                region.imageSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1)
                region.imageOffset().set(0, 0, 0); region.imageExtent().set(img.width, img.height, 1)
                vkCmdCopyBufferToImage(cmd, staging.first, image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, region)

                // Transition mip 0 → TRANSFER_SRC so we can blit it down to mip 1.
                transitionMip(cmd, stack, image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                    VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, mip = 0, mipLevels = 1,
                    srcAccess = VK_ACCESS_TRANSFER_WRITE_BIT, dstAccess = VK_ACCESS_TRANSFER_READ_BIT,
                    srcStage = VK_PIPELINE_STAGE_TRANSFER_BIT, dstStage = VK_PIPELINE_STAGE_TRANSFER_BIT)

                // Blit mip (i-1) → mip i, halving dimensions, with a linear+mip filter for the downsample.
                for (mip in 1 until mipLevels) {
                    val srcW = (img.width shr (mip - 1)).coerceAtLeast(1)
                    val srcH = (img.height shr (mip - 1)).coerceAtLeast(1)
                    val dstW = (img.width shr mip).coerceAtLeast(1)
                    val dstH = (img.height shr mip).coerceAtLeast(1)
                    val blit = VkImageBlit.calloc(1, stack)
                    blit.srcSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(mip - 1).baseArrayLayer(0).layerCount(1)
                    blit.srcOffsets(0).set(0, 0, 0); blit.srcOffsets(1).set(srcW, srcH, 1)
                    blit.dstSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(mip).baseArrayLayer(0).layerCount(1)
                    blit.dstOffsets(0).set(0, 0, 0); blit.dstOffsets(1).set(dstW, dstH, 1)
                    // mip i must be in TRANSFER_DST before the blit writes it.
                    transitionMip(cmd, stack, image, VK_IMAGE_LAYOUT_UNDEFINED,
                        VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, mip = mip, mipLevels = 1,
                        srcAccess = 0, dstAccess = VK_ACCESS_TRANSFER_WRITE_BIT,
                        srcStage = VK_PIPELINE_STAGE_TRANSFER_BIT, dstStage = VK_PIPELINE_STAGE_TRANSFER_BIT)
                    vkCmdBlitImage(cmd, image, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                        image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, blit, VK_FILTER_LINEAR)
                    // Then transition mip i to TRANSFER_SRC for the next iteration (or final SHADER_READ_ONLY).
                    transitionMip(cmd, stack, image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                        VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, mip = mip, mipLevels = 1,
                        srcAccess = VK_ACCESS_TRANSFER_WRITE_BIT, dstAccess = VK_ACCESS_TRANSFER_READ_BIT,
                        srcStage = VK_PIPELINE_STAGE_TRANSFER_BIT, dstStage = VK_PIPELINE_STAGE_TRANSFER_BIT)
                }

                // Transition all mips to SHADER_READ_ONLY in one barrier.
                transitionMip(cmd, stack, image, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                    VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, mip = 0, mipLevels = mipLevels,
                    srcAccess = VK_ACCESS_TRANSFER_READ_BIT, dstAccess = VK_ACCESS_SHADER_READ_BIT,
                    srcStage = VK_PIPELINE_STAGE_TRANSFER_BIT, dstStage = VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT)
            }
        }
        destroyBuffer(staging.first, staging.second)
        val view = createImageView(image, format, VK_IMAGE_ASPECT_COLOR_BIT, mipLevels = mipLevels)
        return Triple(image, mem, view)
    }

    /** Floor(log2(max(w,h))) + 1 — the standard mip-chain depth for a 2D texture of the given size. */
    private fun mipCount(w: Int, h: Int): Int {
        var max = maxOf(w, h)
        var levels = 1
        while (max > 1) { max = max shr 1; levels++ }
        return levels
    }

    /** [createImage] variant that creates a 2D image with [mipLevels] mip levels (no cube compat). */
    private fun createImageWithMips(w: Int, h: Int, mipLevels: Int, format: Int, usage: Int): Pair<Long, Long> = MemoryStack.stackPush().use { stack ->
        val info = VkImageCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO)
            .imageType(VK_IMAGE_TYPE_2D).format(format).mipLevels(mipLevels).arrayLayers(1)
            .samples(VK_SAMPLE_COUNT_1_BIT).tiling(VK_IMAGE_TILING_OPTIMAL).usage(usage)
            .sharingMode(VK_SHARING_MODE_EXCLUSIVE).initialLayout(VK_IMAGE_LAYOUT_UNDEFINED)
        info.extent().set(w, h, 1)
        val pImage = stack.mallocLong(1)
        check(vkCreateImage(device, info, null, pImage) == VK_SUCCESS)
        val req = VkMemoryRequirements.calloc(stack); vkGetImageMemoryRequirements(device, pImage.get(0), req)
        val alloc = VkMemoryAllocateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO).allocationSize(req.size())
            .memoryTypeIndex(findMemoryType(stack, req.memoryTypeBits(), VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT))
        val pMem = stack.mallocLong(1)
        check(vkAllocateMemory(device, alloc, null, pMem) == VK_SUCCESS)
        vkBindImageMemory(device, pImage.get(0), pMem.get(0), 0)
        pImage.get(0) to pMem.get(0)
    }

    /** [transition] variant that can target a single mip level (or all of them, when mipLevels > 1). */
    private fun transitionMip(cmd: VkCommandBuffer, stack: MemoryStack, image: Long, oldLayout: Int, newLayout: Int,
                              mip: Int, mipLevels: Int, srcAccess: Int, dstAccess: Int, srcStage: Int, dstStage: Int) {
        val barrier = VkImageMemoryBarrier.calloc(1, stack).sType(VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER)
            .oldLayout(oldLayout).newLayout(newLayout).srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
            .image(image).srcAccessMask(srcAccess).dstAccessMask(dstAccess)
        barrier.subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).baseMipLevel(mip).levelCount(mipLevels).baseArrayLayer(0).layerCount(1)
        vkCmdPipelineBarrier(cmd, srcStage, dstStage, 0, null, null, barrier)
    }

    private fun transition(cmd: VkCommandBuffer, stack: MemoryStack, image: Long, oldLayout: Int, newLayout: Int, srcAccess: Int, dstAccess: Int, srcStage: Int, dstStage: Int) {
        val barrier = VkImageMemoryBarrier.calloc(1, stack).sType(VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER)
            .oldLayout(oldLayout).newLayout(newLayout).srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
            .image(image).srcAccessMask(srcAccess).dstAccessMask(dstAccess)
        barrier.subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1)
        vkCmdPipelineBarrier(cmd, srcStage, dstStage, 0, null, null, barrier)
    }

    private fun singleTimeCommands(record: (VkCommandBuffer) -> Unit) = MemoryStack.stackPush().use { stack ->
        val alloc = VkCommandBufferAllocateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO).commandPool(commandPool).level(VK_COMMAND_BUFFER_LEVEL_PRIMARY).commandBufferCount(1)
        val pCb = stack.mallocPointer(1)
        check(vkAllocateCommandBuffers(device, alloc, pCb) == VK_SUCCESS)
        val cmd = VkCommandBuffer(pCb.get(0), device)
        val begin = VkCommandBufferBeginInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO).flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT)
        vkBeginCommandBuffer(cmd, begin)
        record(cmd)
        vkEndCommandBuffer(cmd)
        val submit = VkSubmitInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_SUBMIT_INFO).pCommandBuffers(stack.pointers(cmd))
        vkQueueSubmit(queue, submit, VK_NULL_HANDLE)
        vkQueueWaitIdle(queue)
        vkFreeCommandBuffers(device, commandPool, cmd)
    }

    /**
     * SSAA supersampling factor — the offscreen render target is allocated at `scale ×` the surface's
     * display dimensions and the resulting larger bitmap is downscaled by Compose when drawn. This is
     * "free" edge AA on top of MSAA, costing only pixel bandwidth (4× for scale=2). The scale is
     * configurable via `CHESS_DESKTOP_SSAA` so it can be tuned (1 = off, 2 = default, etc.).
     */
    private val ssaaScale: Int = runCatching {
        System.getenv("CHESS_DESKTOP_SSAA")?.trim()?.toIntOrNull()
    }.getOrNull()?.coerceIn(1, 4) ?: 2

    private fun ensureTargets(w: Int, h: Int) {
        // Render target dimensions = display surface × ssaaScale. width/height store the SCALED
        // dimensions because everything downstream (viewport, scissor, readback, aspect math) keys
        // off them; aspect is invariant under uniform scaling so the camera math is unaffected.
        val rw = (w * ssaaScale).coerceAtLeast(1)
        val rh = (h * ssaaScale).coerceAtLeast(1)
        if (rw == width && rh == height && framebuffer != VK_NULL_HANDLE) return
        destroyTargets()
        width = rw; height = rh
        // Scene MSAA color target is HDR (RGBA16F) — the scene pass writes linear HDR radiance now,
        // resolved into sceneHdr for the post chain to sample. TRANSFER_SRC stays off (not read back).
        val (ci, cm) = createImage(rw, rh, hdrFormat, VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT, samples)
        colorImage = ci; colorMem = cm; colorView = createImageView(colorImage, hdrFormat, VK_IMAGE_ASPECT_COLOR_BIT)
        val (di, dm) = createImage(rw, rh, depthFormat, VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT, samples)
        depthImage = di; depthMem = dm; depthView = createImageView(depthImage, depthFormat, VK_IMAGE_ASPECT_DEPTH_BIT)
        // HDR resolve of the scene pass (sampled by bright + composite).
        val (si, sm) = createImage(rw, rh, hdrFormat, VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT or VK_IMAGE_USAGE_SAMPLED_BIT, VK_SAMPLE_COUNT_1_BIT)
        sceneHdrImage = si; sceneHdrMem = sm; sceneHdrView = createImageView(sceneHdrImage, hdrFormat, VK_IMAGE_ASPECT_COLOR_BIT)
        // resolveImage is now the composite's LDR output (the read-back target), no longer an MSAA resolve.
        val (ri, rm) = createImage(rw, rh, colorFormat, VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT or VK_IMAGE_USAGE_TRANSFER_SRC_BIT, VK_SAMPLE_COUNT_1_BIT)
        resolveImage = ri; resolveMem = rm; resolveView = createImageView(resolveImage, colorFormat, VK_IMAGE_ASPECT_COLOR_BIT)
        // Half-res HDR ping-pong bloom targets.
        bloomW = (rw / 2).coerceAtLeast(1); bloomH = (rh / 2).coerceAtLeast(1)
        val bloomUsage = VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT or VK_IMAGE_USAGE_SAMPLED_BIT
        val (bri, brm) = createImage(bloomW, bloomH, hdrFormat, bloomUsage)
        bloomBright = bri; bloomBrightMem = brm; bloomBrightView = createImageView(bloomBright, hdrFormat, VK_IMAGE_ASPECT_COLOR_BIT)
        val (ai, am) = createImage(bloomW, bloomH, hdrFormat, bloomUsage)
        bloomA = ai; bloomAMem = am; bloomAView = createImageView(bloomA, hdrFormat, VK_IMAGE_ASPECT_COLOR_BIT)
        val (bi, bm) = createImage(bloomW, bloomH, hdrFormat, bloomUsage)
        bloomB = bi; bloomBMem = bm; bloomBView = createImageView(bloomB, hdrFormat, VK_IMAGE_ASPECT_COLOR_BIT)
        MemoryStack.stackPush().use { stack ->
            val sceneFbInfo = VkFramebufferCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO).renderPass(renderPass).pAttachments(stack.longs(colorView, depthView, sceneHdrView)).width(rw).height(rh).layers(1)
            val p = stack.mallocLong(1)
            check(vkCreateFramebuffer(device, sceneFbInfo, null, p) == VK_SUCCESS); framebuffer = p.get(0)
            val brightFbInfo = VkFramebufferCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO).renderPass(postRenderPass).pAttachments(stack.longs(bloomBrightView)).width(bloomW).height(bloomH).layers(1)
            check(vkCreateFramebuffer(device, brightFbInfo, null, p) == VK_SUCCESS); brightFb = p.get(0)
            val aFbInfo = VkFramebufferCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO).renderPass(postRenderPass).pAttachments(stack.longs(bloomAView)).width(bloomW).height(bloomH).layers(1)
            check(vkCreateFramebuffer(device, aFbInfo, null, p) == VK_SUCCESS); aFb = p.get(0)
            val bFbInfo = VkFramebufferCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO).renderPass(postRenderPass).pAttachments(stack.longs(bloomBView)).width(bloomW).height(bloomH).layers(1)
            check(vkCreateFramebuffer(device, bFbInfo, null, p) == VK_SUCCESS); bFb = p.get(0)
            val compFbInfo = VkFramebufferCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO).renderPass(compositeRenderPass).pAttachments(stack.longs(resolveView)).width(rw).height(rh).layers(1)
            check(vkCreateFramebuffer(device, compFbInfo, null, p) == VK_SUCCESS); compositeFb = p.get(0)

            // (Re)allocate the 5 post descriptor sets and wire them to the post targets via the shared
            // linear/clamp brdfLutSampler (perfect for these single-mip targets). Resetting the pool
            // frees the previous resize's sets in one call.
            vkResetDescriptorPool(device, postPool, 0)
            val counts = stack.ints(1, 1, 1, 1, 1)
            val setLayouts = stack.longs(postSetLayout, postSetLayout, postSetLayout, postSetLayout, postSetLayout)
            val allocInfo = VkDescriptorSetAllocateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO).descriptorPool(postPool).pSetLayouts(setLayouts)
            val pSets = stack.mallocLong(5)
            check(vkAllocateDescriptorSets(device, allocInfo, pSets) == VK_SUCCESS)
            dsSceneHdr = pSets.get(0); dsBright = pSets.get(1); dsA = pSets.get(2); dsB = pSets.get(3); dsComposite = pSets.get(4)
            writePostDescriptor(stack, dsSceneHdr, sceneHdrView, sceneHdrView)
            writePostDescriptor(stack, dsBright, bloomBrightView, bloomBrightView)
            writePostDescriptor(stack, dsA, bloomAView, bloomAView)
            writePostDescriptor(stack, dsB, bloomBView, bloomBView)
            writePostDescriptor(stack, dsComposite, sceneHdrView, bloomBView)
        }
        readbackSize = (rw.toLong() * rh * 4)
        val (rb, rbm) = createBuffer(readbackSize, VK_BUFFER_USAGE_TRANSFER_DST_BIT, hostVisible)
        readbackBuffer = rb; readbackMem = rbm
    }

    /** Writes the two COMBINED_IMAGE_SAMPLER bindings of a post descriptor set (binding 0 = [view0],
     *  binding 1 = [view1]) against the shared brdfLutSampler. */
    private fun writePostDescriptor(stack: MemoryStack, set: Long, view0: Long, view1: Long) {
        val info0 = VkDescriptorImageInfo.calloc(1, stack).imageLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL).imageView(view0).sampler(brdfLutSampler)
        val info1 = VkDescriptorImageInfo.calloc(1, stack).imageLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL).imageView(view1).sampler(brdfLutSampler)
        val writes = VkWriteDescriptorSet.calloc(2, stack)
        writes[0].sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET).dstSet(set).dstBinding(0).descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1).pImageInfo(info0)
        writes[1].sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET).dstSet(set).dstBinding(1).descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1).pImageInfo(info1)
        vkUpdateDescriptorSets(device, writes, null)
    }

    private fun uploadGroup(tex: ChessTexture, group: SceneGroup) {
        val gb = groupBuffers.getOrPut(tex) { GroupBuffers() }
        gb.indexCount = group.indexCount
        if (group.vertices.isEmpty() || group.indices.isEmpty()) { gb.indexCount = 0; return }
        val vBytes = group.vertices.size.toLong() * 4
        val iBytes = group.indices.size.toLong() * 4
        if (vBytes > gb.vCap) { destroyBuffer(gb.vBuf, gb.vMem); val (b, m) = createBuffer(vBytes, VK_BUFFER_USAGE_VERTEX_BUFFER_BIT, hostVisible); gb.vBuf = b; gb.vMem = m; gb.vCap = vBytes }
        if (iBytes > gb.iCap) { destroyBuffer(gb.iBuf, gb.iMem); val (b, m) = createBuffer(iBytes, VK_BUFFER_USAGE_INDEX_BUFFER_BIT, hostVisible); gb.iBuf = b; gb.iMem = m; gb.iCap = iBytes }
        writeFloats(gb.vMem, group.vertices); writeInts(gb.iMem, group.indices)
        // Tangent stream (parallel to vertices, 4 floats per vertex). Empty when no source tangents
        // existed (rare — only happens for groups built entirely without addMesh, which always emits
        // a flat fallback tangent, so this is just defensive).
        if (group.tangents.isNotEmpty()) {
            val tBytes = group.tangents.size.toLong() * 4
            if (tBytes > gb.tCap) { destroyBuffer(gb.tBuf, gb.tMem); val (b, m) = createBuffer(tBytes, VK_BUFFER_USAGE_VERTEX_BUFFER_BIT, hostVisible); gb.tBuf = b; gb.tMem = m; gb.tCap = tBytes }
            writeFloats(gb.tMem, group.tangents)
        }
    }

    private fun writeFloats(mem: Long, data: FloatArray) = MemoryStack.stackPush().use { stack ->
        val pp = stack.mallocPointer(1); vkMapMemory(device, mem, 0, data.size.toLong() * 4, 0, pp); pp.getByteBuffer(0, data.size * 4).asFloatBuffer().put(data); vkUnmapMemory(device, mem)
    }
    private fun writeInts(mem: Long, data: IntArray) = MemoryStack.stackPush().use { stack ->
        val pp = stack.mallocPointer(1); vkMapMemory(device, mem, 0, data.size.toLong() * 4, 0, pp); pp.getByteBuffer(0, data.size * 4).asIntBuffer().put(data); vkUnmapMemory(device, mem)
    }
    private fun writeBytes(mem: Long, data: ByteArray) = MemoryStack.stackPush().use { stack ->
        val pp = stack.mallocPointer(1); vkMapMemory(device, mem, 0, data.size.toLong(), 0, pp); pp.getByteBuffer(0, data.size).put(data); vkUnmapMemory(device, mem)
    }

    private fun createImage(w: Int, h: Int, format: Int, usage: Int, samplesCount: Int = VK_SAMPLE_COUNT_1_BIT): Pair<Long, Long> = MemoryStack.stackPush().use { stack ->
        val info = VkImageCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO).imageType(VK_IMAGE_TYPE_2D).format(format).mipLevels(1).arrayLayers(1).samples(samplesCount).tiling(VK_IMAGE_TILING_OPTIMAL).usage(usage).sharingMode(VK_SHARING_MODE_EXCLUSIVE).initialLayout(VK_IMAGE_LAYOUT_UNDEFINED)
        info.extent().set(w, h, 1)
        val pImage = stack.mallocLong(1)
        check(vkCreateImage(device, info, null, pImage) == VK_SUCCESS)
        val req = VkMemoryRequirements.calloc(stack); vkGetImageMemoryRequirements(device, pImage.get(0), req)
        val alloc = VkMemoryAllocateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO).allocationSize(req.size()).memoryTypeIndex(findMemoryType(stack, req.memoryTypeBits(), VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT))
        val pMem = stack.mallocLong(1)
        check(vkAllocateMemory(device, alloc, null, pMem) == VK_SUCCESS)
        vkBindImageMemory(device, pImage.get(0), pMem.get(0), 0)
        pImage.get(0) to pMem.get(0)
    }

    private fun createImageView(image: Long, format: Int, aspect: Int, viewType: Int = VK_IMAGE_VIEW_TYPE_2D, layerCount: Int = 1, mipLevels: Int = 1): Long = MemoryStack.stackPush().use { stack ->
        val info = VkImageViewCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO).image(image).viewType(viewType).format(format)
        info.subresourceRange().aspectMask(aspect).baseMipLevel(0).levelCount(mipLevels).baseArrayLayer(0).layerCount(layerCount)
        val p = stack.mallocLong(1)
        check(vkCreateImageView(device, info, null, p) == VK_SUCCESS); p.get(0)
    }

    /**
     * Uploads a KTX cube env (papermill skybox / IBL) into a GPU cube image + shader-readable view.
     * Takes the Vulkan [format] explicitly because the same code path handles RGBA16F (HDR raw env)
     * and R11F_G11F_B10F (the Filament-processed skybox/IBL files Android uses).
     */
    private fun uploadKtxCube(ktx: KtxLoader.KtxImage, format: Int): CubeTexture {
        val (stgBuf, stgMem) = createBuffer(ktx.totalSize.toLong(), VK_BUFFER_USAGE_TRANSFER_SRC_BIT, hostVisible)
        MemoryStack.stackPush().use { stack ->
            val pp = stack.mallocPointer(1); vkMapMemory(device, stgMem, 0, ktx.totalSize.toLong(), 0, pp)
            pp.getByteBuffer(0, ktx.totalSize).put(ktx.data); vkUnmapMemory(device, stgMem)
        }

        var image = VK_NULL_HANDLE; var mem = VK_NULL_HANDLE
        MemoryStack.stackPush().use { stack ->
            val info = VkImageCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO).imageType(VK_IMAGE_TYPE_2D).format(format)
                .mipLevels(ktx.mipLevels).arrayLayers(ktx.faces).samples(VK_SAMPLE_COUNT_1_BIT).tiling(VK_IMAGE_TILING_OPTIMAL)
                .usage(VK_IMAGE_USAGE_SAMPLED_BIT or VK_IMAGE_USAGE_TRANSFER_DST_BIT).sharingMode(VK_SHARING_MODE_EXCLUSIVE)
                .initialLayout(VK_IMAGE_LAYOUT_UNDEFINED).flags(VK_IMAGE_CREATE_CUBE_COMPATIBLE_BIT)
            info.extent().set(ktx.width, ktx.height, 1)
            val pImage = stack.mallocLong(1)
            check(vkCreateImage(device, info, null, pImage) == VK_SUCCESS)
            val req = VkMemoryRequirements.calloc(stack); vkGetImageMemoryRequirements(device, pImage.get(0), req)
            val alloc = VkMemoryAllocateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO).allocationSize(req.size())
                .memoryTypeIndex(findMemoryType(stack, req.memoryTypeBits(), VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT))
            val pMem = stack.mallocLong(1)
            check(vkAllocateMemory(device, alloc, null, pMem) == VK_SUCCESS)
            vkBindImageMemory(device, pImage.get(0), pMem.get(0), 0)
            image = pImage.get(0); mem = pMem.get(0)
        }

        singleTimeCommands { cmd ->
            val stack = MemoryStack.stackGet()
            val barrier = VkImageMemoryBarrier.calloc(1, stack).sType(VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER)
                .oldLayout(VK_IMAGE_LAYOUT_UNDEFINED).newLayout(VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL)
                .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                .image(image).srcAccessMask(0).dstAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT)
            barrier.subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).baseMipLevel(0).levelCount(ktx.mipLevels).baseArrayLayer(0).layerCount(ktx.faces)
            vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, 0, null, null, barrier)

            val regions = VkBufferImageCopy.calloc(ktx.mipLevels, stack)
            for (m in 0 until ktx.mipLevels) {
                regions[m].bufferOffset(ktx.mipOffsets[m].toLong()).bufferRowLength(0).bufferImageHeight(0)
                regions[m].imageSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(m).baseArrayLayer(0).layerCount(ktx.faces)
                regions[m].imageOffset().set(0, 0, 0)
                regions[m].imageExtent().set((ktx.width shr m).coerceAtLeast(1), (ktx.height shr m).coerceAtLeast(1), 1)
            }
            vkCmdCopyBufferToImage(cmd, stgBuf, image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, regions)

            barrier.oldLayout(VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL).newLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL)
                .srcAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT).dstAccessMask(VK_ACCESS_SHADER_READ_BIT)
            vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT, 0, null, null, barrier)
        }
        destroyBuffer(stgBuf, stgMem)
        val view = createImageView(image, format, VK_IMAGE_ASPECT_COLOR_BIT, VK_IMAGE_VIEW_TYPE_CUBE, ktx.faces, ktx.mipLevels)
        return CubeTexture(image, mem, view, ktx.mipLevels)
    }

    private fun createBuffer(size: Long, usage: Int, memProps: Int): Pair<Long, Long> = MemoryStack.stackPush().use { stack ->
        val info = VkBufferCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO).size(size).usage(usage).sharingMode(VK_SHARING_MODE_EXCLUSIVE)
        val pBuf = stack.mallocLong(1)
        check(vkCreateBuffer(device, info, null, pBuf) == VK_SUCCESS)
        val req = VkMemoryRequirements.calloc(stack); vkGetBufferMemoryRequirements(device, pBuf.get(0), req)
        val alloc = VkMemoryAllocateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO).allocationSize(req.size()).memoryTypeIndex(findMemoryType(stack, req.memoryTypeBits(), memProps))
        val pMem = stack.mallocLong(1)
        check(vkAllocateMemory(device, alloc, null, pMem) == VK_SUCCESS)
        vkBindBufferMemory(device, pBuf.get(0), pMem.get(0), 0)
        pBuf.get(0) to pMem.get(0)
    }

    private fun findMemoryType(stack: MemoryStack, typeBits: Int, props: Int): Int {
        val memProps = VkPhysicalDeviceMemoryProperties.calloc(stack); vkGetPhysicalDeviceMemoryProperties(physicalDevice, memProps)
        for (i in 0 until memProps.memoryTypeCount()) if (typeBits and (1 shl i) != 0 && memProps.memoryTypes(i).propertyFlags() and props == props) return i
        error("no suitable memory type")
    }

    private fun destroyTargets() {
        // Post framebuffers first (they reference the views freed below). Descriptor sets are freed
        // implicitly by resetting the pool on next allocate, so just null them here.
        for (fb in longArrayOf(framebuffer, brightFb, aFb, bFb, compositeFb)) {
            if (fb != VK_NULL_HANDLE) vkDestroyFramebuffer(device, fb, null)
        }
        framebuffer = VK_NULL_HANDLE; brightFb = VK_NULL_HANDLE; aFb = VK_NULL_HANDLE; bFb = VK_NULL_HANDLE; compositeFb = VK_NULL_HANDLE
        dsSceneHdr = VK_NULL_HANDLE; dsBright = VK_NULL_HANDLE; dsA = VK_NULL_HANDLE; dsB = VK_NULL_HANDLE; dsComposite = VK_NULL_HANDLE
        for (v in longArrayOf(sceneHdrView, bloomBrightView, bloomAView, bloomBView, colorView, depthView, resolveView)) {
            if (v != VK_NULL_HANDLE) vkDestroyImageView(device, v, null)
        }
        sceneHdrView = VK_NULL_HANDLE; bloomBrightView = VK_NULL_HANDLE; bloomAView = VK_NULL_HANDLE; bloomBView = VK_NULL_HANDLE
        colorView = VK_NULL_HANDLE; depthView = VK_NULL_HANDLE; resolveView = VK_NULL_HANDLE
        for (img in longArrayOf(colorImage, depthImage, sceneHdrImage, resolveImage, bloomBright, bloomA, bloomB)) {
            if (img != VK_NULL_HANDLE) vkDestroyImage(device, img, null)
        }
        colorImage = VK_NULL_HANDLE; depthImage = VK_NULL_HANDLE; sceneHdrImage = VK_NULL_HANDLE
        resolveImage = VK_NULL_HANDLE; bloomBright = VK_NULL_HANDLE; bloomA = VK_NULL_HANDLE; bloomB = VK_NULL_HANDLE
        for (mem in longArrayOf(colorMem, depthMem, sceneHdrMem, resolveMem, bloomBrightMem, bloomAMem, bloomBMem)) {
            if (mem != VK_NULL_HANDLE) vkFreeMemory(device, mem, null)
        }
        colorMem = VK_NULL_HANDLE; depthMem = VK_NULL_HANDLE; sceneHdrMem = VK_NULL_HANDLE
        resolveMem = VK_NULL_HANDLE; bloomBrightMem = VK_NULL_HANDLE; bloomAMem = VK_NULL_HANDLE; bloomBMem = VK_NULL_HANDLE
        if (readbackBuffer != VK_NULL_HANDLE) vkDestroyBuffer(device, readbackBuffer, null); readbackBuffer = VK_NULL_HANDLE
        if (readbackMem != VK_NULL_HANDLE) vkFreeMemory(device, readbackMem, null); readbackMem = VK_NULL_HANDLE
    }

    private fun destroyBuffer(buf: Long, mem: Long) {
        if (buf != VK_NULL_HANDLE) vkDestroyBuffer(device, buf, null)
        if (mem != VK_NULL_HANDLE) vkFreeMemory(device, mem, null)
    }

    private fun destroyVulkan() {
        if (!::device.isInitialized) return
        vkDeviceWaitIdle(device)
        destroyTargets()
        for (gb in groupBuffers.values) { destroyBuffer(gb.vBuf, gb.vMem); destroyBuffer(gb.iBuf, gb.iMem); destroyBuffer(gb.tBuf, gb.tMem) }
        for (t in textures.values) {
            if (t.view != VK_NULL_HANDLE) vkDestroyImageView(device, t.view, null)
            if (t.image != VK_NULL_HANDLE) vkDestroyImage(device, t.image, null)
            if (t.mem != VK_NULL_HANDLE) vkFreeMemory(device, t.mem, null)
            if (t.mrView != VK_NULL_HANDLE) vkDestroyImageView(device, t.mrView, null)
            if (t.mrImage != VK_NULL_HANDLE) vkDestroyImage(device, t.mrImage, null)
            if (t.mrMem != VK_NULL_HANDLE) vkFreeMemory(device, t.mrMem, null)
            // Normal view may point at defaultNormalView (no per-material normal); only destroy
            // the per-material image when the material actually had a normal texture of its own.
            if (t.normalView != VK_NULL_HANDLE && t.normalView != defaultNormalView) {
                vkDestroyImageView(device, t.normalView, null)
            }
        }
        if (defaultNormalView != VK_NULL_HANDLE) vkDestroyImageView(device, defaultNormalView, null)
        if (defaultNormalImage != VK_NULL_HANDLE) vkDestroyImage(device, defaultNormalImage, null)
        if (defaultNormalMem != VK_NULL_HANDLE) vkFreeMemory(device, defaultNormalMem, null)
        if (sampler != VK_NULL_HANDLE) vkDestroySampler(device, sampler, null)
        if (cubeSampler != VK_NULL_HANDLE) vkDestroySampler(device, cubeSampler, null)
        if (brdfLutSampler != VK_NULL_HANDLE) vkDestroySampler(device, brdfLutSampler, null)
        if (descriptorPool != VK_NULL_HANDLE) vkDestroyDescriptorPool(device, descriptorPool, null)
        if (descriptorSetLayout != VK_NULL_HANDLE) vkDestroyDescriptorSetLayout(device, descriptorSetLayout, null)
        // Part B post-pipeline teardown (pipelines/layouts/passes/pool; the per-frame post images +
        // framebuffers are freed by destroyTargets above).
        if (compositePipeline != VK_NULL_HANDLE) vkDestroyPipeline(device, compositePipeline, null)
        if (blurPipeline != VK_NULL_HANDLE) vkDestroyPipeline(device, blurPipeline, null)
        if (brightPipeline != VK_NULL_HANDLE) vkDestroyPipeline(device, brightPipeline, null)
        if (compositePipelineLayout != VK_NULL_HANDLE) vkDestroyPipelineLayout(device, compositePipelineLayout, null)
        if (blurPipelineLayout != VK_NULL_HANDLE) vkDestroyPipelineLayout(device, blurPipelineLayout, null)
        if (brightPipelineLayout != VK_NULL_HANDLE) vkDestroyPipelineLayout(device, brightPipelineLayout, null)
        if (postPool != VK_NULL_HANDLE) vkDestroyDescriptorPool(device, postPool, null)
        if (postSetLayout != VK_NULL_HANDLE) vkDestroyDescriptorSetLayout(device, postSetLayout, null)
        if (postRenderPass != VK_NULL_HANDLE) vkDestroyRenderPass(device, postRenderPass, null)
        if (compositeRenderPass != VK_NULL_HANDLE) vkDestroyRenderPass(device, compositeRenderPass, null)
        destroyBuffer(uboBuffer, uboMem)
        destroyBuffer(uboParamsBuffer, uboParamsMem)
        if (brdfLutView != VK_NULL_HANDLE) vkDestroyImageView(device, brdfLutView, null)
        if (brdfLutImage != VK_NULL_HANDLE) vkDestroyImage(device, brdfLutImage, null)
        if (brdfLutMem != VK_NULL_HANDLE) vkFreeMemory(device, brdfLutMem, null)
        if (irradianceView != VK_NULL_HANDLE) vkDestroyImageView(device, irradianceView, null)
        if (irradianceImage != VK_NULL_HANDLE) vkDestroyImage(device, irradianceImage, null)
        if (irradianceMem != VK_NULL_HANDLE) vkFreeMemory(device, irradianceMem, null)
        if (skybox.view != VK_NULL_HANDLE) vkDestroyImageView(device, skybox.view, null)
        if (skybox.image != VK_NULL_HANDLE) vkDestroyImage(device, skybox.image, null)
        if (skybox.mem != VK_NULL_HANDLE) vkFreeMemory(device, skybox.mem, null)
        if (ibl.view != VK_NULL_HANDLE) vkDestroyImageView(device, ibl.view, null)
        if (ibl.image != VK_NULL_HANDLE) vkDestroyImage(device, ibl.image, null)
        if (ibl.mem != VK_NULL_HANDLE) vkFreeMemory(device, ibl.mem, null)
        if (shadowPipeline != VK_NULL_HANDLE) vkDestroyPipeline(device, shadowPipeline, null)
        if (shadowPipelineLayout != VK_NULL_HANDLE) vkDestroyPipelineLayout(device, shadowPipelineLayout, null)
        if (shadowFramebuffer != VK_NULL_HANDLE) vkDestroyFramebuffer(device, shadowFramebuffer, null)
        if (shadowRenderPass != VK_NULL_HANDLE) vkDestroyRenderPass(device, shadowRenderPass, null)
        if (shadowSampler != VK_NULL_HANDLE) vkDestroySampler(device, shadowSampler, null)
        if (shadowView != VK_NULL_HANDLE) vkDestroyImageView(device, shadowView, null)
        if (shadowImage != VK_NULL_HANDLE) vkDestroyImage(device, shadowImage, null)
        if (shadowMem != VK_NULL_HANDLE) vkFreeMemory(device, shadowMem, null)
        if (skyPipeline != VK_NULL_HANDLE) vkDestroyPipeline(device, skyPipeline, null)
        if (skyPipelineLayout != VK_NULL_HANDLE) vkDestroyPipelineLayout(device, skyPipelineLayout, null)
        if (pipeline != VK_NULL_HANDLE) vkDestroyPipeline(device, pipeline, null)
        if (pipelineLayout != VK_NULL_HANDLE) vkDestroyPipelineLayout(device, pipelineLayout, null)
        if (renderPass != VK_NULL_HANDLE) vkDestroyRenderPass(device, renderPass, null)
        if (fence != VK_NULL_HANDLE) vkDestroyFence(device, fence, null)
        if (commandPool != VK_NULL_HANDLE) vkDestroyCommandPool(device, commandPool, null)
        vkDestroyDevice(device, null)
        if (::instance.isInitialized) vkDestroyInstance(instance, null)
    }

    companion object {
        private const val FenStart = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"

        /** Target per-frame budget for the animation driver (~60fps). */
        private const val FRAME_BUDGET_MS = 16L

        // Binding layout (single descriptor set, allocated per material group):
        //   0: UBO          { viewProj, lightViewProj, camPos(vec4), invViewProj } — 208 bytes
        //   1: UBOParams    { lightDir(vec4), exposure, gamma, prefilteredCubeMipLevels, pad } — 32 bytes
        //   2: shadowMap    sampler2D   (depth from the light-POV pass)
        //   3: envMap       samplerCube (the loaded papermill HDR env, used by the skybox pass)
        //   4: irradiance   samplerCube (precomputed diffuse irradiance cube — convolution of envMap)
        //   5: prefilteredMap samplerCube (for now: same view as envMap, mip-sampled for roughness)
        //   6: brdfLUT      sampler2D   (precomputed 2D BRDF integration LUT, 512² RG16F)
        //   7: tex          sampler2D   (per-material albedo, multiplied by vTint)

        private const val VERT_GLSL = """
#version 450
layout(location = 0) in vec3 inPos;
layout(location = 1) in vec3 inNormal;
layout(location = 2) in vec2 inUv;
layout(location = 3) in vec3 inTint;
layout(location = 4) in vec4 inTangent;  // xyz: tangent dir; w: handedness (glTF spec)
layout(set = 0, binding = 0) uniform UBO { mat4 viewProj; mat4 lightViewProj; vec4 camPos; mat4 invViewProj; } ubo;
layout(location = 0) out vec3 vWorldPos;
layout(location = 1) out vec3 vNormal;
layout(location = 2) out vec2 vUv;
layout(location = 3) out vec3 vTint;
layout(location = 4) out vec4 vTangent;
void main() {
    gl_Position = ubo.viewProj * vec4(inPos, 1.0);
    vWorldPos = inPos; vNormal = inNormal; vUv = inUv; vTint = inTint; vTangent = inTangent;
}
"""

        // vkChess/Sascha-Willems PBR fragment, adapted to our bindings & non-instanced baked geometry.
        // - Cook-Torrance direct lighting (D_GGX + G_SchlicksmithGGX + F_Schlick) modulated by PCF shadow.
        // - Real IBL: precomputed irradiance cube (diffuse) + prefilteredMap + BRDF LUT (specular).
        // - Per-pixel metallic/roughness from the glTF metallicRoughness texture (B=metallic, G=roughness),
        //   scaled by the material's metallicFactor/roughnessFactor (push constants).
        // - baseColor = baseColorFactor (push constant) × albedo texture × vTint.
        // - Uncharted2 filmic tonemap + gamma 2.2 (controlled by UBOParams.exposure / .gamma).
        // - vTint > 1 reserved for selection-highlight glow (kept from the previous Blinn-Phong shader).
        private const val FRAG_GLSL = """
#version 450
layout(location = 0) in vec3 vWorldPos;
layout(location = 1) in vec3 vNormal;
layout(location = 2) in vec2 vUv;
layout(location = 3) in vec3 vTint;
layout(location = 4) in vec4 vTangent;
layout(set = 0, binding = 0) uniform UBO { mat4 viewProj; mat4 lightViewProj; vec4 camPos; mat4 invViewProj; } ubo;
layout(set = 0, binding = 1) uniform UBOParams { vec4 lightDir; float exposure; float gamma; float prefilteredCubeMipLevels; float pad; } uboParams;
layout(set = 0, binding = 2) uniform sampler2D shadowMap;
layout(set = 0, binding = 3) uniform samplerCube envMap;
layout(set = 0, binding = 4) uniform samplerCube irradiance;
layout(set = 0, binding = 5) uniform samplerCube prefilteredMap;
layout(set = 0, binding = 6) uniform sampler2D brdfLUT;
layout(set = 0, binding = 7) uniform sampler2D tex;
layout(set = 0, binding = 8) uniform sampler2D mrTex;
layout(set = 0, binding = 9) uniform sampler2D normalTex;
layout(push_constant) uniform MaterialParams {
    vec4 baseColorFactor;
    float metallicFactor;
    float roughnessFactor;
    float roughnessScale;     // 24: Part A.2 per-material roughness multiplier
    float grainStrength;      // 28: de-band — 1 keeps full albedo grain; <1 pulls toward grainMean
    float grainMeanR;         // 32
    float grainMeanG;         // 36
    float grainMeanB;         // 40: per-material mean wood colour (sRGB) the grain collapses toward
    float roughnessOverride;  // 44: de-band — >0 uses this constant roughness instead of striped mr.g
} mat;
layout(location = 0) out vec4 outColor;

#define PI 3.1415926535897932384626433832795

// Rebuilds a TBN matrix from the interpolated tangent + geometric normal, then transforms the
// tangent-space normal sample into world space. The flat 1×1 default normal (128,128,255) makes
// this a no-op for materials without a normal map.
vec3 perturbNormal(vec3 N, vec2 uv) {
    vec3 tangentNormal = texture(normalTex, uv).xyz * 2.0 - 1.0;
    vec3 T = normalize(vTangent.xyz - dot(vTangent.xyz, N) * N);  // Gram-Schmidt orthonormalization
    vec3 B = cross(N, T) * vTangent.w;                             // handedness from glTF tangent.w
    mat3 TBN = mat3(T, B, N);
    return normalize(TBN * tangentNormal);
}

float shadowFactor(vec3 worldPos, float ndl) {
    vec4 lp = ubo.lightViewProj * vec4(worldPos, 1.0);
    vec3 proj = lp.xyz / lp.w;
    vec2 uv = proj.xy * 0.5 + 0.5;
    if (uv.x < 0.0 || uv.x > 1.0 || uv.y < 0.0 || uv.y > 1.0 || proj.z > 1.0) return 1.0;
    float bias = max(0.0020 * (1.0 - ndl), 0.0007);
    float current = proj.z - bias;
    float sum = 0.0;
    vec2 texel = vec2(1.0 / 2048.0);
    for (int x = -1; x <= 1; x++) for (int y = -1; y <= 1; y++) {
        float closest = texture(shadowMap, uv + vec2(x, y) * texel).r;
        sum += current <= closest ? 1.0 : 0.0;
    }
    return sum / 9.0;
}

vec3 Uncharted2Tonemap(vec3 x) {
    float A = 0.15; float B = 0.50; float C = 0.10; float D = 0.20; float E = 0.02; float F = 0.30;
    return ((x*(A*x+C*B)+D*E)/(x*(A*x+B)+D*F))-E/F;
}

float D_GGX(float dotNH, float roughness) {
    float alpha = roughness * roughness;
    float alpha2 = alpha * alpha;
    float denom = dotNH * dotNH * (alpha2 - 1.0) + 1.0;
    return (alpha2) / (PI * denom * denom);
}

float G_SchlicksmithGGX(float dotNL, float dotNV, float roughness) {
    float r = (roughness + 1.0);
    float k = (r*r) / 8.0;
    float GL = dotNL / (dotNL * (1.0 - k) + k);
    float GV = dotNV / (dotNV * (1.0 - k) + k);
    return GL * GV;
}

vec3 F_Schlick(float cosTheta, vec3 F0) {
    return F0 + (1.0 - F0) * pow(1.0 - cosTheta, 5.0);
}

vec3 F_SchlickR(float cosTheta, vec3 F0, float roughness) {
    return F0 + (max(vec3(1.0 - roughness), F0) - F0) * pow(1.0 - cosTheta, 5.0);
}

vec3 specularContribution(vec3 L, vec3 V, vec3 N, vec3 F0, float metallic, float roughness, vec3 albedo) {
    vec3 H = normalize(V + L);
    float dotNH = clamp(dot(N, H), 0.0, 1.0);
    float dotNV = clamp(dot(N, V), 0.0, 1.0);
    float dotNL = clamp(dot(N, L), 0.0, 1.0);
    // Sun intensity + warm colour — the papermill IBL is a green/blue outdoor scene whose cool
    // ambient washes out the warm wood albedo. A warm key light (R>B) counteracts that and makes
    // the wood read as wood, not grey. Matches Filament's default ~5500K sunlight tint.
    vec3 lightColor = vec3(3.8, 3.4, 2.8);
    vec3 color = vec3(0.0);
    if (dotNL > 0.0) {
        float D = D_GGX(dotNH, roughness);
        float G = G_SchlicksmithGGX(dotNL, dotNV, roughness);
        vec3 F = F_Schlick(dotNV, F0);
        vec3 spec = D * F * G / (4.0 * dotNL * dotNV + 0.001);
        vec3 kD = (vec3(1.0) - F) * (1.0 - metallic);
        color += (kD * albedo / PI + spec) * dotNL * lightColor;
    }
    return color;
}

vec3 prefilteredReflection(vec3 R, float roughness) {
    float lod = roughness * uboParams.prefilteredCubeMipLevels;
    float lodf = floor(lod);
    float lodc = ceil(lod);
    vec3 a = textureLod(prefilteredMap, R, lodf).rgb;
    vec3 b = textureLod(prefilteredMap, R, lodc).rgb;
    return mix(a, b, lod - lodf);
}

void main() {
    // glTF spec: baseColor = factor × texture; sRGB→linear conversion of the albedo sample.
    vec4 albedoTex = texture(tex, vUv);
    vec3 albedoLin = pow(albedoTex.rgb, vec3(2.2));
    // De-band: pull the (heavily striped) piece albedo toward the per-material mean wood colour so
    // the baked grain reads as subtle texture, not hard rings. grainStrength == 1 is a no-op (board).
    vec3 grainMeanLin = pow(vec3(mat.grainMeanR, mat.grainMeanG, mat.grainMeanB), vec3(2.2));
    albedoLin = mix(grainMeanLin, albedoLin, mat.grainStrength);
    vec3 albedo = albedoLin * mat.baseColorFactor.rgb * vTint;

    // glTF spec: metallic = factor × tex.b; roughness = factor × tex.g × per-material scale (Part A.2),
    // clamped to avoid mirror-sharp. Piece MR has B pinned to 255 so metallic stays 0 for pieces.
    vec3 mr = texture(mrTex, vUv).rgb;
    float metallic = mat.metallicFactor * mr.b;
    // De-band: mr.g (roughness) is also striped, which alternates glossy/matte rings — the gloss boost
    // amplified them. Pieces use a flat roughnessOverride for an even sheen; board keeps its texture.
    float roughness = mat.roughnessOverride > 0.0
        ? mat.roughnessOverride
        : clamp(mat.roughnessFactor * mr.g * mat.roughnessScale, 0.04, 1.0);

    // Apply tangent-space normal map (cracks/wear in the marble) to the geometric normal.
    vec3 N = normalize(perturbNormal(normalize(vNormal), vUv));
    vec3 V = normalize(ubo.camPos.xyz - vWorldPos);
    vec3 R = -normalize(reflect(V, N));

    vec3 L = normalize(uboParams.lightDir.xyz);
    float ndl = max(dot(N, L), 0.0);
    float sh = shadowFactor(vWorldPos, ndl);

    vec3 F0 = mix(vec3(0.04), albedo, metallic);

    // Direct light (Cook-Torrance), modulated by the PCF shadow factor.
    vec3 Lo = specularContribution(L, V, N, F0, metallic, roughness, albedo) * sh;

    // Image-based lighting: real irradiance cube (diffuse) + prefiltered env + BRDF LUT (specular).
    // Warm-tint the irradiance — papermill is an outdoor scene with green vegetation + blue sky,
    // and that cool ambient fights the warm wood albedo. Shifting the ambient warm lets the wood's
    // natural brown read through. Scaled to 0.6 so the warm direct sun stays the dominant light.
    vec3 irradiance = texture(irradiance, N).rgb;
    irradiance *= vec3(1.25, 1.08, 0.85); // warm tint: boost R, cut B
    vec3 diffuse = irradiance * albedo * 0.6;

    vec3 reflection = prefilteredReflection(R, roughness);
    vec2 brdf = texture(brdfLUT, vec2(max(dot(N, V), 0.0), roughness)).rg;
    vec3 F = F_SchlickR(max(dot(N, V), 0.0), F0, roughness);
    // Part A.1 / A.3: IBL specular scale 0.5 -> 0.85 (iblSpecularScale). Restores gloss on pieces and
    // the subtle board reflection that the halved scale muted. (mr.r occlusion + contact grounding
    // applied below stop the brighter ambient from washing pieces out.)
    vec3 specular = reflection * (F * brdf.x + brdf.y) * 0.85;

    vec3 kD = 1.0 - F;
    kD *= 1.0 - metallic;
    vec3 ambient = kD * diffuse + specular;

    // Part A.3: glTF occlusion (mr.r) darkens only the indirect/ambient term. Piece MR.r is ~flat so
    // this mostly grounds the marble board/frame; it is a no-op where mr.r == 1, so it is safe globally.
    float ao = mr.r;
    ambient *= mix(1.0, ao, 1.0);                          // aoStrength = 1.0
    // Part A.3: deepen contact shadow on the ambient fill so pieces sit on the board instead of floating.
    ambient *= mix(1.0 - 0.35, 1.0, sh);                   // contactStrength = 0.35

    vec3 color = ambient + Lo;

    // Selection-highlight glow: vTint > 1 emits extra light (used by the selection marker).
    vec3 glow = max(vTint - vec3(1.0), 0.0) * albedoTex.rgb * 1.6;
     color += glow;
 
     // Part B.6: output raw linear HDR radiance. Tonemap + exposure + gamma moved to the composite
     // pass so bloom can be extracted in HDR; the additive glow above will bloom (desirable).
     outColor = vec4(color, 1.0);
 }
 """

        private const val SHADOW_VERT = """
#version 450
layout(location = 0) in vec3 inPos;
layout(push_constant) uniform PC { mat4 lightViewProj; } pc;
void main() { gl_Position = pc.lightViewProj * vec4(inPos, 1.0); }
"""

        private const val SHADOW_FRAG = """
#version 450
void main() {}
"""

        private const val SKY_VERT = """
#version 450
layout(location = 0) out vec3 vViewDir;
layout(set = 0, binding = 0) uniform UBO { mat4 viewProj; mat4 lightViewProj; vec4 camPos; mat4 invViewProj; } ubo;
void main() {
    vec2 p = vec2(float((gl_VertexIndex << 1) & 2), float(gl_VertexIndex & 2));
    vec2 ndc = p * 2.0 - 1.0;
    vec4 unprojected = ubo.invViewProj * vec4(ndc, 1.0, 1.0);
    vViewDir = unprojected.xyz / unprojected.w - ubo.camPos.xyz;
    gl_Position = vec4(ndc, 1.0, 1.0); // far plane, behind everything
}
"""

        // Skybox tonemap matches the fragment pass so the background and pieces share a look.
        private const val SKY_FRAG = """
#version 450
layout(location = 0) in vec3 vViewDir;
layout(set = 0, binding = 1) uniform UBOParams { vec4 lightDir; float exposure; float gamma; float prefilteredCubeMipLevels; float pad; } uboParams;
layout(set = 0, binding = 3) uniform samplerCube envMap;
layout(location = 0) out vec4 outColor;

vec3 Uncharted2Tonemap(vec3 x) {
    float A = 0.15; float B = 0.50; float C = 0.10; float D = 0.20; float E = 0.02; float F = 0.30;
    return ((x*(A*x+C*B)+D*E)/(x*(A*x+B)+D*F))-E/F;
}

void main() {
    vec3 color = textureLod(envMap, normalize(vViewDir), 0.0).rgb;
    // Part B.6: raw linear HDR sky radiance — tonemap happens in the composite pass.
    outColor = vec4(color, 1.0);
}
"""

        // --- IBL precompute shaders (run once at init) ---

        // Cube-face vertex shader for the irradiance offscreen pass; takes a 1x1 cube-strip of
        // unit-cube positions and transforms each face by its own mvp supplied via push constants.
        private const val FILTERCUBE_VERT = """
#version 450
layout(location = 0) in vec3 inPos;
layout(push_constant) uniform PushConsts { layout(offset = 0) mat4 mvp; } pushConsts;
layout(location = 0) out vec3 outUVW;
out gl_PerVertex { vec4 gl_Position; };
void main() {
    outUVW = inPos;
    gl_Position = pushConsts.mvp * vec4(inPos.xyz, 1.0);
}
"""

        // vkChess irradiancecube.frag — Riemann integration of the env cube over the hemisphere.
        private const val IRRADIANCE_FRAG = """
#version 450
layout(location = 0) in vec3 inPos;
layout(location = 0) out vec4 outColor;
layout(binding = 0) uniform samplerCube samplerEnv;
layout(push_constant) uniform PushConsts {
    layout(offset = 64) float deltaPhi;
    layout(offset = 68) float deltaTheta;
} consts;
#define PI 3.1415926535897932384626433832795
void main() {
    vec3 N = normalize(inPos);
    vec3 up = vec3(0.0, 1.0, 0.0);
    vec3 right = normalize(cross(up, N));
    up = cross(N, right);
    const float TWO_PI = PI * 2.0;
    const float HALF_PI = PI * 0.5;
    vec3 color = vec3(0.0);
    uint sampleCount = 0u;
    for (float phi = 0.0; phi < TWO_PI; phi += consts.deltaPhi) {
        for (float theta = 0.0; theta < HALF_PI; theta += consts.deltaTheta) {
            vec3 tempVec = cos(phi) * right + sin(phi) * up;
            vec3 sampleVector = cos(theta) * N + sin(theta) * tempVec;
            color += texture(samplerEnv, sampleVector).rgb * cos(theta) * sin(theta);
            sampleCount++;
        }
    }
    outColor = vec4(PI * color / float(sampleCount), 1.0);
}
"""

        // vkChess prefilterenvmap.frag — GGX importance-sampled prefiltering of the env cube into
        // a mip chain (one roughness per mip). Run once per (face, mip) at init.
        private const val PREFILTER_FRAG = """
#version 450
layout(location = 0) in vec3 inPos;
layout(location = 0) out vec4 outColor;
layout(binding = 0) uniform samplerCube samplerEnv;
layout(push_constant) uniform PushConsts {
    layout(offset = 64) float roughness;
    layout(offset = 68) uint numSamples;
} consts;
const float PI = 3.1415926536;
float random(vec2 co) {
    float a = 12.9898; float b = 78.233; float c = 43758.5453;
    float dt = dot(co.xy, vec2(a, b)); float sn = mod(dt, 3.14);
    return fract(sin(sn) * c);
}
vec2 hammersley2d(uint i, uint N) {
    uint bits = (i << 16u) | (i >> 16u);
    bits = ((bits & 0x55555555u) << 1u) | ((bits & 0xAAAAAAAAu) >> 1u);
    bits = ((bits & 0x33333333u) << 2u) | ((bits & 0xCCCCCCCCu) >> 2u);
    bits = ((bits & 0x0F0F0F0Fu) << 4u) | ((bits & 0xF0F0F0F0u) >> 4u);
    bits = ((bits & 0x00FF00FFu) << 8u) | ((bits & 0xFF00FF00u) >> 8u);
    float rdi = float(bits) * 2.3283064365386963e-10;
    return vec2(float(i) / float(N), rdi);
}
vec3 importanceSample_GGX(vec2 Xi, float roughness, vec3 normal) {
    float alpha = roughness * roughness;
    float phi = 2.0 * PI * Xi.x + random(normal.xz) * 0.1;
    float cosTheta = sqrt((1.0 - Xi.y) / (1.0 + (alpha*alpha - 1.0) * Xi.y));
    float sinTheta = sqrt(1.0 - cosTheta * cosTheta);
    vec3 H = vec3(sinTheta * cos(phi), sinTheta * sin(phi), cosTheta);
    vec3 up = abs(normal.z) < 0.999 ? vec3(0.0, 0.0, 1.0) : vec3(1.0, 0.0, 0.0);
    vec3 tangentX = normalize(cross(up, normal));
    vec3 tangentY = normalize(cross(normal, tangentX));
    return normalize(tangentX * H.x + tangentY * H.y + normal * H.z);
}
float D_GGX(float dotNH, float roughness) {
    float alpha = roughness * roughness;
    float alpha2 = alpha * alpha;
    float denom = dotNH * dotNH * (alpha2 - 1.0) + 1.0;
    return (alpha2) / (PI * denom * denom);
}
vec3 prefilterEnvMap(vec3 R, float roughness) {
    vec3 N = R;
    vec3 V = R;
    vec3 color = vec3(0.0);
    float totalWeight = 0.0;
    float envMapDim = float(textureSize(samplerEnv, 0).s);
    for (uint i = 0u; i < consts.numSamples; i++) {
        vec2 Xi = hammersley2d(i, consts.numSamples);
        vec3 H = importanceSample_GGX(Xi, roughness, N);
        vec3 L = 2.0 * dot(V, H) * H - V;
        float dotNL = clamp(dot(N, L), 0.0, 1.0);
        if (dotNL > 0.0) {
            float dotNH = clamp(dot(N, H), 0.0, 1.0);
            float dotVH = clamp(dot(V, H), 0.0, 1.0);
            float pdf = D_GGX(dotNH, roughness) * dotNH / (4.0 * dotVH) + 0.0001;
            float omegaS = 1.0 / (float(consts.numSamples) * pdf);
            float omegaP = 4.0 * PI / (6.0 * envMapDim * envMapDim);
            float mipLevel = roughness == 0.0 ? 0.0 : max(0.5 * log2(omegaS / omegaP) + 1.0, 0.0);
            color += textureLod(samplerEnv, L, mipLevel).rgb * dotNL;
            totalWeight += dotNL;
        }
    }
    return (color / totalWeight);
}
void main() {
    vec3 N = normalize(inPos);
    outColor = vec4(prefilterEnvMap(N, consts.roughness), 1.0);
}
"""

        // Fullscreen-triangle vertex shader for the BRDF LUT pass (no vertex buffer required).
        private const val FSQ_VERT = """
#version 450
layout(location = 0) out vec2 outUV;
void main() {
    outUV = vec2((gl_VertexIndex << 1) & 2, gl_VertexIndex & 2);
    gl_Position = vec4(outUV * 2.0f - 1.0f, 0.0f, 1.0f);
}
"""

        // vkChess genbrdflut.frag — GGX importance-sampled BRDF integration → 2D (NdotV, roughness) LUT.
        private const val BRDF_LUT_FRAG = """
#version 450
layout(location = 0) in vec2 inUV;
layout(location = 0) out vec4 outColor;
layout(constant_id = 0) const uint NUM_SAMPLES = 1024u;
const float PI = 3.1415926536;
float random(vec2 co) {
    float a = 12.9898; float b = 78.233; float c = 43758.5453;
    float dt = dot(co.xy, vec2(a, b)); float sn = mod(dt, 3.14);
    return fract(sin(sn) * c);
}
vec2 hammersley2d(uint i, uint N) {
    uint bits = (i << 16u) | (i >> 16u);
    bits = ((bits & 0x55555555u) << 1u) | ((bits & 0xAAAAAAAAu) >> 1u);
    bits = ((bits & 0x33333333u) << 2u) | ((bits & 0xCCCCCCCCu) >> 2u);
    bits = ((bits & 0x0F0F0F0Fu) << 4u) | ((bits & 0xF0F0F0F0u) >> 4u);
    bits = ((bits & 0x00FF00FFu) << 8u) | ((bits & 0xFF00FF00u) >> 8u);
    float rdi = float(bits) * 2.3283064365386963e-10;
    return vec2(float(i) / float(N), rdi);
}
vec3 importanceSample_GGX(vec2 Xi, float roughness, vec3 normal) {
    float alpha = roughness * roughness;
    float phi = 2.0 * PI * Xi.x + random(normal.xz) * 0.1;
    float cosTheta = sqrt((1.0 - Xi.y) / (1.0 + (alpha*alpha - 1.0) * Xi.y));
    float sinTheta = sqrt(1.0 - cosTheta * cosTheta);
    vec3 H = vec3(sinTheta * cos(phi), sinTheta * sin(phi), cosTheta);
    vec3 up = abs(normal.z) < 0.999 ? vec3(0.0, 0.0, 1.0) : vec3(1.0, 0.0, 0.0);
    vec3 tangentX = normalize(cross(up, normal));
    vec3 tangentY = normalize(cross(normal, tangentX));
    return normalize(tangentX * H.x + tangentY * H.y + normal * H.z);
}
float G_SchlicksmithGGX(float dotNL, float dotNV, float roughness) {
    float k = (roughness * roughness) / 2.0;
    float GL = dotNL / (dotNL * (1.0 - k) + k);
    float GV = dotNV / (dotNV * (1.0 - k) + k);
    return GL * GV;
}
vec2 BRDF(float NoV, float roughness) {
    const vec3 N = vec3(0.0, 0.0, 1.0);
    vec3 V = vec3(sqrt(1.0 - NoV*NoV), 0.0, NoV);
    vec2 LUT = vec2(0.0);
    for (uint i = 0u; i < NUM_SAMPLES; i++) {
        vec2 Xi = hammersley2d(i, NUM_SAMPLES);
        vec3 H = importanceSample_GGX(Xi, roughness, N);
        vec3 L = 2.0 * dot(V, H) * H - V;
        float dotNL = max(dot(N, L), 0.0);
        float dotNV = max(dot(N, V), 0.0);
        float dotVH = max(dot(V, H), 0.0);
        float dotNH = max(dot(H, N), 0.0);
        if (dotNL > 0.0) {
            float G = G_SchlicksmithGGX(dotNL, dotNV, roughness);
            float G_Vis = (G * dotVH) / (dotNH * dotNV);
            float Fc = pow(1.0 - dotVH, 5.0);
            LUT += vec2((1.0 - Fc) * G_Vis, Fc * G_Vis);
        }
    }
    return LUT / float(NUM_SAMPLES);
}
void main() {
    outColor = vec4(BRDF(inUV.s, 1.0 - inUV.t), 0.0, 1.0);
}
"""

        // Part B.7 — HDR bloom post shaders (drawn as fullscreen triangles via FSQ_VERT).

        // BRIGHT_FRAG — threshold with a soft knee (Karis/UE style): below threshold+knee nothing
        // passes; the soft quadratic knee eases in across [threshold-knee, threshold]; above the
        // threshold everything passes at full strength.
        private const val BRIGHT_FRAG = """
#version 450
layout(location = 0) in vec2 inUV;
layout(set = 0, binding = 0) uniform sampler2D src;
layout(push_constant) uniform PC { vec4 p; } pc; // p.x = threshold, p.y = knee
layout(location = 0) out vec4 outColor;
void main() {
    vec3 c = texture(src, inUV).rgb;
    float br = max(c.r, max(c.g, c.b));
    float knee = max(pc.p.y, 1e-4);
    float soft = clamp((br - pc.p.x + knee) / (2.0 * knee), 0.0, 1.0);
    float w = max(soft * soft, step(pc.p.x, br));
    outColor = vec4(c * w, 1.0);
}
"""

        // BLUR_FRAG — separable 9-tap Gaussian. Push constant: xy = texel size, zw = direction
        // (1,0)=horizontal or (0,1)=vertical. Run H then V, `bloomIterations` times.
        private const val BLUR_FRAG = """
#version 450
layout(location = 0) in vec2 inUV;
layout(set = 0, binding = 0) uniform sampler2D src;
layout(push_constant) uniform PC { vec4 p; } pc;
layout(location = 0) out vec4 outColor;
const float W[5] = float[](0.227027, 0.1945946, 0.1216216, 0.054054, 0.016216);
void main() {
    vec2 step = pc.p.xy * pc.p.zw;
    vec3 c = texture(src, inUV).rgb * W[0];
    for (int i = 1; i < 5; i++) {
        c += texture(src, inUV + step * float(i)).rgb * W[i];
        c += texture(src, inUV - step * float(i)).rgb * W[i];
    }
    outColor = vec4(c, 1.0);
}
"""

        // COMPOSITE_FRAG — scene + bloom, then the single Uncharted2 tonemap + gamma (the only
        // tonemap left now that the scene/sky shaders emit raw HDR). Push: x=intensity, y=exposure,
        // z=gamma. With intensity 0 this is a straight tonemap of the HDR scene.
        private const val COMPOSITE_FRAG = """
#version 450
layout(location = 0) in vec2 inUV;
layout(set = 0, binding = 0) uniform sampler2D sceneHdr;
layout(set = 0, binding = 1) uniform sampler2D bloomTex;
layout(push_constant) uniform PC { vec4 p; } pc;
layout(location = 0) out vec4 outColor;
vec3 Uncharted2Tonemap(vec3 x) {
    float A=0.15, B=0.50, C=0.10, D=0.20, E=0.02, F=0.30;
    return ((x*(A*x+C*B)+D*E)/(x*(A*x+B)+D*F))-E/F;
}
void main() {
    vec3 hdr = texture(sceneHdr, inUV).rgb + texture(bloomTex, inUV).rgb * pc.p.x;
    vec3 col = Uncharted2Tonemap(clamp(hdr * pc.p.y, 0.0, 256.0));
    col *= 1.0 / Uncharted2Tonemap(vec3(11.2));
    col = pow(col, vec3(1.0 / pc.p.z));
    outColor = vec4(col, 1.0);
}
"""
    }
}
