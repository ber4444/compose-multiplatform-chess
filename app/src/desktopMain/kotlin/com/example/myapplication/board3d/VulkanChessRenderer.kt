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
    private val textureImages: Map<ChessTexture, TextureImage>

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
    private val lightDir = org.joml.Vector3f(0.45f, 1.0f, 0.35f).normalize()

    private val colorFormat = VK_FORMAT_R8G8B8A8_UNORM
    private val depthFormat = VK_FORMAT_D32_SFLOAT

    private var width = 0
    private var height = 0
    private var samples = VK_SAMPLE_COUNT_1_BIT // MSAA sample count (chosen in initVulkan)
    private var colorImage = VK_NULL_HANDLE; private var colorMem = VK_NULL_HANDLE; private var colorView = VK_NULL_HANDLE // MSAA color
    private var depthImage = VK_NULL_HANDLE; private var depthMem = VK_NULL_HANDLE; private var depthView = VK_NULL_HANDLE // MSAA depth
    private var resolveImage = VK_NULL_HANDLE; private var resolveMem = VK_NULL_HANDLE; private var resolveView = VK_NULL_HANDLE // single-sample resolve (read back)
    private var envImage = VK_NULL_HANDLE; private var envMem = VK_NULL_HANDLE; private var envView = VK_NULL_HANDLE
    private var framebuffer = VK_NULL_HANDLE
    private var readbackBuffer = VK_NULL_HANDLE; private var readbackMem = VK_NULL_HANDLE; private var readbackSize = 0L

    private class Texture(var image: Long = VK_NULL_HANDLE, var mem: Long = VK_NULL_HANDLE, var view: Long = VK_NULL_HANDLE, var descriptorSet: Long = VK_NULL_HANDLE)
    private val textures = HashMap<ChessTexture, Texture>()

    private class GroupBuffers {
        var vBuf = VK_NULL_HANDLE; var vMem = VK_NULL_HANDLE; var vCap = 0L
        var iBuf = VK_NULL_HANDLE; var iMem = VK_NULL_HANDLE; var iCap = 0L
        var indexCount = 0
    }
    private val groupBuffers = HashMap<ChessTexture, GroupBuffers>()

    private val hostVisible = VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT or VK_MEMORY_PROPERTY_HOST_COHERENT_BIT

    private var surface: ImageBitmapChess3DSurface? = null
    private var pendingFen: String? = null
    private var selectedSquare: BoardSquare? = null
    private var camera: CameraParams = OrbitCameraController.DEFAULT_WHITE_VIEW
    private var disposed = false

    init {
        meshes = GltfChessMeshes.load(glb)
        require(meshes.isNotEmpty()) { "No chess piece meshes found in glb" }
        textureImages = GltfChessTextures.load(glb)
        runBlocking(renderDispatcher) {
            initVulkan()
        }
    }

    override fun attach(surface: Chess3DSurface) {
        if (surface !is ImageBitmapChess3DSurface) return
        post {
            this.surface = surface
            ensureTargets(surface.widthPx.coerceAtLeast(1), surface.heightPx.coerceAtLeast(1))
            camera = camera.copy(aspect = width.toFloat() / height.toFloat())
            rebuildGeometry(pendingFen ?: FenStart)
            renderFrame()
        }
    }

    override fun detach() { post { surface = null } }

    override fun updatePosition(fen: String) = updatePosition(fen, null)

    override fun updatePosition(fen: String, transition: Board3DTransition?) {
        pendingFen = fen
        post {
            if (surface != null) {
                if (transition != null && transition !is Board3DTransition.Reset) {
                    animateTransition(fen, transition)
                } else {
                    rebuildGeometry(fen)
                    renderFrame()
                }
            }
        }
    }

    private var animationJob: Job? = null

    private fun animateTransition(targetFen: String, transition: Board3DTransition) {
        animationJob?.cancel()
        val baseScene = Board3DSceneMapper.fromFen(targetFen).copy(selectedSquare = selectedSquare)
        
        animationJob = renderScope.launch {
            val startMs = System.currentTimeMillis()
            val durationMs = 500L
            
            while (true) {
                val now = System.currentTimeMillis()
                val progress = ((now - startMs).toFloat() / durationMs).coerceIn(0f, 1f)
                
                // Construct the interpolated scene
                val interpolatedPieces = baseScene.pieces.map { piece ->
                    // Find if this piece is the target of the transition
                    val t = transition
                    when (t) {
                        is Board3DTransition.Move -> {
                            if (piece.square == t.to && piece.kind == t.kind && piece.color == t.color) {
                                // Interpolate position
                                val fromPos = BoardGeometry.squareCenter(t.from)
                                val toPos = BoardGeometry.squareCenter(t.to)
                                val currentPos = org.joml.Vector3f(fromPos.x, fromPos.y, fromPos.z).lerp(
                                    org.joml.Vector3f(toPos.x, toPos.y, toPos.z), progress
                                )
                                piece.copy(position = Vec3(currentPos.x, currentPos.y, currentPos.z))
                            } else if (t.secondary != null && piece.square == t.secondary.to && piece.kind == t.secondary.kind && piece.color == t.secondary.color) {
                                val fromPos = BoardGeometry.squareCenter(t.secondary.from)
                                val toPos = BoardGeometry.squareCenter(t.secondary.to)
                                val currentPos = org.joml.Vector3f(fromPos.x, fromPos.y, fromPos.z).lerp(
                                    org.joml.Vector3f(toPos.x, toPos.y, toPos.z), progress
                                )
                                piece.copy(position = Vec3(currentPos.x, currentPos.y, currentPos.z))
                            } else {
                                piece
                            }
                        }
                        is Board3DTransition.Capture -> {
                            if (piece.square == t.move.to && piece.kind == t.move.kind && piece.color == t.move.color) {
                                val fromPos = BoardGeometry.squareCenter(t.move.from)
                                val toPos = BoardGeometry.squareCenter(t.move.to)
                                val currentPos = org.joml.Vector3f(fromPos.x, fromPos.y, fromPos.z).lerp(
                                    org.joml.Vector3f(toPos.x, toPos.y, toPos.z), progress
                                )
                                piece.copy(position = Vec3(currentPos.x, currentPos.y, currentPos.z))
                            } else {
                                piece
                            }
                        }
                        is Board3DTransition.Promotion -> {
                            if (piece.square == t.move.to && piece.kind == t.promotedTo && piece.color == t.move.color) {
                                val fromPos = BoardGeometry.squareCenter(t.move.from)
                                val toPos = BoardGeometry.squareCenter(t.move.to)
                                val currentPos = org.joml.Vector3f(fromPos.x, fromPos.y, fromPos.z).lerp(
                                    org.joml.Vector3f(toPos.x, toPos.y, toPos.z), progress
                                )
                                // Show pawn until the very end, then snap to new piece
                                if (progress < 1f) {
                                    piece.copy(kind = PieceKind.PAWN, position = Vec3(currentPos.x, currentPos.y, currentPos.z))
                                } else {
                                    piece.copy(position = Vec3(currentPos.x, currentPos.y, currentPos.z))
                                }
                            } else {
                                piece
                            }
                        }
                        else -> piece
                    }
                }

                // Handle fading/sinking captured piece (since it's not in baseScene, we need to inject it)
                val injectedPieces = interpolatedPieces.toMutableList()
                if (transition is Board3DTransition.Capture && progress < 1f) {
                    val pos = BoardGeometry.squareCenter(transition.capturedSquare)
                    val sinkDepth = progress * 2.0f // sink down 2 units
                    injectedPieces.add(Piece3DInstance(
                        kind = transition.capturedKind,
                        color = transition.capturedColor,
                        square = transition.capturedSquare,
                        position = Vec3(pos.x, pos.y - sinkDepth, pos.z),
                        rotationYDegrees = if (transition.capturedColor == PieceColor.WHITE) 0f else 180f
                    ))
                }

                val interpolatedScene = baseScene.copy(pieces = injectedPieces)
                val geo = ChessSceneGeometry.build(interpolatedScene, meshes)
                for ((tex, group) in geo.groups) uploadGroup(tex, group)
                
                renderFrame()
                
                if (progress >= 1f) break
                kotlinx.coroutines.delay(16) // ~60fps
            }
        }
    }

    override fun setSelectedSquare(square: BoardSquare?) {
        post {
            if (square == selectedSquare) return@post
            selectedSquare = square
            if (surface != null) { rebuildGeometry(pendingFen ?: FenStart); renderFrame() }
        }
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

    private fun rebuildGeometry(fen: String) {
        val scene = Board3DSceneMapper.fromFen(fen).copy(selectedSquare = selectedSquare)
        val geo = ChessSceneGeometry.build(scene, meshes)
        for ((tex, group) in geo.groups) uploadGroup(tex, group)
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
            val ds = textures[tex]?.descriptorSet ?: continue
            vkCmdBindDescriptorSets(commandBuffer, VK_PIPELINE_BIND_POINT_GRAPHICS, pipelineLayout, 0, stack.longs(ds), null)
            vkCmdBindVertexBuffers(commandBuffer, 0, stack.longs(g.vBuf), stack.longs(0))
            vkCmdBindIndexBuffer(commandBuffer, g.iBuf, 0, VK_INDEX_TYPE_UINT32)
            vkCmdDrawIndexed(commandBuffer, g.indexCount, 1, 0, 0, 0)
        }

        vkCmdEndRenderPass(commandBuffer)

        val region = VkBufferImageCopy.calloc(1, stack)
        region.bufferOffset(0).bufferRowLength(0).bufferImageHeight(0)
        region.imageSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1)
        region.imageOffset().set(0, 0, 0); region.imageExtent().set(width, height, 1)
        vkCmdCopyImageToBuffer(commandBuffer, resolveImage, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, readbackBuffer, region)

        check(vkEndCommandBuffer(commandBuffer) == VK_SUCCESS)
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
            queueFamily = findGraphicsQueueFamily(stack)

            val devExts = mutableListOf<String>()
            if (hasDeviceExtension(stack, "VK_KHR_portability_subset")) devExts += "VK_KHR_portability_subset"
            val pDevExts = if (devExts.isEmpty()) null else stack.pointers(*devExts.map { stack.UTF8(it) }.toTypedArray())
            val queueInfo = VkDeviceQueueCreateInfo.calloc(1, stack).sType(VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO).queueFamilyIndex(queueFamily).pQueuePriorities(stack.floats(1f))
            val dci = VkDeviceCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO).pQueueCreateInfos(queueInfo).ppEnabledExtensionNames(pDevExts)
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
            createPipeline(stack)
            createSkyPipeline(stack)
        }
        val envBytes = this::class.java.getResourceAsStream("/papermill_hdr16f_cube.ktx")?.readAllBytes()
        if (envBytes != null) {
            val ktx = KtxLoader.load(envBytes)
            if (ktx != null) {
                uploadKtxTexture(ktx)
                ktx.free()
            }
        }
        uploadAllTextures()
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

    private fun createRenderPass(stack: MemoryStack) {
        // 0: MSAA color (rendered into), 1: MSAA depth, 2: single-sample resolve target (read back).
        val attachments = VkAttachmentDescription.calloc(3, stack)
        attachments[0].format(colorFormat).samples(samples)
            .loadOp(VK_ATTACHMENT_LOAD_OP_CLEAR).storeOp(VK_ATTACHMENT_STORE_OP_DONT_CARE)
            .stencilLoadOp(VK_ATTACHMENT_LOAD_OP_DONT_CARE).stencilStoreOp(VK_ATTACHMENT_STORE_OP_DONT_CARE)
            .initialLayout(VK_IMAGE_LAYOUT_UNDEFINED).finalLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL)
        attachments[1].format(depthFormat).samples(samples)
            .loadOp(VK_ATTACHMENT_LOAD_OP_CLEAR).storeOp(VK_ATTACHMENT_STORE_OP_DONT_CARE)
            .stencilLoadOp(VK_ATTACHMENT_LOAD_OP_DONT_CARE).stencilStoreOp(VK_ATTACHMENT_STORE_OP_DONT_CARE)
            .initialLayout(VK_IMAGE_LAYOUT_UNDEFINED).finalLayout(VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL)
        attachments[2].format(colorFormat).samples(VK_SAMPLE_COUNT_1_BIT)
            .loadOp(VK_ATTACHMENT_LOAD_OP_DONT_CARE).storeOp(VK_ATTACHMENT_STORE_OP_STORE)
            .stencilLoadOp(VK_ATTACHMENT_LOAD_OP_DONT_CARE).stencilStoreOp(VK_ATTACHMENT_STORE_OP_DONT_CARE)
            .initialLayout(VK_IMAGE_LAYOUT_UNDEFINED).finalLayout(VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL)
        val colorRef = VkAttachmentReference.calloc(1, stack).attachment(0).layout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL)
        val depthRef = VkAttachmentReference.calloc(stack).attachment(1).layout(VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL)
        val resolveRef = VkAttachmentReference.calloc(1, stack).attachment(2).layout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL)
        val subpass = VkSubpassDescription.calloc(1, stack).pipelineBindPoint(VK_PIPELINE_BIND_POINT_GRAPHICS)
            .colorAttachmentCount(1).pColorAttachments(colorRef).pResolveAttachments(resolveRef).pDepthStencilAttachment(depthRef)
        val rpInfo = VkRenderPassCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_RENDER_PASS_CREATE_INFO).pAttachments(attachments).pSubpasses(subpass)
        val p = stack.mallocLong(1)
        check(vkCreateRenderPass(device, rpInfo, null, p) == VK_SUCCESS) { "vkCreateRenderPass failed" }
        renderPass = p.get(0)
    }

    private fun createSampler(stack: MemoryStack) {
        val info = VkSamplerCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO)
            .magFilter(VK_FILTER_LINEAR).minFilter(VK_FILTER_LINEAR)
            .addressModeU(VK_SAMPLER_ADDRESS_MODE_REPEAT).addressModeV(VK_SAMPLER_ADDRESS_MODE_REPEAT).addressModeW(VK_SAMPLER_ADDRESS_MODE_REPEAT)
            .mipmapMode(VK_SAMPLER_MIPMAP_MODE_LINEAR).maxLod(0f).minLod(0f)
        val p = stack.mallocLong(1)
        check(vkCreateSampler(device, info, null, p) == VK_SUCCESS); sampler = p.get(0)
    }

    private fun createDescriptorLayoutAndPool(stack: MemoryStack) {
        val bindings = VkDescriptorSetLayoutBinding.calloc(4, stack)
        bindings[0].binding(0).descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1).stageFlags(VK_SHADER_STAGE_FRAGMENT_BIT) // material albedo
        bindings[1].binding(1).descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1).stageFlags(VK_SHADER_STAGE_FRAGMENT_BIT) // shadow map
        bindings[2].binding(2).descriptorType(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER).descriptorCount(1).stageFlags(VK_SHADER_STAGE_VERTEX_BIT or VK_SHADER_STAGE_FRAGMENT_BIT) // matrices
        bindings[3].binding(3).descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1).stageFlags(VK_SHADER_STAGE_FRAGMENT_BIT) // env map
        val layoutInfo = VkDescriptorSetLayoutCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO).pBindings(bindings)
        val pLayout = stack.mallocLong(1)
        check(vkCreateDescriptorSetLayout(device, layoutInfo, null, pLayout) == VK_SUCCESS); descriptorSetLayout = pLayout.get(0)

        val n = ChessTexture.entries.size
        val poolSizes = VkDescriptorPoolSize.calloc(2, stack)
        poolSizes[0].type(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(n * 3)
        poolSizes[1].type(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER).descriptorCount(n)
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

        val binding = VkVertexInputBindingDescription.calloc(1, stack).binding(0).stride(11 * 4).inputRate(VK_VERTEX_INPUT_RATE_VERTEX)
        val attrs = VkVertexInputAttributeDescription.calloc(4, stack)
        attrs[0].location(0).binding(0).format(VK_FORMAT_R32G32B32_SFLOAT).offset(0)
        attrs[1].location(1).binding(0).format(VK_FORMAT_R32G32B32_SFLOAT).offset(3 * 4)
        attrs[2].location(2).binding(0).format(VK_FORMAT_R32G32_SFLOAT).offset(6 * 4)
        attrs[3].location(3).binding(0).format(VK_FORMAT_R32G32B32_SFLOAT).offset(8 * 4)
        val vertexInput = VkPipelineVertexInputStateCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO).pVertexBindingDescriptions(binding).pVertexAttributeDescriptions(attrs)
        val inputAssembly = VkPipelineInputAssemblyStateCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO).topology(VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST)
        val viewportState = VkPipelineViewportStateCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO).viewportCount(1).scissorCount(1)
        val raster = VkPipelineRasterizationStateCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO).polygonMode(VK_POLYGON_MODE_FILL).cullMode(VK_CULL_MODE_NONE).frontFace(VK_FRONT_FACE_COUNTER_CLOCKWISE).lineWidth(1f)
        val multisample = VkPipelineMultisampleStateCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO).rasterizationSamples(samples)
        val depth = VkPipelineDepthStencilStateCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_PIPELINE_DEPTH_STENCIL_STATE_CREATE_INFO).depthTestEnable(true).depthWriteEnable(true).depthCompareOp(VK_COMPARE_OP_LESS)
        val blendAttachment = VkPipelineColorBlendAttachmentState.calloc(1, stack).colorWriteMask(VK_COLOR_COMPONENT_R_BIT or VK_COLOR_COMPONENT_G_BIT or VK_COLOR_COMPONENT_B_BIT or VK_COLOR_COMPONENT_A_BIT).blendEnable(false)
        val blend = VkPipelineColorBlendStateCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO).pAttachments(blendAttachment)
        val dynamic = VkPipelineDynamicStateCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_PIPELINE_DYNAMIC_STATE_CREATE_INFO).pDynamicStates(stack.ints(VK_DYNAMIC_STATE_VIEWPORT, VK_DYNAMIC_STATE_SCISSOR))

        val layoutInfo = VkPipelineLayoutCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO).pSetLayouts(stack.longs(descriptorSetLayout))
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
            val file = when (tex) {
                ChessTexture.BOARD -> "board3"
                ChessTexture.WHITE -> "whites"
                ChessTexture.BLACK -> "blacks"
                ChessTexture.FRAME -> "marble-speckled-albedo"
            }
            val img = textureImages[tex] ?: continue
            textures[tex] = uploadTexture(img)
        }
    }

    private fun uploadTexture(img: TextureImage): Texture {
        val (image, mem) = createImage(img.width, img.height, VK_FORMAT_R8G8B8A8_UNORM, VK_IMAGE_USAGE_TRANSFER_DST_BIT or VK_IMAGE_USAGE_SAMPLED_BIT)
        val staging = createBuffer(img.rgba.size.toLong(), VK_BUFFER_USAGE_TRANSFER_SRC_BIT, hostVisible)
        writeBytes(staging.second, img.rgba)
        singleTimeCommands { cmd ->
            MemoryStack.stackPush().use { stack ->
                transition(cmd, stack, image, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 0, VK_ACCESS_TRANSFER_WRITE_BIT, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT)
                val region = VkBufferImageCopy.calloc(1, stack)
                region.bufferOffset(0).bufferRowLength(0).bufferImageHeight(0)
                region.imageSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1)
                region.imageOffset().set(0, 0, 0); region.imageExtent().set(img.width, img.height, 1)
                vkCmdCopyBufferToImage(cmd, staging.first, image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, region)
                transition(cmd, stack, image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, VK_ACCESS_TRANSFER_WRITE_BIT, VK_ACCESS_SHADER_READ_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT)
            }
        }
        destroyBuffer(staging.first, staging.second)
        val view = createImageView(image, VK_FORMAT_R8G8B8A8_UNORM, VK_IMAGE_ASPECT_COLOR_BIT)
        val descriptorSet = MemoryStack.stackPush().use { stack ->
            val alloc = VkDescriptorSetAllocateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO).descriptorPool(descriptorPool).pSetLayouts(stack.longs(descriptorSetLayout))
            val pSet = stack.mallocLong(1)
            check(vkAllocateDescriptorSets(device, alloc, pSet) == VK_SUCCESS)
            val set = pSet.get(0)
            val matInfo = VkDescriptorImageInfo.calloc(1, stack).imageLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL).imageView(view).sampler(sampler)
            val shadowInfo = VkDescriptorImageInfo.calloc(1, stack).imageLayout(VK_IMAGE_LAYOUT_DEPTH_STENCIL_READ_ONLY_OPTIMAL).imageView(shadowView).sampler(shadowSampler)
            val envInfo = VkDescriptorImageInfo.calloc(1, stack).imageLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL).imageView(envView).sampler(sampler)
            val bufInfo = VkDescriptorBufferInfo.calloc(1, stack).buffer(uboBuffer).offset(0).range(208L)
            val writes = VkWriteDescriptorSet.calloc(4, stack)
            writes[0].sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET).dstSet(set).dstBinding(0).descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1).pImageInfo(matInfo)
            writes[1].sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET).dstSet(set).dstBinding(1).descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1).pImageInfo(shadowInfo)
            writes[2].sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET).dstSet(set).dstBinding(2).descriptorType(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER).descriptorCount(1).pBufferInfo(bufInfo)
            writes[3].sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET).dstSet(set).dstBinding(3).descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1).pImageInfo(envInfo)
            vkUpdateDescriptorSets(device, writes, null)
            set
        }
        return Texture(image, mem, view, descriptorSet)
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

    private fun ensureTargets(w: Int, h: Int) {
        if (w == width && h == height && framebuffer != VK_NULL_HANDLE) return
        destroyTargets()
        width = w; height = h
        val (ci, cm) = createImage(w, h, colorFormat, VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT or VK_IMAGE_USAGE_TRANSFER_SRC_BIT, samples)
        colorImage = ci; colorMem = cm; colorView = createImageView(colorImage, colorFormat, VK_IMAGE_ASPECT_COLOR_BIT)
        val (di, dm) = createImage(w, h, depthFormat, VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT, samples)
        depthImage = di; depthMem = dm; depthView = createImageView(depthImage, depthFormat, VK_IMAGE_ASPECT_DEPTH_BIT)
        val (ri, rm) = createImage(w, h, colorFormat, VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT or VK_IMAGE_USAGE_TRANSFER_SRC_BIT, VK_SAMPLE_COUNT_1_BIT)
        resolveImage = ri; resolveMem = rm; resolveView = createImageView(resolveImage, colorFormat, VK_IMAGE_ASPECT_COLOR_BIT)
        MemoryStack.stackPush().use { stack ->
            val fbInfo = VkFramebufferCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO).renderPass(renderPass).pAttachments(stack.longs(colorView, depthView, resolveView)).width(w).height(h).layers(1)
            val p = stack.mallocLong(1)
            check(vkCreateFramebuffer(device, fbInfo, null, p) == VK_SUCCESS); framebuffer = p.get(0)
        }
        readbackSize = (w.toLong() * h * 4)
        val (rb, rbm) = createBuffer(readbackSize, VK_BUFFER_USAGE_TRANSFER_DST_BIT, hostVisible)
        readbackBuffer = rb; readbackMem = rbm
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

    private fun uploadKtxTexture(ktx: KtxLoader.KtxImage) {
        val (stgBuf, stgMem) = createBuffer(ktx.totalSize.toLong(), VK_BUFFER_USAGE_TRANSFER_SRC_BIT, hostVisible)
        MemoryStack.stackPush().use { stack ->
            val pp = stack.mallocPointer(1); vkMapMemory(device, stgMem, 0, ktx.totalSize.toLong(), 0, pp)
            pp.getByteBuffer(0, ktx.totalSize).put(ktx.data); vkUnmapMemory(device, stgMem)
        }

        MemoryStack.stackPush().use { stack ->
            val info = VkImageCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO).imageType(VK_IMAGE_TYPE_2D).format(VK_FORMAT_R16G16B16A16_SFLOAT)
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
            envImage = pImage.get(0); envMem = pMem.get(0)
        }

        singleTimeCommands { cmd ->
            val stack = MemoryStack.stackGet()
            val barrier = VkImageMemoryBarrier.calloc(1, stack).sType(VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER)
                .oldLayout(VK_IMAGE_LAYOUT_UNDEFINED).newLayout(VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL)
                .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                .image(envImage).srcAccessMask(0).dstAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT)
            barrier.subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).baseMipLevel(0).levelCount(ktx.mipLevels).baseArrayLayer(0).layerCount(ktx.faces)
            vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, 0, null, null, barrier)

            val regions = VkBufferImageCopy.calloc(ktx.mipLevels, stack)
            for (m in 0 until ktx.mipLevels) {
                regions[m].bufferOffset(ktx.mipOffsets[m].toLong()).bufferRowLength(0).bufferImageHeight(0)
                regions[m].imageSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(m).baseArrayLayer(0).layerCount(ktx.faces)
                regions[m].imageOffset().set(0, 0, 0)
                regions[m].imageExtent().set((ktx.width shr m).coerceAtLeast(1), (ktx.height shr m).coerceAtLeast(1), 1)
            }
            vkCmdCopyBufferToImage(cmd, stgBuf, envImage, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, regions)

            barrier.oldLayout(VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL).newLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL)
                .srcAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT).dstAccessMask(VK_ACCESS_SHADER_READ_BIT)
            vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT, 0, null, null, barrier)
        }
        destroyBuffer(stgBuf, stgMem)
        envView = createImageView(envImage, VK_FORMAT_R16G16B16A16_SFLOAT, VK_IMAGE_ASPECT_COLOR_BIT, VK_IMAGE_VIEW_TYPE_CUBE, ktx.faces, ktx.mipLevels)
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
        if (framebuffer != VK_NULL_HANDLE) vkDestroyFramebuffer(device, framebuffer, null); framebuffer = VK_NULL_HANDLE
        if (colorView != VK_NULL_HANDLE) vkDestroyImageView(device, colorView, null); colorView = VK_NULL_HANDLE
        if (depthView != VK_NULL_HANDLE) vkDestroyImageView(device, depthView, null); depthView = VK_NULL_HANDLE
        if (resolveView != VK_NULL_HANDLE) vkDestroyImageView(device, resolveView, null); resolveView = VK_NULL_HANDLE
        if (colorImage != VK_NULL_HANDLE) vkDestroyImage(device, colorImage, null); colorImage = VK_NULL_HANDLE
        if (depthImage != VK_NULL_HANDLE) vkDestroyImage(device, depthImage, null); depthImage = VK_NULL_HANDLE
        if (resolveImage != VK_NULL_HANDLE) vkDestroyImage(device, resolveImage, null); resolveImage = VK_NULL_HANDLE
        if (colorMem != VK_NULL_HANDLE) vkFreeMemory(device, colorMem, null); colorMem = VK_NULL_HANDLE
        if (depthMem != VK_NULL_HANDLE) vkFreeMemory(device, depthMem, null); depthMem = VK_NULL_HANDLE
        if (resolveMem != VK_NULL_HANDLE) vkFreeMemory(device, resolveMem, null); resolveMem = VK_NULL_HANDLE
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
        for (gb in groupBuffers.values) { destroyBuffer(gb.vBuf, gb.vMem); destroyBuffer(gb.iBuf, gb.iMem) }
        for (t in textures.values) {
            if (t.view != VK_NULL_HANDLE) vkDestroyImageView(device, t.view, null)
            if (t.image != VK_NULL_HANDLE) vkDestroyImage(device, t.image, null)
            if (t.mem != VK_NULL_HANDLE) vkFreeMemory(device, t.mem, null)
        }
        if (sampler != VK_NULL_HANDLE) vkDestroySampler(device, sampler, null)
        if (descriptorPool != VK_NULL_HANDLE) vkDestroyDescriptorPool(device, descriptorPool, null)
        if (descriptorSetLayout != VK_NULL_HANDLE) vkDestroyDescriptorSetLayout(device, descriptorSetLayout, null)
        destroyBuffer(uboBuffer, uboMem)
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
        if (envView != VK_NULL_HANDLE) vkDestroyImageView(device, envView, null)
        if (envImage != VK_NULL_HANDLE) vkDestroyImage(device, envImage, null)
        if (envMem != VK_NULL_HANDLE) vkFreeMemory(device, envMem, null)
        vkDestroyDevice(device, null)
        if (::instance.isInitialized) vkDestroyInstance(instance, null)
    }

    companion object {
        private const val FenStart = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"

        private const val VERT_GLSL = """
#version 450
layout(location = 0) in vec3 inPos;
layout(location = 1) in vec3 inNormal;
layout(location = 2) in vec2 inUv;
layout(location = 3) in vec3 inTint;
layout(set = 0, binding = 2) uniform UBO { mat4 viewProj; mat4 lightViewProj; vec4 camPos; } ubo;
layout(location = 0) out vec3 vNormal;
layout(location = 1) out vec2 vUv;
layout(location = 2) out vec3 vTint;
layout(location = 3) out vec3 vWorldPos;
void main() {
    gl_Position = ubo.viewProj * vec4(inPos, 1.0);
    vNormal = inNormal; vUv = inUv; vTint = inTint; vWorldPos = inPos;
}
"""

        private const val FRAG_GLSL = """
#version 450
layout(location = 0) in vec3 vNormal;
layout(location = 1) in vec2 vUv;
layout(location = 2) in vec3 vTint;
layout(location = 3) in vec3 vWorldPos;
layout(set = 0, binding = 0) uniform sampler2D tex;
layout(set = 0, binding = 1) uniform sampler2D shadowMap;
layout(set = 0, binding = 2) uniform UBO { mat4 viewProj; mat4 lightViewProj; vec4 camPos; mat4 invViewProj; } ubo;
layout(set = 0, binding = 3) uniform samplerCube envMap;
layout(location = 0) out vec4 outColor;

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

vec3 F_SchlickR(float cosTheta, vec3 F0, float roughness) {
    return F0 + (max(vec3(1.0 - roughness), F0) - F0) * pow(1.0 - cosTheta, 5.0);
}

void main() {
    vec3 N = normalize(vNormal);
    vec3 L = normalize(vec3(0.45, 1.0, 0.35));
    float ndl = max(dot(N, L), 0.0);
    vec3 V = normalize(ubo.camPos.xyz - vWorldPos);
    vec3 R = reflect(-V, N);

    float sh = shadowFactor(vWorldPos, ndl);
    
    float roughness = 0.25; // hardcoded average for glossy marble/wood
    vec3 base = texture(tex, vUv).rgb;
    vec3 albedo = base * vTint;
    
    vec3 F0 = vec3(0.04); // dielectric
    vec3 F = F_SchlickR(max(dot(N, V), 0.0), F0, roughness);
    vec3 kD = (1.0 - F);
    
    vec3 irradiance = textureLod(envMap, N, 8.0).rgb;
    vec3 diffuse = irradiance * albedo;
    
    vec3 prefiltered = textureLod(envMap, R, roughness * 9.0).rgb;
    // approximate BRDF lut for specular
    vec3 specular = prefiltered * (F * 1.0 + 0.0);
    
    vec3 ambient = (kD * diffuse + specular);
    vec3 lit = ambient + albedo * (0.75 * ndl * sh); // keep some directional light for shadow casting

    vec3 glow = max(vTint - vec3(1.0), 0.0) * base * 1.6;
    outColor = vec4(clamp(lit + glow, 0.0, 1.0), 1.0);
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
layout(set = 0, binding = 2) uniform UBO { mat4 viewProj; mat4 lightViewProj; vec4 camPos; mat4 invViewProj; } ubo;

void main() {
    vec2 p = vec2(float((gl_VertexIndex << 1) & 2), float(gl_VertexIndex & 2));
    vec2 ndc = p * 2.0 - 1.0;
    vec4 unprojected = ubo.invViewProj * vec4(ndc, 1.0, 1.0);
    vViewDir = unprojected.xyz / unprojected.w - ubo.camPos.xyz;
    gl_Position = vec4(ndc, 1.0, 1.0); // far plane, behind everything
}
"""

        private const val SKY_FRAG = """
#version 450
layout(location = 0) in vec3 vViewDir;
layout(set = 0, binding = 3) uniform samplerCube envMap;
layout(location = 0) out vec4 outColor;
void main() {
    outColor = vec4(textureLod(envMap, normalize(vViewDir), 0.0).rgb, 1.0);
}
"""
    }
}
