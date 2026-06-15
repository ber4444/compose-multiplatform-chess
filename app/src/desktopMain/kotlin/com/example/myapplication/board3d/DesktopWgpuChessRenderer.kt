package com.example.myapplication.board3d

import darwin.CAMetalLayer
import ffi.JvmNativeAddress
import io.ygdrasil.webgpu.*
import kotlinx.coroutines.*
import java.lang.foreign.MemorySegment
import io.ygdrasil.wgpu.wgpuSetLogLevel
import ffi.LibraryLoader
import androidx.compose.ui.graphics.ImageBitmap

import org.joml.Matrix4f

const val FenStart = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"

class DesktopWgpuChessRenderer(glb: ByteArray) : Chess3DBoardRenderer {

    private val meshes: Map<PieceKind, MeshData>
    private val textureImages: Map<ChessTexture, TextureImage>

    private var wgpu: WGPU? = null
    private var adapter: Adapter? = null
    private var device: Device? = null
    private var surfaceWrapper: NativeSurface? = null
    private var metalLayer: Any? = null
    private var renderJob: Job? = null

    // All wgpu/device work is confined to ONE thread: wgpu device access must not be interleaved
    // across threads, and the render loop + geometry uploads must be serialized. Mirrors the working
    // VulkanChessRenderer.renderDispatcher.
    @OptIn(DelicateCoroutinesApi::class)
    private val renderDispatcher = newSingleThreadContext("wgpu-desktop-render")
    private val scope = CoroutineScope(renderDispatcher + SupervisorJob())

    private var pendingFen: String? = null
    private var selectedSquare: BoardSquare? = null
    private var camera: CameraParams = OrbitCameraController.DEFAULT_WHITE_VIEW

    private class GroupBuffers {
        var vBuf: GPUBuffer? = null
        var iBuf: GPUBuffer? = null
        var indexCount = 0
        var vCap = 0UL
        var iCap = 0UL
    }
    private val groupBuffers = HashMap<ChessTexture, GroupBuffers>()

    private class TextureGroup {
        var image: GPUTexture? = null
        var view: GPUTextureView? = null
        var bindGroup: GPUBindGroup? = null
        var materialBuffer: GPUBuffer? = null
    }
    private val textures = HashMap<ChessTexture, TextureGroup>()

    private var uniformBuffer: GPUBuffer? = null
    private var sampler: GPUSampler? = null
    private var skyPipeline: GPURenderPipeline? = null
    private var renderPipeline: GPURenderPipeline? = null

    // Environment cubemap (papermill HDR) for the skybox background + IBL — the vkChess look.
    private var envTexture: GPUTexture? = null
    private var envView: GPUTextureView? = null
    private var envSampler: GPUSampler? = null

    init {
        meshes = GltfChessMeshes.load(glb)
        require(meshes.isNotEmpty()) { "No chess piece meshes found in glb" }
        textureImages = GltfChessTextures.load(glb)
    }

    override fun attach(surface: Chess3DSurface) {
        require(surface is ImageBitmapChess3DSurface) { "Expected ImageBitmapChess3DSurface" }
        val previous = renderJob
        renderJob = scope.launch {
            // DesktopBoard3DSurface recreates the surface (and re-runs attach) when the layout size
            // changes from (1,1) to the real size. Fully stop + release the previous render loop
            // before starting a new one, so two loops never push frames to the same Compose state
            // concurrently — that overlap was the "flashing".
            previous?.cancelAndJoin()
            try {
                runRenderLoop(surface)
            } finally {
                releaseGpu()
            }
        }
    }

    private suspend fun runRenderLoop(surface: ImageBitmapChess3DSurface) {
        LibraryLoader.load()
        wgpuSetLogLevel(1u)
        
        wgpu = WGPU.createInstance() ?: error("Failed to create WGPU instance")
        val layer = CAMetalLayer.layer() ?: error("Failed to create CAMetalLayer")
        metalLayer = layer
        val layerAddr = (layer.id() as Number).toLong()
        val nativeAddress = JvmNativeAddress(MemorySegment.ofAddress(layerAddr))
        
        surfaceWrapper = wgpu!!.getSurfaceFromMetalLayer(nativeAddress) 
            ?: error("Failed to create NativeSurface")
            
        adapter = wgpu!!.requestAdapter(surfaceWrapper!!, GPUPowerPreference.HighPerformance)
            ?: error("Failed to request adapter")
            
        val deviceResult = adapter!!.requestDevice()
        device = deviceResult.getOrThrow() as Device

        val shaderModule = device!!.createShaderModule(
            ShaderModuleDescriptor(code = WGPU_SHADER)
        )
        
        val format = GPUTextureFormat.RGBA8Unorm

        renderPipeline = device!!.createRenderPipeline(
            RenderPipelineDescriptor(
                vertex = VertexState(
                    entryPoint = "vs_main",
                    module = shaderModule,
                    buffers = listOf(
                        VertexBufferLayout(
                            arrayStride = 44uL,
                            attributes = listOf(
                                VertexAttribute(shaderLocation = 0u, offset = 0uL, format = GPUVertexFormat.Float32x3),
                                VertexAttribute(shaderLocation = 1u, offset = 12uL, format = GPUVertexFormat.Float32x3),
                                VertexAttribute(shaderLocation = 2u, offset = 24uL, format = GPUVertexFormat.Float32x2),
                                VertexAttribute(shaderLocation = 3u, offset = 32uL, format = GPUVertexFormat.Float32x3)
                            )
                        )
                    )
                ),
                fragment = FragmentState(
                    entryPoint = "fs_main",
                    module = shaderModule,
                    targets = listOf(ColorTargetState(format = format))
                ),
                primitive = PrimitiveState(
                    topology = GPUPrimitiveTopology.TriangleList,
                    // No culling: the shared ChessSceneGeometry winds the flat board/ground quads
                    // front-down (visible top = back face) while the glTF piece meshes are wound
                    // front-out. With one cull setting those two are inconsistent (Back shows pieces
                    // but culls the board); None draws both correctly (solid pieces rely on depth).
                    cullMode = GPUCullMode.None
                ),
                depthStencil = DepthStencilState(
                    format = GPUTextureFormat.Depth24Plus,
                    depthWriteEnabled = true,
                    depthCompare = GPUCompareFunction.Less
                )
            )
        )

        createUniformBuffer()
        uploadAllTextures()
        
        sampler = device!!.createSampler(
            SamplerDescriptor(
                magFilter = GPUFilterMode.Linear,
                minFilter = GPUFilterMode.Linear,
            )
        )
        
        uploadEnvCube()
        
        for ((_, tg) in textures) {
            tg.bindGroup = device!!.createBindGroup(
                BindGroupDescriptor(
                    layout = renderPipeline!!.getBindGroupLayout(0u),
                    entries = listOf(
                        BindGroupEntry(binding = 0u, resource = tg.view!!),
                        BindGroupEntry(binding = 1u, resource = BufferBinding(buffer = uniformBuffer!!)),
                        BindGroupEntry(binding = 2u, resource = sampler!!),
                        BindGroupEntry(binding = 3u, resource = envView!!),
                        BindGroupEntry(binding = 4u, resource = envSampler!!),
                        BindGroupEntry(binding = 5u, resource = BufferBinding(buffer = tg.materialBuffer!!))
                    )
                )
            )
        }

        // --- Environment cubemap + skybox (vkChess papermill background) ---
        val skyModule = device!!.createShaderModule(ShaderModuleDescriptor(code = SKY_SHADER))
        skyPipeline = device!!.createRenderPipeline(
            RenderPipelineDescriptor(
                vertex = VertexState(entryPoint = "vs_sky", module = skyModule, buffers = emptyList()),
                fragment = FragmentState(
                    entryPoint = "fs_sky",
                    module = skyModule,
                    targets = listOf(ColorTargetState(format = format))
                ),
                primitive = PrimitiveState(topology = GPUPrimitiveTopology.TriangleList, cullMode = GPUCullMode.None),
                // Drawn first at the far plane; no depth write so the scene (depth Less) draws over it.
                depthStencil = DepthStencilState(
                    format = GPUTextureFormat.Depth24Plus,
                    depthWriteEnabled = false,
                    depthCompare = GPUCompareFunction.Always
                )
            )
        )
        val skyBindGroup = device!!.createBindGroup(
            BindGroupDescriptor(
                layout = skyPipeline!!.getBindGroupLayout(0u),
                entries = listOf(
                    BindGroupEntry(binding = 0u, resource = BufferBinding(buffer = uniformBuffer!!)),
                    BindGroupEntry(binding = 1u, resource = envView!!),
                    BindGroupEntry(binding = 2u, resource = envSampler!!)
                )
            )
        )

        rebuildGeometry(FenStart)
        
        // Capture size once; textures/staging below are sized to it. On resize the surface is
        // recreated and attach() restarts this loop at the new size.
        val w = surface.widthPx
        val h = surface.heightPx
        val width = w.toUInt()
        val height = h.toUInt()
        val depthTexture = device!!.createTexture(
            TextureDescriptor(
                size = Extent3D(width, height, 1u),
                format = GPUTextureFormat.Depth24Plus,
                usage = GPUTextureUsage.RenderAttachment
            )
        )

        val texture = device!!.createTexture(
            TextureDescriptor(
                label = "Chess3DOffscreen",
                size = Extent3D(width, height, 1u),
                format = format,
                usage = GPUTextureUsage.RenderAttachment or GPUTextureUsage.CopySrc
            )
        )

        val bytesPerRow = (width * 4u + 255u) and (255u).inv()
        val textureDataSize = (bytesPerRow * height).toULong()
        val outputStagingBuffer = device!!.createBuffer(
            BufferDescriptor(
                size = textureDataSize,
                usage = GPUBufferUsage.CopyDst or GPUBufferUsage.MapRead,
                mappedAtCreation = false,
            )
        )

        while (currentCoroutineContext().isActive) {
            updateUniforms(w, h)

            val encoder = device!!.createCommandEncoder()
            
            val pass = encoder.beginRenderPass(
                RenderPassDescriptor(
                    colorAttachments = listOf(
                        RenderPassColorAttachment(
                            view = texture.createView(),
                            loadOp = GPULoadOp.Clear,
                            clearValue = Color(0.1, 0.2, 0.3, 1.0),
                            storeOp = GPUStoreOp.Store
                        )
                    ),
                    depthStencilAttachment = RenderPassDepthStencilAttachment(
                        view = depthTexture.createView(),
                        depthClearValue = 1.0f,
                        depthLoadOp = GPULoadOp.Clear,
                        depthStoreOp = GPUStoreOp.Store
                    )
                )
            )
            // Skybox first (fills the background; no depth write), then the scene over it.
            pass.setPipeline(skyPipeline!!)
            pass.setBindGroup(0u, skyBindGroup)
            pass.draw(3u)

            pass.setPipeline(renderPipeline!!)

            for (tex in ChessTexture.entries) {
                val gb = groupBuffers[tex] ?: continue
                if (gb.indexCount == 0) continue
                val tg = textures[tex] ?: continue
                val bindGroup = tg.bindGroup ?: continue
                
                pass.setBindGroup(0u, bindGroup)
                pass.setVertexBuffer(0u, gb.vBuf!!)
                pass.setIndexBuffer(gb.iBuf!!, GPUIndexFormat.Uint32)
                pass.drawIndexed(gb.indexCount.toUInt())
            }
            pass.end()

            encoder.copyTextureToBuffer(
                TexelCopyTextureInfo(
                    texture = texture,
                    mipLevel = 0u,
                    origin = Origin3D(),
                    aspect = GPUTextureAspect.All,
                ),
                TexelCopyBufferInfo(
                    buffer = outputStagingBuffer,
                    offset = 0u,
                    bytesPerRow = bytesPerRow,
                    rowsPerImage = height,
                ),
                Extent3D(width, height, 1u)
            )

            val commandBuffer = encoder.finish()
            device!!.queue.submit(listOf(commandBuffer))

            // Wait for GPU to finish and map the buffer safely using direct FFI
            safeMapAsync(outputStagingBuffer as io.ygdrasil.webgpu.Buffer, device!!, GPUMapMode.Read, 0uL, textureDataSize)
            
            val mappedRange = outputStagingBuffer.getMappedRange()
            val byteArray = mappedRange.toByteArray()
            
            val imageBitmap = rgbaBytesToImageBitmap(byteArray, w, h, bytesPerRow.toInt())
            surface.onFrame(imageBitmap)

            outputStagingBuffer.unmap()

            delay(16) // ~60fps; also a cancellation point so detach() stops the loop promptly
        }
    }

    override fun detach() {
        // Just stop the loop; GPU resources are freed in runRenderLoop's finally (releaseGpu) on the
        // render thread AFTER the loop has actually stopped — avoids closing the device out from under
        // an in-flight frame (which produced the hs_err crashes / garbage frames). Keep the job ref so
        // dispose() can join it, and so a re-attach can cancelAndJoin the previous loop first.
        renderJob?.cancel()
    }

    private fun releaseGpu() {
        groupBuffers.clear()
        textures.clear()
        uniformBuffer = null
        sampler = null
        renderPipeline = null
        skyPipeline = null
        envView = null
        envSampler = null
        envTexture = null
        device?.close(); device = null
        adapter?.close(); adapter = null
        surfaceWrapper = null
        wgpu?.close(); wgpu = null
        metalLayer = null
    }

    override fun updatePosition(fen: String) {
        pendingFen = fen
        // The render loop constantly runs, so we just rebuild the geometry which will be picked up
        scope.launch {
            rebuildGeometry(fen)
        }
    }

    override fun setSelectedSquare(square: BoardSquare?) {
        if (square == selectedSquare) return
        selectedSquare = square
        scope.launch {
            rebuildGeometry(pendingFen ?: FenStart)
        }
    }

    override fun onUserInteraction(event: Board3DInput) {
        when (event) {
            is Board3DInput.SetCamera -> {
                camera = event.camera
            }
            is Board3DInput.Resize -> {
                camera = camera.copy(aspect = event.widthPx.toFloat() / event.heightPx.coerceAtLeast(1).toFloat())
            }
            else -> {}
        }
    }
    
    private suspend fun rebuildGeometry(fen: String) {
        if (device == null) return
        val scene = Board3DSceneMapper.fromFen(fen).copy(selectedSquare = selectedSquare)
        // No giant ground plane: the skybox is the background (vkChess look), so the board sits in the
        // environment rather than on a grey floor that would occlude the sky.
        val geo = ChessSceneGeometry.build(scene, meshes, includeGround = false)
        for ((tex, group) in geo.groups) {
            uploadGroup(tex, group)
        }
    }
    
    private suspend fun uploadGroup(tex: ChessTexture, group: SceneGroup) {
        val gb = groupBuffers.getOrPut(tex) { GroupBuffers() }
        gb.indexCount = group.indexCount
        if (group.vertices.isEmpty() || group.indices.isEmpty()) { gb.indexCount = 0; return }
        
        val vBytes = (group.vertices.size * 4).toULong()
        val iBytes = (group.indices.size * 4).toULong()
        
        if (vBytes > gb.vCap) {
            gb.vBuf?.close()
            gb.vBuf = device!!.createBuffer(
                BufferDescriptor(
                    size = vBytes,
                    usage = GPUBufferUsage.Vertex or GPUBufferUsage.CopyDst,
                )
            )
            gb.vCap = vBytes
        }
        
        if (iBytes > gb.iCap) {
            gb.iBuf?.close()
            gb.iBuf = device!!.createBuffer(
                BufferDescriptor(
                    size = iBytes,
                    usage = GPUBufferUsage.Index or GPUBufferUsage.CopyDst,
                )
            )
            gb.iCap = iBytes
        }
        
        // Write data
        device!!.queue.writeBuffer(gb.vBuf!!, 0u, group.vertices, 0u, group.vertices.size.toULong())
        device!!.queue.writeBuffer(gb.iBuf!!, 0u, group.indices, 0u, group.indices.size.toULong())
    }

    private suspend fun uploadAllTextures() {
        if (device == null) return
        for (tex in ChessTexture.entries) {
            val img = textureImages[tex] ?: continue
            textures[tex] = uploadTexture(tex, img)
        }
    }

    private suspend fun uploadTexture(tex: ChessTexture, img: TextureImage): TextureGroup {
        val tg = TextureGroup()
        val width = img.width.toUInt()
        val height = img.height.toUInt()
        val texSize = Extent3D(width, height, 1u)
        
        val texture = device!!.createTexture(
            TextureDescriptor(
                size = texSize,
                format = GPUTextureFormat.RGBA8Unorm,
                usage = GPUTextureUsage.TextureBinding or GPUTextureUsage.CopyDst
            )
        )
        
        val bytesPerRow = width * 4u
        val size = bytesPerRow * height
        
        val stagingBuffer = device!!.createBuffer(
            BufferDescriptor(
                size = size.toULong(),
                usage = GPUBufferUsage.CopySrc or GPUBufferUsage.MapWrite,
                mappedAtCreation = true
            )
        )
        
        val mappedRange = stagingBuffer.getMappedRange()
        mappedRange.setBytes(0uL, img.rgba)
        stagingBuffer.unmap()
        
        val encoder = device!!.createCommandEncoder()
        encoder.copyBufferToTexture(
            TexelCopyBufferInfo(
                buffer = stagingBuffer,
                bytesPerRow = bytesPerRow,
                rowsPerImage = height
            ),
            TexelCopyTextureInfo(
                texture = texture
            ),
            texSize
        )
        val commandBuffer = encoder.finish()
        device!!.queue.submit(listOf(commandBuffer))
        
        // Wait for copy before freeing
        // For simplicity, we just close it, WebGPU handles it
        stagingBuffer.close()
        
        tg.image = texture
        tg.view = texture.createView()
        
        val matBuffer = device!!.createBuffer(
            BufferDescriptor(
                size = 16uL,
                usage = GPUBufferUsage.Uniform or GPUBufferUsage.CopyDst,
            )
        )
        val roughness = if (tex == ChessTexture.BOARD) 0.25f else 0.45f
        val matData = floatArrayOf(roughness, 0f, 0f, 0f)
        device!!.queue.writeBuffer(matBuffer, 0u, matData, 0u, 4uL)
        tg.materialBuffer = matBuffer
        
        return tg
    }
    
    /** Loads `papermill_hdr16f_cube.ktx` into a wgpu cube texture (RGBA16F, all mips) for skybox + IBL. */
    private fun uploadEnvCube() {
        val bytes = this::class.java.getResourceAsStream("/papermill_hdr16f_cube.ktx")?.readBytes()
            ?: error("papermill_hdr16f_cube.ktx not found on classpath")
        val ktx = KtxLoader.load(bytes) ?: error("Failed to parse env cubemap KTX")
        val tex = device!!.createTexture(
            TextureDescriptor(
                size = Extent3D(ktx.width.toUInt(), ktx.height.toUInt(), 6u),
                format = GPUTextureFormat.RGBA16Float,
                usage = GPUTextureUsage.TextureBinding or GPUTextureUsage.CopyDst,
                mipLevelCount = ktx.mipLevels.toUInt(),
            )
        )
        // KTX cube layout: per mip, the 6 faces are stored contiguously. RGBA16F = 8 bytes/texel.
        // queue.writeTexture (unlike copyBufferToTexture) allows arbitrary bytesPerRow, so the small
        // mips that aren't 256-aligned upload fine.
        for (m in 0 until ktx.mipLevels) {
            val mipW = (ktx.width shr m).coerceAtLeast(1)
            val mipH = (ktx.height shr m).coerceAtLeast(1)
            val faceSize = ktx.mipSizes[m] / 6
            for (face in 0 until 6) {
                val arr = ByteArray(faceSize)
                ktx.data.duplicate().apply { position(ktx.mipOffsets[m] + face * faceSize) }.get(arr)
                device!!.queue.writeTexture(
                    TexelCopyTextureInfo(texture = tex, mipLevel = m.toUInt(), origin = Origin3D(0u, 0u, face.toUInt())),
                    ArrayBuffer.of(arr),
                    TexelCopyBufferLayout(offset = 0uL, bytesPerRow = (mipW * 8).toUInt(), rowsPerImage = mipH.toUInt()),
                    Extent3D(mipW.toUInt(), mipH.toUInt(), 1u),
                )
            }
        }
        ktx.free()
        envTexture = tex
        envView = tex.createView(
            TextureViewDescriptor(dimension = GPUTextureViewDimension.Cube, arrayLayerCount = 6u)
        )
        envSampler = device!!.createSampler(
            SamplerDescriptor(
                magFilter = GPUFilterMode.Linear,
                minFilter = GPUFilterMode.Linear,
                mipmapFilter = GPUMipmapFilterMode.Linear,
                addressModeU = GPUAddressMode.ClampToEdge,
                addressModeV = GPUAddressMode.ClampToEdge,
                addressModeW = GPUAddressMode.ClampToEdge,
            )
        )
    }

    private fun createUniformBuffer() {
        if (device == null) return
        val size = 64uL * 4uL // 4 mat4x4
        uniformBuffer = device!!.createBuffer(
            BufferDescriptor(
                size = size,
                usage = GPUBufferUsage.Uniform or GPUBufferUsage.CopyDst,
            )
        )
    }

    private fun viewProjMatrix(width: Int, height: Int): Matrix4f {
        val aspect = (width.toFloat() / height.toFloat()).coerceAtLeast(0.01f)
        val proj = Matrix4f().perspective(
            Math.toRadians(camera.fovYDegrees.toDouble()).toFloat(),
            aspect,
            camera.near, camera.far, true,
        )
        // WebGPU clip-space Y points up (unlike Vulkan), so JOML's y-up perspective needs NO Y flip.
        // (The Vulkan renderer flips m11 AND culls FRONT; WebGPU keeps default winding + cull BACK.)
        val view = Matrix4f().lookAt(
            camera.position.x, camera.position.y, camera.position.z,
            camera.target.x, camera.target.y, camera.target.z,
            camera.up.x, camera.up.y, camera.up.z,
        )
        return proj.mul(view)
    }

    private fun lightViewProj(): Matrix4f {
        val lightDir = org.joml.Vector3f(0.45f, 1.0f, 0.35f).normalize()
        val lightPos = org.joml.Vector3f(camera.target.x, camera.target.y, camera.target.z).add(org.joml.Vector3f(lightDir).mul(30f))
        val proj = Matrix4f().ortho(-20f, 20f, -20f, 20f, 0.1f, 100f, true)
        val view = Matrix4f().lookAt(
            lightPos, 
            org.joml.Vector3f(camera.target.x, camera.target.y, camera.target.z), 
            org.joml.Vector3f(0f, 1f, 0f)
        )
        return proj.mul(view)
    }

    private fun updateUniforms(width: Int, height: Int) {
        val ub = uniformBuffer ?: return
        if (width == 0 || height == 0) return
        
        val viewProj = viewProjMatrix(width, height)
        val lightVP = lightViewProj()
        val invViewProj = Matrix4f(viewProj).invert()
        
        val data = FloatArray(64)
        viewProj.get(data, 0)
        lightVP.get(data, 16)
        data[32] = camera.position.x
        data[33] = camera.position.y
        data[34] = camera.position.z
        data[35] = 1.0f
        // std140: camPos is a vec4 (bytes 128-143), so invViewProj (mat4) starts at byte 144 = float 36
        // — NOT float 48. The sky shader reads ubo.invViewProj at that offset; writing it at 48 left the
        // shader reading zero-padding, collapsing all view directions (uniform sky).
        invViewProj.get(data, 36)
        
        device!!.queue.writeBuffer(ub, 0u, data, 0u, data.size.toULong())
    }

    override fun dispose() {
        // Stop the loop and let its finally (releaseGpu) finish on the render thread before tearing
        // down the single-thread dispatcher.
        runBlocking { renderJob?.cancelAndJoin() }
        scope.cancel()
        renderDispatcher.close()
    }

    private fun safeMapAsync(buffer: io.ygdrasil.webgpu.Buffer, device: Device, mode: GPUMapMode, offset: GPUSize64, size: GPUSize64) {
        var mapped = false
        ffi.memoryScope { scope ->
            val callback = io.ygdrasil.wgpu.WGPUBufferMapCallback.allocate(scope, object : io.ygdrasil.wgpu.WGPUBufferMapCallback {
                override fun invoke(status: io.ygdrasil.wgpu.WGPUMapAsyncStatus, message: io.ygdrasil.wgpu.WGPUStringView?, userdata1: ffi.NativeAddress?, userdata2: ffi.NativeAddress?) {
                    mapped = true
                }
            })
            val info = io.ygdrasil.wgpu.WGPUBufferMapCallbackInfo.allocate(scope).apply {
                this.callback = callback
                this.userdata2 = callback.handler
            }
            io.ygdrasil.wgpu.wgpuBufferMapAsync(buffer.handler, mode.value, offset, size, info)
            while (!mapped) {
                io.ygdrasil.wgpu.wgpuDevicePoll(device.handler, true, null)
            }
        }
    }
}
