package com.example.myapplication.board3d

import kotlinx.coroutines.*
import com.example.myapplication.board3d.math.Matrix4f
import com.example.myapplication.board3d.math.Vector3f
import kotlin.math.PI

class WebGpuChessRenderer(glb: ByteArray) : Chess3DBoardRenderer {

    private var meshes: Map<PieceKind, MeshData>? = null
    private var textureImages: Map<ChessTexture, TextureImage>? = null
    private val glbData = glb

    private var adapter: JsAny? = null
    private var device: JsAny? = null
    private var context: JsAny? = null
    private var renderJob: Job? = null

    // Run everything on the main thread for Wasm JS
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private var pendingFen: String? = null
    private var selectedSquare: BoardSquare? = null
    private var camera: CameraParams = OrbitCameraController.DEFAULT_WHITE_VIEW

    private class GroupBuffers {
        var vBuf: JsAny? = null
        var iBuf: JsAny? = null
        var indexCount = 0
        var vCap = 0UL
        var iCap = 0UL
    }
    private val groupBuffers = HashMap<ChessTexture, GroupBuffers>()

    private class TextureGroup {
        var image: JsAny? = null
        var view: JsAny? = null
        var bindGroup: JsAny? = null
        var materialBuffer: JsAny? = null
    }
    private val textures = HashMap<ChessTexture, TextureGroup>()

    private var uniformBuffer: JsAny? = null
    private var sampler: JsAny? = null
    private var skyPipeline: JsAny? = null
    private var renderPipeline: JsAny? = null

    private var envTexture: JsAny? = null
    private var envView: JsAny? = null
    private var envSampler: JsAny? = null

    override fun attach(surface: Chess3DSurface) {
        require(surface is WasmChess3DSurface) { "Expected WasmChess3DSurface" }
        val previous = renderJob
        renderJob = scope.launch {
            previous?.cancel()
            try {
                if (meshes == null || textureImages == null) {
                    meshes = WasmGltfLoader.loadMeshes(glbData)
                    textureImages = WasmGltfLoader.loadTextures(glbData)
                }
                runRenderLoop(surface)
            } finally {
                releaseGpu()
            }
        }
    }

    private suspend fun runRenderLoop(surface: WasmChess3DSurface) {
        val navigatorGpu = getNavigatorGpu() ?: error("WebGPU not supported")
        adapter = requestAdapterJs(navigatorGpu).await() ?: error("Failed to request adapter")
        device = requestDeviceJs(adapter!!).await() ?: error("Failed to request device")
        
        context = getGpuContextJs(surface.canvas)
        val format = getPreferredCanvasFormatJs(navigatorGpu)

        configureContextJs(context!!, device!!, format)

        val shaderModule = createShaderModuleJs(device!!, WGPU_SHADER)
        renderPipeline = createRenderPipelineJs(device!!, shaderModule, format)

        createUniformBuffer()
        uploadAllTextures()
        
        sampler = createSamplerJs(device!!)
        uploadEnvCube()
        
        for ((_, tg) in textures) {
            val entries = makeBindGroupEntriesForObject(tg.view!!, uniformBuffer!!, sampler!!, envView!!, envSampler!!, tg.materialBuffer!!)
            tg.bindGroup = createBindGroupJs(device!!, renderPipeline!!, 0, entries)
        }

        val skyModule = createShaderModuleJs(device!!, SKY_SHADER)
        skyPipeline = createSkyPipelineJs(device!!, skyModule, format)
        val skyEntries = makeBindGroupEntriesForSky(uniformBuffer!!, envView!!, envSampler!!)
        val skyBindGroup = createBindGroupJs(device!!, skyPipeline!!, 0, skyEntries)

        rebuildGeometry(pendingFen ?: "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1")
        
        var depthTexture: JsAny? = null
        var currentWidth = 0
        var currentHeight = 0

        while (currentCoroutineContext().isActive) {
            val width = surface.widthPx
            val height = surface.heightPx
            if (width != currentWidth || height != currentHeight) {
                currentWidth = width
                currentHeight = height
                if (width > 0 && height > 0) {
                    depthTexture = createDepthTextureJs(device!!, width, height)
                }
            }

            if (width > 0 && height > 0 && depthTexture != null) {
                updateUniforms(width, height)

                val encoder = createCommandEncoderJs(device!!)
                val textureView = getCurrentTextureViewJs(context!!)
                val depthView = createTextureViewJs(depthTexture)

                val pass = beginRenderPassJs(encoder, textureView, depthView)
                
                passSetPipelineJs(pass, skyPipeline!!)
                passSetBindGroupJs(pass, 0, skyBindGroup)
                passDrawJs(pass, 3)

                passSetPipelineJs(pass, renderPipeline!!)
                for (tex in ChessTexture.entries) {
                    val gb = groupBuffers[tex] ?: continue
                    if (gb.indexCount == 0) continue
                    val tg = textures[tex] ?: continue
                    val bindGroup = tg.bindGroup ?: continue
                    
                    passSetBindGroupJs(pass, 0, bindGroup)
                    passSetVertexBufferJs(pass, 0, gb.vBuf!!)
                    passSetIndexBufferJs(pass, gb.iBuf!!)
                    passDrawIndexedJs(pass, gb.indexCount)
                }
                passEndJs(pass)

                val commandBuffer = finishEncoderJs(encoder)
                submitQueueJs(device!!, commandBuffer)
            }

            awaitAnimationFrame()
        }
    }
    
    private suspend fun awaitAnimationFrame() = suspendCancellableCoroutine<Unit> { cont ->
        val id = kotlinx.browser.window.requestAnimationFrame { cont.resumeWith(Result.success(Unit)) }
        cont.invokeOnCancellation { kotlinx.browser.window.cancelAnimationFrame(id) }
    }

    override fun detach() {
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
        device = null
        adapter = null
        context = null
    }

    override fun updatePosition(fen: String) = updatePosition(fen, null)

    override fun updatePosition(fen: String, transition: Board3DTransition?) {
        pendingFen = fen
        if (transition != null && transition !is Board3DTransition.Reset) {
            animateTransition(fen, transition)
        } else {
            scope.launch { rebuildGeometry(fen) }
        }
    }

    private var animationJob: Job? = null

    private fun animateTransition(targetFen: String, transition: Board3DTransition) {
        animationJob?.cancel()
        val baseScene = Board3DSceneMapper.fromFen(targetFen).copy(selectedSquare = selectedSquare)
        
        animationJob = scope.launch {
            val startMs = kotlinx.browser.window.performance.now()
            val durationMs = 500.0
            
            while (isActive) {
                val now = kotlinx.browser.window.performance.now()
                val progress = ((now - startMs) / durationMs).toFloat().coerceIn(0f, 1f)
                
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

                // Handle fading/sinking captured piece
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
                if (device != null && meshes != null) {
                    val geo = ChessSceneGeometry.build(interpolatedScene, meshes!!, includeGround = false)
                    for ((tex, group) in geo.groups) uploadGroup(tex, group)
                }
                
                if (progress >= 1f) break
                awaitAnimationFrame()
            }
        }
    }

    override fun setSelectedSquare(square: BoardSquare?) {
        if (square == selectedSquare) return
        selectedSquare = square
        scope.launch { rebuildGeometry(pendingFen ?: "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1") }
    }

    override fun onUserInteraction(event: Board3DInput) {
        when (event) {
            is Board3DInput.SetCamera -> camera = event.camera
            is Board3DInput.Resize -> camera = camera.copy(aspect = event.widthPx.toFloat() / event.heightPx.coerceAtLeast(1).toFloat())
            else -> {}
        }
    }
    
    private suspend fun rebuildGeometry(fen: String) {
        if (device == null || meshes == null) return
        val scene = Board3DSceneMapper.fromFen(fen).copy(selectedSquare = selectedSquare)
        val geo = ChessSceneGeometry.build(scene, meshes!!, includeGround = false)
        for ((tex, group) in geo.groups) uploadGroup(tex, group)
    }
    
    private fun uploadGroup(tex: ChessTexture, group: SceneGroup) {
        val gb = groupBuffers.getOrPut(tex) { GroupBuffers() }
        gb.indexCount = group.indexCount
        if (group.vertices.isEmpty() || group.indices.isEmpty()) { gb.indexCount = 0; return }
        
        val vBytes = (group.vertices.size * 4).toULong()
        val iBytes = (group.indices.size * 4).toULong()
        
        if (vBytes > gb.vCap) {
            gb.vBuf = createVertexBufferJs(device!!, vBytes.toInt())
            gb.vCap = vBytes
        }
        
        if (iBytes > gb.iCap) {
            gb.iBuf = createIndexBufferJs(device!!, iBytes.toInt())
            gb.iCap = iBytes
        }
        
        writeBufferFloatArrayJs(device!!, gb.vBuf!!, group.vertices)
        writeBufferIntArrayJs(device!!, gb.iBuf!!, group.indices)
    }

    private fun uploadAllTextures() {
        if (device == null || textureImages == null) return
        for (tex in ChessTexture.entries) {
            val img = textureImages!![tex] ?: continue
            textures[tex] = uploadTexture(tex, img)
        }
    }

    private fun uploadTexture(tex: ChessTexture, img: TextureImage): TextureGroup {
        val tg = TextureGroup()
        val texture = createTextureJs(device!!, img.width, img.height, "rgba8unorm")
        
        writeTextureJs(device!!, texture, img.width, img.height, img.rgba)
        
        tg.image = texture
        tg.view = createTextureViewJs(texture)
        
        val matBuffer = createUniformBufferJs(device!!, 16)
        val roughness = if (tex == ChessTexture.BOARD) 0.25f else 0.45f
        val matData = floatArrayOf(roughness, 0f, 0f, 0f)
        writeBufferFloatArrayJs(device!!, matBuffer, matData)
        tg.materialBuffer = matBuffer
        
        return tg
    }
    
    private suspend fun uploadEnvCube() {
        val bytes = game.app.generated.resources.Res.readBytes("files/papermill_hdr16f_cube.ktx")
        val ktx = WasmKtxLoader.load(bytes) ?: error("Failed to parse env cubemap KTX")
        val tex = createCubeTextureJs(device!!, ktx.width, ktx.height, ktx.mipLevels)
        for (m in 0 until ktx.mipLevels) {
            val mipW = (ktx.width shr m).coerceAtLeast(1)
            val mipH = (ktx.height shr m).coerceAtLeast(1)
            val faceSize = ktx.mipSizes[m] / 6
            for (face in 0 until 6) {
                val arr = ByteArray(faceSize)
                ktx.data.copyInto(arr, 0, ktx.mipOffsets[m] + face * faceSize, ktx.mipOffsets[m] + face * faceSize + faceSize)
                writeCubeFaceJs(device!!, tex, m, face, mipW, mipH, arr)
            }
        }
        envTexture = tex
        envView = createCubeTextureViewJs(tex)
        envSampler = createSamplerJs(device!!, mipmap = true)
    }

    private fun createUniformBuffer() {
        uniformBuffer = createUniformBufferJs(device!!, 64 * 4)
    }

    private fun viewProjMatrix(width: Int, height: Int): Matrix4f {
        val aspect = (width.toFloat() / height.toFloat()).coerceAtLeast(0.01f)
        val proj = Matrix4f().perspective(camera.fovYDegrees * (PI.toFloat() / 180f), aspect, camera.near, camera.far, true)
        val view = Matrix4f().lookAt(camera.position.x, camera.position.y, camera.position.z, camera.target.x, camera.target.y, camera.target.z, camera.up.x, camera.up.y, camera.up.z)
        return proj.mul(view)
    }

    private fun lightViewProj(): Matrix4f {
        val lightDir = Vector3f(0.45f, 1.0f, 0.35f).normalize()
        val lightPos = Vector3f(camera.target.x, camera.target.y, camera.target.z).add(Vector3f(lightDir).mul(30f))
        val proj = Matrix4f().ortho(-20f, 20f, -20f, 20f, 0.1f, 100f, true)
        val view = Matrix4f().lookAt(lightPos, Vector3f(camera.target.x, camera.target.y, camera.target.z), Vector3f(0f, 1f, 0f))
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
        invViewProj.get(data, 36)
        
        writeBufferFloatArrayJs(device!!, ub, data)
    }

    override fun dispose() {
        renderJob?.cancel()
        scope.cancel()
    }
}
