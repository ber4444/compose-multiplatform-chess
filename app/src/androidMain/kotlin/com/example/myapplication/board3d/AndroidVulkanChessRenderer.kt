package com.example.myapplication.board3d

import android.view.Choreographer
import com.google.android.filament.Camera
import com.google.android.filament.Engine
import com.google.android.filament.EntityManager
import com.google.android.filament.Filament
import com.google.android.filament.Renderer
import com.google.android.filament.Scene
import com.google.android.filament.SwapChain
import com.google.android.filament.View
import com.google.android.filament.Viewport
import com.google.android.filament.gltfio.AssetLoader
import com.google.android.filament.gltfio.FilamentAsset
import com.google.android.filament.gltfio.MaterialProvider
import com.google.android.filament.gltfio.ResourceLoader
import com.google.android.filament.gltfio.UbershaderProvider
import java.nio.ByteBuffer

class AndroidVulkanChessRenderer(private val glb: ByteArray) : Chess3DBoardRenderer {

    private var engine: Engine? = null
    private var renderer: Renderer? = null
    private var scene: Scene? = null
    private var camera: Camera? = null
    private var view: View? = null
    private var assetLoader: AssetLoader? = null
    private var materialProvider: MaterialProvider? = null
    private var resourceLoader: ResourceLoader? = null
    private var filamentAsset: FilamentAsset? = null
    private var swapChain: SwapChain? = null
    private var surface: AndroidChess3DSurface? = null
    private var currentCameraParams: CameraParams = OrbitCameraController.DEFAULT_WHITE_VIEW
    private var frameCallback: Choreographer.FrameCallback? = null
    
    private var pieceInstances = arrayOfNulls<com.google.android.filament.gltfio.FilamentInstance>(33)
    private var whiteMaterial: com.google.android.filament.MaterialInstance? = null
    private var blackMaterial: com.google.android.filament.MaterialInstance? = null
    private val activePieceEntities = mutableListOf<Int>()
    init {
        Filament.init()
        com.google.android.filament.gltfio.Gltfio.init()
        com.google.android.filament.utils.Utils.init()
        engine = Engine.create()
        renderer = engine?.createRenderer()
        scene = engine?.createScene()
        camera = engine?.createCamera(EntityManager.get().create())
        view = engine?.createView()

        view?.scene = scene
        view?.camera = camera

        materialProvider = UbershaderProvider(engine!!)
        assetLoader = AssetLoader(engine!!, materialProvider!!, EntityManager.get())

        val buffer = ByteBuffer.wrap(glb)
        filamentAsset = assetLoader?.createInstancedAsset(buffer, pieceInstances)

        resourceLoader = ResourceLoader(engine!!, true)
        resourceLoader?.loadResources(filamentAsset!!)
        filamentAsset?.releaseSourceData()

        // The glb bundles the 8x8 board (64 tiles + frame + ground) and one template mesh per piece
        // type (king/queen/.../pawn) = 72 nodes. createInstancedAsset() makes 33 instances and
        // asset.entities is ALL of them concatenated (33 * 72), so adding asset.entities stacks 33
        // overlapping boards PLUS 33 copies of every piece template at the model origin -> the stray
        // piece in the centre. Instead render the board from a single instance (instance 0), excluding
        // the piece-template nodes; the per-square pieces are cloned from instances 1..32 in
        // updatePosition().
        val pieceNodeIndices = PieceKind.entries.mapNotNull { kind ->
            val e = filamentAsset!!.getFirstEntityByName(ChessSetMeshNames.getMeshName(kind, PieceColor.WHITE))
            filamentAsset!!.entities.indexOf(e).takeIf { it >= 0 }
        }.toSet()
        val boardEntities = pieceInstances[0]!!.entities
            .filterIndexed { i, _ -> i !in pieceNodeIndices }
            .toIntArray()
        scene?.addEntities(boardEntities)

        val tm = engine!!.transformManager
        val rootInst = tm.getInstance(filamentAsset!!.root)
        if (rootInst != 0) {
            val rootTransform = FloatArray(16)
            android.opengl.Matrix.setIdentityM(rootTransform, 0)
            android.opengl.Matrix.scaleM(rootTransform, 0, 0.5f, 0.5f, 0.5f)
            tm.setTransform(rootInst, rootTransform)
        }

        val rm = engine!!.renderableManager
        for (entity in filamentAsset!!.entities) {
            val inst = rm.getInstance(entity)
            if (inst == 0) continue
            for (i in 0 until rm.getPrimitiveCount(inst)) {
                val mat = rm.getMaterialInstanceAt(inst, i)
                if (mat.name == "white") whiteMaterial = mat
                if (mat.name == "black") blackMaterial = mat
            }
        }

        // Add a directional light so PBR materials are visible
        val light = EntityManager.get().create()
        com.google.android.filament.LightManager.Builder(com.google.android.filament.LightManager.Type.DIRECTIONAL)
            .color(1.0f, 1.0f, 1.0f)
            .intensity(110000.0f)
            .direction(-1.0f, -1.0f, -1.0f)
            .build(engine!!, light)
        scene?.addEntity(light)

        // Set a background color (Skybox)
        val skybox = com.google.android.filament.Skybox.Builder()
            .color(0.1f, 0.1f, 0.15f, 1.0f)
            .build(engine!!)
        scene?.skybox = skybox
    }

    override fun attach(surface: Chess3DSurface) {
        if (surface !is AndroidChess3DSurface) return
        this.surface = surface

        swapChain?.let { engine?.destroySwapChain(it) }
        swapChain = engine?.createSwapChain(surface.holder.surface)

        updateViewport(surface.widthPx, surface.heightPx)
        startRenderLoop()
    }

    override fun detach() {
        stopRenderLoop()
        swapChain?.let { engine?.destroySwapChain(it) }
        swapChain = null
        surface = null
    }

    override fun updatePosition(fen: String) {
        val boardScene = try {
            Board3DSceneMapper.fromFen(fen)
        } catch (e: Exception) { return }

        activePieceEntities.forEach { scene?.removeEntity(it) }
        activePieceEntities.clear()

        val tm = engine!!.transformManager
        val rm = engine!!.renderableManager

        boardScene.pieces.forEachIndexed { i, piece ->
            if (i + 1 >= pieceInstances.size) return@forEachIndexed
            val instance = pieceInstances[i + 1] ?: return@forEachIndexed

            val meshName = ChessSetMeshNames.getMeshName(piece.kind, piece.color)
            val templateEntity = filamentAsset?.getFirstEntityByName(meshName) ?: 0
            if (templateEntity == 0) return@forEachIndexed

            val idx = filamentAsset!!.entities.indexOf(templateEntity)
            if (idx < 0) return@forEachIndexed

            val pieceEntity = instance.entities[idx]
            activePieceEntities.add(pieceEntity)
            scene?.addEntity(pieceEntity)

            val rInst = rm.getInstance(pieceEntity)
            if (rInst != 0) {
                val targetMat = if (piece.color == PieceColor.WHITE) whiteMaterial else blackMaterial
                if (targetMat != null) {
                    for (p in 0 until rm.getPrimitiveCount(rInst)) {
                        rm.setMaterialInstanceAt(rInst, p, targetMat)
                    }
                }
            }

            val tInst = tm.getInstance(pieceEntity)
            if (tInst != 0) {
                tm.setParent(tInst, 0)
                val transform = FloatArray(16)
                android.opengl.Matrix.setIdentityM(transform, 0)
                android.opengl.Matrix.translateM(transform, 0, piece.position.x, piece.position.y, piece.position.z)
                android.opengl.Matrix.rotateM(transform, 0, piece.rotationYDegrees, 0f, 1f, 0f)
                android.opengl.Matrix.scaleM(transform, 0, 0.5f, 0.5f, 0.5f)
                tm.setTransform(tInst, transform)
            }
        }
    }

    override fun onUserInteraction(event: Board3DInput) {
        when (event) {
            is Board3DInput.Resize -> updateViewport(event.widthPx, event.heightPx)
            is Board3DInput.SetCamera -> {
                currentCameraParams = event.camera
                updateCamera()
            }
            else -> {}
        }
    }

    private fun updateViewport(width: Int, height: Int) {
        view?.viewport = Viewport(0, 0, width, height)
        updateCamera()
    }

    private fun updateCamera() {
        val aspect = if (surface != null && surface!!.heightPx > 0)
            surface!!.widthPx.toDouble() / surface!!.heightPx.toDouble()
        else 1.0

        val fovDirection = if (aspect < 1.0) {
            com.google.android.filament.Camera.Fov.HORIZONTAL
        } else {
            com.google.android.filament.Camera.Fov.VERTICAL
        }

        camera?.setProjection(
            currentCameraParams.fovYDegrees.toDouble(),
            aspect,
            0.1,
            100.0,
            fovDirection
        )

        camera?.lookAt(
            currentCameraParams.position.x.toDouble(),
            currentCameraParams.position.y.toDouble(),
            currentCameraParams.position.z.toDouble(),
            currentCameraParams.target.x.toDouble(),
            currentCameraParams.target.y.toDouble(),
            currentCameraParams.target.z.toDouble(),
            0.0, 1.0, 0.0
        )
    }

    private fun startRenderLoop() {
        if (frameCallback == null) {
            frameCallback = object : Choreographer.FrameCallback {
                override fun doFrame(frameTimeNanos: Long) {
                    if (engine != null && renderer != null && view != null && swapChain != null) {
                        if (renderer!!.beginFrame(swapChain!!, frameTimeNanos)) {
                            renderer!!.render(view!!)
                            renderer!!.endFrame()
                        }
                    }
                    Choreographer.getInstance().postFrameCallback(this)
                }
            }
        }
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    private fun stopRenderLoop() {
        frameCallback?.let {
            Choreographer.getInstance().removeFrameCallback(it)
        }
    }

    override fun dispose() {
        stopRenderLoop()
        filamentAsset?.let { assetLoader?.destroyAsset(it) }
        resourceLoader?.destroy()
        assetLoader?.destroy()
        materialProvider?.destroy()

        camera?.entity?.let { EntityManager.get().destroy(it) }
        view?.let { engine?.destroyView(it) }
        scene?.let { engine?.destroyScene(it) }
        renderer?.let { engine?.destroyRenderer(it) }
        swapChain?.let { engine?.destroySwapChain(it) }
        engine?.destroy()

        engine = null
        renderer = null
        scene = null
        camera = null
        view = null
    }
}
