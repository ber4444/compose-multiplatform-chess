package com.example.myapplication.board3d

import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.*
import org.w3c.dom.HTMLCanvasElement
import org.w3c.dom.HTMLScriptElement

@JsFun("(msg) => { console.log(msg); }")
private external fun log(msg: String)
@JsFun("(msg) => { console.warn(msg); }")
private external fun warn(msg: String)
@JsFun("(msg) => { console.error(msg); }")
private external fun error(msg: String)

class FilamentWasmChessRenderer : Chess3DBoardRenderer {

    private var canvas: HTMLCanvasElement? = null
    private var pendingFen: String = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
    private var camera: CameraParams = OrbitCameraController.DEFAULT_WHITE_VIEW
    private var selectedSquare: BoardSquare? = null
    private var isReady = false
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private val driver = Board3DAnimationDriver(scope) { scene ->
        if (isReady) filamentSetScene(scene.encode())
    }

    override fun attach(surface: Chess3DSurface) {
        val wasmSurface = surface as? WasmChess3DSurface ?: return
        canvas = wasmSurface.canvas

        scope.launch {
            try {
                injectFilamentJs()
                if (!isFilamentJsLoaded()) {
                    error("[filament] ABORT: Filament not defined")
                    return@launch
                }
                
                filamentInitRenderer(wasmSurface.canvas)
                
                var attempts = 0
                while (!isFilamentReady() && attempts < 100) {
                    delay(100)
                    attempts++
                }
                if (!isFilamentReady()) {
                    error("[filament] ABORT: Filament async init timed out")
                    return@launch
                }
                
                isReady = true
                driver.setPosition(runCatching { Board3DSceneMapper.fromFen(pendingFen) }.getOrNull(), null)
                applyCamera(camera)
                filamentResize(wasmSurface.canvas.width, wasmSurface.canvas.height)
                driver.setSelected(selectedSquare)
            } catch (t: Throwable) {
                error("[filament] attach() failed: ${t.message}")
            }
        }
    }

    override fun detach() {
        filamentDispose()
        isReady = false
    }

    override fun updatePosition(fen: String) = updatePosition(fen, null)

    override fun updatePosition(fen: String, transition: Board3DTransition?) {
        pendingFen = fen
        driver.setPosition(runCatching { Board3DSceneMapper.fromFen(fen) }.getOrNull(), transition)
    }

    override fun setSelectedSquare(square: BoardSquare?) {
        selectedSquare = square
        driver.setSelected(square)
    }

    override fun onUserInteraction(event: Board3DInput) {
        when (event) {
            is Board3DInput.SetCamera -> {
                camera = event.camera
                applyCamera(event.camera)
            }
            is Board3DInput.Resize -> {
                camera = camera.copy(aspect = event.widthPx.toFloat() / event.heightPx.coerceAtLeast(1).toFloat())
                filamentResize(event.widthPx, event.heightPx)
            }
            else -> {}
        }
    }

    override fun dispose() {
        driver.cancel()
        scope.cancel()
        filamentDispose()
    }

    private fun applyCamera(cam: CameraParams) {
        if (isReady) filamentSetCamera(
            cam.position.x, cam.position.y, cam.position.z,
            cam.target.x, cam.target.y, cam.target.z,
            cam.up.x, cam.up.y, cam.up.z,
            cam.fovYDegrees, cam.aspect
        )
    }

    private suspend fun injectFilamentJs() {
        if (isFilamentJsLoaded()) return

        val script = document.createElement("script") as HTMLScriptElement
        script.src = "https://unpkg.com/filament@1.45.0/filament.js"
        document.head!!.appendChild(script)

        var attempts = 0
        while (!isFilamentJsLoadedCore() && attempts < 100) {
            delay(100)
            attempts++
        }
        
        delay(100)
        
        val glue = document.createElement("script") as HTMLScriptElement
        glue.type = "application/javascript"
        glue.textContent = CHESS3D_FILAMENT_JS
        document.head!!.appendChild(glue)
    }
}

private fun isFilamentJsLoadedCore(): Boolean =
    js("(typeof Filament !== 'undefined')")

private fun isFilamentJsLoaded(): Boolean =
    js("(typeof Filament !== 'undefined' && typeof window.chess3dFilament !== 'undefined')")

private fun isFilamentReady(): Boolean =
    js("(window.chess3dFilament && window.chess3dFilament.isReady === true)")

@JsFun("(canvas) => { window.chess3dFilament.init(canvas); }")
private external fun filamentInitRenderer(canvas: HTMLCanvasElement)

@JsFun("(s) => { window.chess3dFilament.setScene(s); }")
private external fun filamentSetScene(s: String)

@JsFun("(px,py,pz,tx,ty,tz,ux,uy,uz,fov,aspect) => { window.chess3dFilament.setCamera(px,py,pz,tx,ty,tz,ux,uy,uz,fov,aspect); }")
private external fun filamentSetCamera(
    px: Float, py: Float, pz: Float, tx: Float, ty: Float, tz: Float,
    ux: Float, uy: Float, uz: Float, fov: Float, aspect: Float,
)

@JsFun("(w, h) => { window.chess3dFilament.resize(w, h); }")
private external fun filamentResize(w: Int, h: Int)

@JsFun("() => { if (window.chess3dFilament) window.chess3dFilament.dispose(); }")
private external fun filamentDispose()

private val CHESS3D_FILAMENT_JS = """

const PIECE_SCALE = 0.5;
const KIND_NAMES = ['king', 'queen', 'rook', 'bishop', 'knight', 'pawn'];

window.chess3dFilament = {
    isReady: false,
    engine: null,
    scene: null,
    camera: null,
    view: null,
    renderer: null,
    swapChain: null,
    assetLoader: null,
    boardAsset: null,
    piecesPool: [],
    pieceTemplates: {},
    transformManager: null,
    
    init(canvas) {
        Filament.init(['chess.glb', 'papermill_ibl.ktx', 'papermill_skybox.ktx'], () => {
            try {
                console.log('Creating Engine');
                this.engine = Filament.Engine.create(canvas);
                console.log('Creating Scene');
                this.scene = this.engine.createScene();
                console.log('Creating Camera');
                this.camera = this.engine.createCamera(Filament.EntityManager.get().create());
                console.log('Creating View');
                this.view = this.engine.createView();
                this.view.setCamera(this.camera);
                this.view.setScene(this.scene);
                
                console.log('Creating Renderer');
                this.renderer = this.engine.createRenderer();
                console.log('Creating SwapChain');
                this.swapChain = this.engine.createSwapChain();
                
                console.log('Getting TransformManager');
                this.transformManager = this.engine.getTransformManager();
                
                const iblUrl = 'papermill_ibl.ktx';
                const skyUrl = 'papermill_skybox.ktx';
                
                const iblData = Filament.assets[iblUrl];
                const skyData = Filament.assets[skyUrl];
                const glbData = Filament.assets['chess.glb'];
                
                console.log('Creating IBL', !!iblData);
                const ibl = this.engine.createIblFromKtx1(iblData);
                this.scene.setIndirectLight(ibl);
                ibl.setIntensity(30000);
                
                console.log('Creating Skybox', !!skyData);
                const skybox = this.engine.createSkyFromKtx1(skyData);
                this.scene.setSkybox(skybox);
                
                // Asset Loader
                console.log('Creating AssetLoader');
                this.assetLoader = this.engine.createAssetLoader();
                console.log('Creating Asset from GLB');
                this.boardAsset = this.assetLoader.createAsset(glbData);
                
                console.log('Loading Resources');
                this.boardAsset.loadResources(() => {
                    console.log('loadResources onDone callback');
                    const entities = this.boardAsset.getEntities();
                    console.log('Entities count:', entities.size ? entities.size() : entities.length);
                    this.scene.addEntities(entities);
                    
                    const rootEntity = this.boardAsset.getRoot();
                    const tm = this.transformManager;
                    const rootInstance = tm.getInstance(rootEntity);
                    tm.setTransform(rootInstance, Filament.math.mat4.scale(Filament.math.mat4.create(), [PIECE_SCALE, PIECE_SCALE, PIECE_SCALE]));
                    
                    this.isReady = true;
                    console.log('Filament initialization complete!');
                });
                console.log('Requesting Animation Frame');
                requestAnimationFrame(this.render.bind(this));
            } catch(e) {
                console.error(e);
            }
        });
    },
    
    setScene(s) {
        if (!this.isReady || !this.boardAsset) return;
        // The prototype currently just shows the static scene
    },
    
    setCamera(px, py, pz, tx, ty, tz, ux, uy, uz, fov, aspect) {
        if (!this.camera) return;
        const eye = [px, py, pz];
        const center = [tx, ty, tz];
        const up = [ux, uy, uz];
        this.camera.lookAt(eye, center, up);
        this.camera.setProjectionFov(fov, aspect, 0.05, 200.0, Filament.Camera${'$'}Fov.VERTICAL);
    },
    
    resize(w, h) {
        if (!this.engine) return;
        this.view.setViewport([0, 0, w, h]);
    },
    
    render() {
        requestAnimationFrame(this.render.bind(this));
        if (this.isReady && this.renderer && this.view && this.swapChain) {
            this.renderer.render(this.swapChain, this.view);
        }
    },
    
    dispose() {
        if (this.engine) {
            // cleanup
        }
    }
};
"""
