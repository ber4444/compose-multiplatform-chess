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
 * Desktop 3D backend: a headless (no window/swapchain) Vulkan renderer that draws the board +
 * pieces into an offscreen image, copies it back to host memory, and hands the RGBA pixels to the
 * attached [ImageBitmapChess3DSurface] as a Compose `ImageBitmap`. See overview Decision C/D.
 *
 * Design that keeps the Vulkan side minimal and robust (incl. on MoltenVK):
 *  - Everything is baked into ONE world-space, vertex-lit triangle buffer ([ChessSceneGeometry]),
 *    so a frame is a single indexed draw with a single `mat4 viewProj` push constant — no UBOs,
 *    no descriptor sets, no per-object state.
 *  - Geometry is rebuilt only when the FEN changes; camera changes only update the push constant.
 *  - All Vulkan calls run on one dedicated thread (Vulkan objects are thread-affine).
 *
 * Any failure during init throws, so the factory returns null and the UI falls back to 2D.
 */
@OptIn(DelicateCoroutinesApi::class, ExperimentalCoroutinesApi::class)
class VulkanChessRenderer(glb: ByteArray) : Chess3DBoardRenderer {

    private val renderDispatcher = newSingleThreadContext("chess3d-render")
    private val renderScope = CoroutineScope(renderDispatcher + Job())

    private val meshes: Map<PieceKind, MeshData>

    // Vulkan handles
    private lateinit var instance: VkInstance
    private lateinit var physicalDevice: VkPhysicalDevice
    private lateinit var device: VkDevice
    private lateinit var queue: VkQueue
    private var queueFamily = 0
    private var commandPool = VK_NULL_HANDLE
    private lateinit var commandBuffer: VkCommandBuffer
    private var renderPass = VK_NULL_HANDLE
    private var pipelineLayout = VK_NULL_HANDLE
    private var pipeline = VK_NULL_HANDLE
    private var fence = VK_NULL_HANDLE

    private val colorFormat = VK_FORMAT_R8G8B8A8_UNORM
    private val depthFormat = VK_FORMAT_D32_SFLOAT

    // Size-dependent targets
    private var width = 0
    private var height = 0
    private var colorImage = VK_NULL_HANDLE; private var colorMem = VK_NULL_HANDLE; private var colorView = VK_NULL_HANDLE
    private var depthImage = VK_NULL_HANDLE; private var depthMem = VK_NULL_HANDLE; private var depthView = VK_NULL_HANDLE
    private var framebuffer = VK_NULL_HANDLE
    private var readbackBuffer = VK_NULL_HANDLE; private var readbackMem = VK_NULL_HANDLE; private var readbackSize = 0L

    // Geometry buffers
    private var vertexBuffer = VK_NULL_HANDLE; private var vertexMem = VK_NULL_HANDLE; private var vertexCapacity = 0L
    private var indexBuffer = VK_NULL_HANDLE; private var indexMem = VK_NULL_HANDLE; private var indexCapacity = 0L
    private var indexCount = 0

    private var surface: ImageBitmapChess3DSurface? = null
    private var pendingFen: String? = null
    private var selectedSquare: BoardSquare? = null
    private var camera: CameraParams = OrbitCameraController.DEFAULT_WHITE_VIEW
    private var disposed = false

    init {
        runBlocking(renderDispatcher) { initVulkan() }
        meshes = GltfChessMeshes.load(glb)
        require(meshes.isNotEmpty()) { "No chess piece meshes found in glb" }
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

    override fun detach() {
        post { surface = null }
    }

    override fun updatePosition(fen: String) {
        pendingFen = fen
        post { if (surface != null) { rebuildGeometry(fen); renderFrame() } }
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

    // --- render-thread dispatch (work is serialized on the single render thread) ---

    private fun post(block: () -> Unit) {
        if (disposed) return
        renderScope.launch {
            runCatching { block() }.onFailure { co.touchlab.kermit.Logger.w(it) { "chess3d render step failed" } }
        }
    }

    private fun rebuildGeometry(fen: String) {
        val scene = Board3DSceneMapper.fromFen(fen).copy(selectedSquare = selectedSquare)
        val geo = ChessSceneGeometry.build(scene, meshes)
        uploadGeometry(geo)
    }

    private fun renderFrame() {
        val surf = surface ?: return
        if (width == 0 || height == 0 || indexCount == 0) return
        MemoryStack.stackPush().use { stack ->
            vkResetFences(device, stack.longs(fence))
            vkResetCommandBuffer(commandBuffer, 0)
            recordCommandBuffer(stack)
            val submit = VkSubmitInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_SUBMIT_INFO)
                .pCommandBuffers(stack.pointers(commandBuffer))
            check(vkQueueSubmit(queue, submit, fence) == VK_SUCCESS) { "vkQueueSubmit failed" }
            vkWaitForFences(device, stack.longs(fence), true, Long.MAX_VALUE)
        }
        surf.onFrame(readbackToImageBitmap())
    }

    private fun recordCommandBuffer(stack: MemoryStack) {
        val begin = VkCommandBufferBeginInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO)
        check(vkBeginCommandBuffer(commandBuffer, begin) == VK_SUCCESS)

        val clear = VkClearValue.calloc(2, stack)
        clear[0].color().float32(0, 0.10f).float32(1, 0.11f).float32(2, 0.13f).float32(3, 1f)
        clear[1].depthStencil().set(1f, 0)

        val area = VkRect2D.calloc(stack)
        area.offset().set(0, 0); area.extent().set(width, height)
        val rpBegin = VkRenderPassBeginInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO)
            .renderPass(renderPass).framebuffer(framebuffer).renderArea(area).pClearValues(clear)
        vkCmdBeginRenderPass(commandBuffer, rpBegin, VK_SUBPASS_CONTENTS_INLINE)

        vkCmdBindPipeline(commandBuffer, VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline)

        val viewport = VkViewport.calloc(1, stack)
            .x(0f).y(0f).width(width.toFloat()).height(height.toFloat()).minDepth(0f).maxDepth(1f)
        vkCmdSetViewport(commandBuffer, 0, viewport)
        val scissor = VkRect2D.calloc(1, stack)
        scissor.get(0).offset().set(0, 0)
        scissor.get(0).extent().set(width, height)
        vkCmdSetScissor(commandBuffer, 0, scissor)

        val vp = viewProjMatrix()
        val pc = stack.malloc(64)
        vp.get(pc.asFloatBuffer())
        vkCmdPushConstants(commandBuffer, pipelineLayout, VK_SHADER_STAGE_VERTEX_BIT, 0, pc)

        vkCmdBindVertexBuffers(commandBuffer, 0, stack.longs(vertexBuffer), stack.longs(0))
        vkCmdBindIndexBuffer(commandBuffer, indexBuffer, 0, VK_INDEX_TYPE_UINT32)
        vkCmdDrawIndexed(commandBuffer, indexCount, 1, 0, 0, 0)

        vkCmdEndRenderPass(commandBuffer)

        // Color image is now in TRANSFER_SRC_OPTIMAL (render-pass finalLayout); copy to host buffer.
        val region = VkBufferImageCopy.calloc(1, stack)
        region.bufferOffset(0).bufferRowLength(0).bufferImageHeight(0)
        region.imageSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1)
        region.imageOffset().set(0, 0, 0); region.imageExtent().set(width, height, 1)
        vkCmdCopyImageToBuffer(commandBuffer, colorImage, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, readbackBuffer, region)

        check(vkEndCommandBuffer(commandBuffer) == VK_SUCCESS)
    }

    private fun viewProjMatrix(): Matrix4f {
        val proj = Matrix4f().perspective(
            Math.toRadians(camera.fovYDegrees.toDouble()).toFloat(),
            (width.toFloat() / height.toFloat()).coerceAtLeast(0.01f),
            camera.near, camera.far, true,
        )
        proj.m11(proj.m11() * -1f) // Vulkan: clip-space Y points down
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
        val buf = pp.getByteBuffer(0, (width * height * 4))
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
            if (portability) {
                exts += VK_KHR_PORTABILITY_ENUMERATION_EXTENSION_NAME
                exts += VK_KHR_GET_PHYSICAL_DEVICE_PROPERTIES_2_EXTENSION_NAME
            }
            val pExts = if (exts.isEmpty()) null else stack.pointers(*exts.map { stack.UTF8(it) }.toTypedArray())
            val ici = VkInstanceCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO)
                .pApplicationInfo(appInfo).ppEnabledExtensionNames(pExts)
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

            queueFamily = findGraphicsQueueFamily(stack)

            val devExts = mutableListOf<String>()
            if (hasDeviceExtension(stack, "VK_KHR_portability_subset")) devExts += "VK_KHR_portability_subset"
            val pDevExts = if (devExts.isEmpty()) null else stack.pointers(*devExts.map { stack.UTF8(it) }.toTypedArray())
            val queueInfo = VkDeviceQueueCreateInfo.calloc(1, stack).sType(VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO)
                .queueFamilyIndex(queueFamily).pQueuePriorities(stack.floats(1f))
            val dci = VkDeviceCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO)
                .pQueueCreateInfos(queueInfo).ppEnabledExtensionNames(pDevExts)
            val pDevice = stack.mallocPointer(1)
            check(vkCreateDevice(physicalDevice, dci, null, pDevice) == VK_SUCCESS) { "vkCreateDevice failed" }
            device = VkDevice(pDevice.get(0), physicalDevice, dci)

            val pQueue = stack.mallocPointer(1)
            vkGetDeviceQueue(device, queueFamily, 0, pQueue)
            queue = VkQueue(pQueue.get(0), device)

            val poolInfo = VkCommandPoolCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO)
                .queueFamilyIndex(queueFamily).flags(VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT)
            val pPool = stack.mallocLong(1)
            check(vkCreateCommandPool(device, poolInfo, null, pPool) == VK_SUCCESS)
            commandPool = pPool.get(0)

            val cbAlloc = VkCommandBufferAllocateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO)
                .commandPool(commandPool).level(VK_COMMAND_BUFFER_LEVEL_PRIMARY).commandBufferCount(1)
            val pCb = stack.mallocPointer(1)
            check(vkAllocateCommandBuffers(device, cbAlloc, pCb) == VK_SUCCESS)
            commandBuffer = VkCommandBuffer(pCb.get(0), device)

            val fenceInfo = VkFenceCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_FENCE_CREATE_INFO)
            val pFence = stack.mallocLong(1)
            check(vkCreateFence(device, fenceInfo, null, pFence) == VK_SUCCESS)
            fence = pFence.get(0)

            createRenderPass(stack)
            createPipeline(stack)
        }
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
        for (i in 0 until count.get(0)) {
            if (props[i].queueFlags() and VK_QUEUE_GRAPHICS_BIT != 0) return i
        }
        error("no graphics queue family")
    }

    private fun createRenderPass(stack: MemoryStack) {
        val attachments = VkAttachmentDescription.calloc(2, stack)
        attachments[0].format(colorFormat).samples(VK_SAMPLE_COUNT_1_BIT)
            .loadOp(VK_ATTACHMENT_LOAD_OP_CLEAR).storeOp(VK_ATTACHMENT_STORE_OP_STORE)
            .stencilLoadOp(VK_ATTACHMENT_LOAD_OP_DONT_CARE).stencilStoreOp(VK_ATTACHMENT_STORE_OP_DONT_CARE)
            .initialLayout(VK_IMAGE_LAYOUT_UNDEFINED).finalLayout(VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL)
        attachments[1].format(depthFormat).samples(VK_SAMPLE_COUNT_1_BIT)
            .loadOp(VK_ATTACHMENT_LOAD_OP_CLEAR).storeOp(VK_ATTACHMENT_STORE_OP_DONT_CARE)
            .stencilLoadOp(VK_ATTACHMENT_LOAD_OP_DONT_CARE).stencilStoreOp(VK_ATTACHMENT_STORE_OP_DONT_CARE)
            .initialLayout(VK_IMAGE_LAYOUT_UNDEFINED).finalLayout(VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL)

        val colorRef = VkAttachmentReference.calloc(1, stack).attachment(0).layout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL)
        val depthRef = VkAttachmentReference.calloc(stack).attachment(1).layout(VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL)
        val subpass = VkSubpassDescription.calloc(1, stack).pipelineBindPoint(VK_PIPELINE_BIND_POINT_GRAPHICS)
            .colorAttachmentCount(1).pColorAttachments(colorRef).pDepthStencilAttachment(depthRef)

        val rpInfo = VkRenderPassCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_RENDER_PASS_CREATE_INFO)
            .pAttachments(attachments).pSubpasses(subpass)
        val p = stack.mallocLong(1)
        check(vkCreateRenderPass(device, rpInfo, null, p) == VK_SUCCESS) { "vkCreateRenderPass failed" }
        renderPass = p.get(0)
    }

    private fun createPipeline(stack: MemoryStack) {
        val vert = createShaderModule(stack, VERT_GLSL, Shaderc.shaderc_glsl_vertex_shader, "vert")
        val frag = createShaderModule(stack, FRAG_GLSL, Shaderc.shaderc_glsl_fragment_shader, "frag")

        val stages = VkPipelineShaderStageCreateInfo.calloc(2, stack)
        stages[0].sType(VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO).stage(VK_SHADER_STAGE_VERTEX_BIT).module(vert).pName(stack.UTF8("main"))
        stages[1].sType(VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO).stage(VK_SHADER_STAGE_FRAGMENT_BIT).module(frag).pName(stack.UTF8("main"))

        val binding = VkVertexInputBindingDescription.calloc(1, stack).binding(0).stride(6 * 4).inputRate(VK_VERTEX_INPUT_RATE_VERTEX)
        val attrs = VkVertexInputAttributeDescription.calloc(2, stack)
        attrs[0].location(0).binding(0).format(VK_FORMAT_R32G32B32_SFLOAT).offset(0)
        attrs[1].location(1).binding(0).format(VK_FORMAT_R32G32B32_SFLOAT).offset(3 * 4)
        val vertexInput = VkPipelineVertexInputStateCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO)
            .pVertexBindingDescriptions(binding).pVertexAttributeDescriptions(attrs)

        val inputAssembly = VkPipelineInputAssemblyStateCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO)
            .topology(VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST)
        val viewportState = VkPipelineViewportStateCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO)
            .viewportCount(1).scissorCount(1)
        val raster = VkPipelineRasterizationStateCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO)
            .polygonMode(VK_POLYGON_MODE_FILL).cullMode(VK_CULL_MODE_NONE).frontFace(VK_FRONT_FACE_COUNTER_CLOCKWISE).lineWidth(1f)
        val multisample = VkPipelineMultisampleStateCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO)
            .rasterizationSamples(VK_SAMPLE_COUNT_1_BIT)
        val depth = VkPipelineDepthStencilStateCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_PIPELINE_DEPTH_STENCIL_STATE_CREATE_INFO)
            .depthTestEnable(true).depthWriteEnable(true).depthCompareOp(VK_COMPARE_OP_LESS)
        val blendAttachment = VkPipelineColorBlendAttachmentState.calloc(1, stack)
            .colorWriteMask(VK_COLOR_COMPONENT_R_BIT or VK_COLOR_COMPONENT_G_BIT or VK_COLOR_COMPONENT_B_BIT or VK_COLOR_COMPONENT_A_BIT).blendEnable(false)
        val blend = VkPipelineColorBlendStateCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO)
            .pAttachments(blendAttachment)
        val dynamic = VkPipelineDynamicStateCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_PIPELINE_DYNAMIC_STATE_CREATE_INFO)
            .pDynamicStates(stack.ints(VK_DYNAMIC_STATE_VIEWPORT, VK_DYNAMIC_STATE_SCISSOR))

        val pushConstant = VkPushConstantRange.calloc(1, stack).stageFlags(VK_SHADER_STAGE_VERTEX_BIT).offset(0).size(64)
        val layoutInfo = VkPipelineLayoutCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO)
            .pPushConstantRanges(pushConstant)
        val pLayout = stack.mallocLong(1)
        check(vkCreatePipelineLayout(device, layoutInfo, null, pLayout) == VK_SUCCESS)
        pipelineLayout = pLayout.get(0)

        val pipelineInfo = VkGraphicsPipelineCreateInfo.calloc(1, stack).sType(VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO)
            .pStages(stages).pVertexInputState(vertexInput).pInputAssemblyState(inputAssembly)
            .pViewportState(viewportState).pRasterizationState(raster).pMultisampleState(multisample)
            .pDepthStencilState(depth).pColorBlendState(blend).pDynamicState(dynamic)
            .layout(pipelineLayout).renderPass(renderPass).subpass(0)
        val pPipeline = stack.mallocLong(1)
        check(vkCreateGraphicsPipelines(device, VK_NULL_HANDLE, pipelineInfo, null, pPipeline) == VK_SUCCESS) { "pipeline failed" }
        pipeline = pPipeline.get(0)

        vkDestroyShaderModule(device, vert, null)
        vkDestroyShaderModule(device, frag, null)
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
        Shaderc.shaderc_result_release(result)
        Shaderc.shaderc_compile_options_release(options)
        Shaderc.shaderc_compiler_release(compiler)
        return p.get(0)
    }

    private fun ensureTargets(w: Int, h: Int) {
        if (w == width && h == height && framebuffer != VK_NULL_HANDLE) return
        destroyTargets()
        width = w; height = h

        val (ci, cm) = createImage(w, h, colorFormat, VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT or VK_IMAGE_USAGE_TRANSFER_SRC_BIT)
        colorImage = ci; colorMem = cm; colorView = createImageView(colorImage, colorFormat, VK_IMAGE_ASPECT_COLOR_BIT)
        val (di, dm) = createImage(w, h, depthFormat, VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT)
        depthImage = di; depthMem = dm; depthView = createImageView(depthImage, depthFormat, VK_IMAGE_ASPECT_DEPTH_BIT)

        MemoryStack.stackPush().use { stack ->
            val fbInfo = VkFramebufferCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO)
                .renderPass(renderPass).pAttachments(stack.longs(colorView, depthView)).width(w).height(h).layers(1)
            val p = stack.mallocLong(1)
            check(vkCreateFramebuffer(device, fbInfo, null, p) == VK_SUCCESS)
            framebuffer = p.get(0)
        }
        readbackSize = (w.toLong() * h * 4)
        val (rb, rm) = createBuffer(readbackSize, VK_BUFFER_USAGE_TRANSFER_DST_BIT, VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT or VK_MEMORY_PROPERTY_HOST_COHERENT_BIT)
        readbackBuffer = rb; readbackMem = rm
    }

    private fun uploadGeometry(geo: ChessSceneGeometry) {
        indexCount = geo.indexCount
        if (geo.vertices.isEmpty() || geo.indices.isEmpty()) return
        val vBytes = geo.vertices.size.toLong() * 4
        val iBytes = geo.indices.size.toLong() * 4
        if (vBytes > vertexCapacity) {
            destroyBuffer(vertexBuffer, vertexMem)
            val (b, m) = createBuffer(vBytes, VK_BUFFER_USAGE_VERTEX_BUFFER_BIT, hostVisible)
            vertexBuffer = b; vertexMem = m; vertexCapacity = vBytes
        }
        if (iBytes > indexCapacity) {
            destroyBuffer(indexBuffer, indexMem)
            val (b, m) = createBuffer(iBytes, VK_BUFFER_USAGE_INDEX_BUFFER_BIT, hostVisible)
            indexBuffer = b; indexMem = m; indexCapacity = iBytes
        }
        writeFloats(vertexMem, geo.vertices)
        writeInts(indexMem, geo.indices)
    }

    private val hostVisible = VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT or VK_MEMORY_PROPERTY_HOST_COHERENT_BIT

    private fun writeFloats(mem: Long, data: FloatArray) = MemoryStack.stackPush().use { stack ->
        val pp = stack.mallocPointer(1)
        vkMapMemory(device, mem, 0, data.size.toLong() * 4, 0, pp)
        pp.getByteBuffer(0, data.size * 4).asFloatBuffer().put(data)
        vkUnmapMemory(device, mem)
    }

    private fun writeInts(mem: Long, data: IntArray) = MemoryStack.stackPush().use { stack ->
        val pp = stack.mallocPointer(1)
        vkMapMemory(device, mem, 0, data.size.toLong() * 4, 0, pp)
        pp.getByteBuffer(0, data.size * 4).asIntBuffer().put(data)
        vkUnmapMemory(device, mem)
    }

    private fun createImage(w: Int, h: Int, format: Int, usage: Int): Pair<Long, Long> = MemoryStack.stackPush().use { stack ->
        val info = VkImageCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO)
            .imageType(VK_IMAGE_TYPE_2D).format(format).mipLevels(1).arrayLayers(1)
            .samples(VK_SAMPLE_COUNT_1_BIT).tiling(VK_IMAGE_TILING_OPTIMAL).usage(usage)
            .sharingMode(VK_SHARING_MODE_EXCLUSIVE).initialLayout(VK_IMAGE_LAYOUT_UNDEFINED)
        info.extent().set(w, h, 1)
        val pImage = stack.mallocLong(1)
        check(vkCreateImage(device, info, null, pImage) == VK_SUCCESS)
        val req = VkMemoryRequirements.calloc(stack)
        vkGetImageMemoryRequirements(device, pImage.get(0), req)
        val alloc = VkMemoryAllocateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO)
            .allocationSize(req.size()).memoryTypeIndex(findMemoryType(stack, req.memoryTypeBits(), VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT))
        val pMem = stack.mallocLong(1)
        check(vkAllocateMemory(device, alloc, null, pMem) == VK_SUCCESS)
        vkBindImageMemory(device, pImage.get(0), pMem.get(0), 0)
        pImage.get(0) to pMem.get(0)
    }

    private fun createImageView(image: Long, format: Int, aspect: Int): Long = MemoryStack.stackPush().use { stack ->
        val info = VkImageViewCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO)
            .image(image).viewType(VK_IMAGE_VIEW_TYPE_2D).format(format)
        info.subresourceRange().aspectMask(aspect).baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1)
        val p = stack.mallocLong(1)
        check(vkCreateImageView(device, info, null, p) == VK_SUCCESS)
        p.get(0)
    }

    private fun createBuffer(size: Long, usage: Int, memProps: Int): Pair<Long, Long> = MemoryStack.stackPush().use { stack ->
        val info = VkBufferCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO)
            .size(size).usage(usage).sharingMode(VK_SHARING_MODE_EXCLUSIVE)
        val pBuf = stack.mallocLong(1)
        check(vkCreateBuffer(device, info, null, pBuf) == VK_SUCCESS)
        val req = VkMemoryRequirements.calloc(stack)
        vkGetBufferMemoryRequirements(device, pBuf.get(0), req)
        val alloc = VkMemoryAllocateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO)
            .allocationSize(req.size()).memoryTypeIndex(findMemoryType(stack, req.memoryTypeBits(), memProps))
        val pMem = stack.mallocLong(1)
        check(vkAllocateMemory(device, alloc, null, pMem) == VK_SUCCESS)
        vkBindBufferMemory(device, pBuf.get(0), pMem.get(0), 0)
        pBuf.get(0) to pMem.get(0)
    }

    private fun findMemoryType(stack: MemoryStack, typeBits: Int, props: Int): Int {
        val memProps = VkPhysicalDeviceMemoryProperties.calloc(stack)
        vkGetPhysicalDeviceMemoryProperties(physicalDevice, memProps)
        for (i in 0 until memProps.memoryTypeCount()) {
            if (typeBits and (1 shl i) != 0 && memProps.memoryTypes(i).propertyFlags() and props == props) return i
        }
        error("no suitable memory type")
    }

    private fun destroyTargets() {
        if (framebuffer != VK_NULL_HANDLE) vkDestroyFramebuffer(device, framebuffer, null); framebuffer = VK_NULL_HANDLE
        if (colorView != VK_NULL_HANDLE) vkDestroyImageView(device, colorView, null); colorView = VK_NULL_HANDLE
        if (depthView != VK_NULL_HANDLE) vkDestroyImageView(device, depthView, null); depthView = VK_NULL_HANDLE
        if (colorImage != VK_NULL_HANDLE) vkDestroyImage(device, colorImage, null); colorImage = VK_NULL_HANDLE
        if (depthImage != VK_NULL_HANDLE) vkDestroyImage(device, depthImage, null); depthImage = VK_NULL_HANDLE
        if (colorMem != VK_NULL_HANDLE) vkFreeMemory(device, colorMem, null); colorMem = VK_NULL_HANDLE
        if (depthMem != VK_NULL_HANDLE) vkFreeMemory(device, depthMem, null); depthMem = VK_NULL_HANDLE
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
        destroyBuffer(vertexBuffer, vertexMem); destroyBuffer(indexBuffer, indexMem)
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

        private const val VERT_GLSL = """
#version 450
layout(location = 0) in vec3 inPos;
layout(location = 1) in vec3 inColor;
layout(push_constant) uniform PC { mat4 viewProj; } pc;
layout(location = 0) out vec3 fragColor;
void main() {
    gl_Position = pc.viewProj * vec4(inPos, 1.0);
    fragColor = inColor;
}
"""

        private const val FRAG_GLSL = """
#version 450
layout(location = 0) in vec3 fragColor;
layout(location = 0) out vec4 outColor;
void main() { outColor = vec4(fragColor, 1.0); }
"""
    }
}
