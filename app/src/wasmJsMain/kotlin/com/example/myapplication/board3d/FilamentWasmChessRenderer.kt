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
        script.src = "https://unpkg.com/filament@1.53.4/filament.js"
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

// chess.glb uses 2-unit squares (board spans +/-8); the game uses 1-unit squares, so every node is
// scaled 0.5 — identical to iOS kModelScale (FilamentChessRenderer.mm) and AndroidBoard3D.
// All conventions below come from ChessSetConventions in commonMain (single source of truth).
const PIECE_SCALE = ${ChessSetConventions.PIECE_SCALE};
// A board holds at most 32 pieces (promotion replaces a pawn, never adds). Instance 0 is the board;
// 1..32 are the piece-pool slots — mirrors iOS createInstancedAsset(kMaxPieces + 1).
const MAX_PIECES = ${ChessSetConventions.MAX_PIECES};
const INSTANCE_COUNT = MAX_PIECES + 1;
// PieceKind ordinals (Board3DScene.kt): KING, QUEEN, ROOK, BISHOP, KNIGHT, PAWN -> glTF node names.
const KIND_NAMES = [${ChessSetConventions.KIND_NAMES.joinToString(", ") { "'$it'" }}];

window.chess3dFilament = {
    isReady: false,
    engine: null,
    scene: null,
    camera: null,
    view: null,
    renderer: null,
    swapChain: null,
    transformManager: null,
    renderableManager: null,
    assetLoader: null,
    asset: null,
    instances: null,

    init(canvas) {
        Filament.init(['${ChessSetConventions.GLB_ASSET}', '${ChessSetConventions.IBL_ASSET}', '${ChessSetConventions.SKYBOX_ASSET}'], () => {
            try {
                this.engine = Filament.Engine.create(canvas);
                this.scene = this.engine.createScene();
                this.camera = this.engine.createCamera(Filament.EntityManager.get().create());
                this.view = this.engine.createView();
                this.view.setCamera(this.camera);
                this.view.setScene(this.scene);
                this.renderer = this.engine.createRenderer();
                this.swapChain = this.engine.createSwapChain();
                this.transformManager = this.engine.getTransformManager();
                this.renderableManager = this.engine.getRenderableManager();

                const ibl = this.engine.createIblFromKtx1(Filament.assets['${ChessSetConventions.IBL_ASSET}']);
                this.scene.setIndirectLight(ibl);
                ibl.setIntensity(${ChessSetConventions.IBL_INTENSITY});
                this.scene.setSkybox(this.engine.createSkyFromKtx1(Filament.assets['${ChessSetConventions.SKYBOX_ASSET}']));

                // One asset, INSTANCE_COUNT instances sharing geometry but with independent transforms,
                // visibility and material instances — mirrors iOS createInstancedAsset / Android
                // createInstancedModel. createInstancedAsset fills the passed array in place.
                this.assetLoader = this.engine.createAssetLoader();
                this.asset = this.assetLoader.createAsset(Filament.assets['${ChessSetConventions.GLB_ASSET}']);
                const instances = new Array(INSTANCE_COUNT).fill(null);
                instances[0] = this.asset.getInstance();
                for (let i = 1; i < INSTANCE_COUNT; i++) {
                    instances[i] = this.assetLoader.createInstance(this.asset);
                }
                this.instances = instances;

                this.asset.loadResources(() => {
                    this.configureInstanceVisibility();
                    this.isReady = true;
                    console.log('[filament] ready: ' + this.instances.length + ' instances');
                });
                requestAnimationFrame(this.render.bind(this));
            } catch (e) {
                console.error('[filament] init failed', e.stack || e);
            }
        });
    },

    // Instance 0 shows board tiles + frame and hides the 6 piece templates + the "Plane" ground;
    // instances 1..32 start hidden until setScene reveals one template each. Hidden = removed from the
    // scene; shown = present. Mirrors iOS configureInstanceVisibility.
    configureInstanceVisibility() {
        const board = this.instances[0];
        this.forEachRenderable(board, (e, name) => {
            const hide = KIND_NAMES.indexOf(name) !== -1 || name === 'Plane';
            if (hide) this.scene.remove(e); else this.scene.addEntity(e);
        });
        this.setInstanceTransform(board, 0, 0, 0, 0);
        for (let i = 1; i < INSTANCE_COUNT; i++) {
            this.forEachRenderable(this.instances[i], (e) => this.scene.remove(e));
        }
    },

    // Reconcile the fixed instance pool against an encoded Board3DScene ("kind,color,x,y,z,rot;...").
    // Called every animation frame by the shared Board3DAnimationDriver. Mirrors iOS setSceneEncoded.
    setScene(s) {
        if (!this.isReady) return;
        const pieces = this.parseScene(s);
        for (let slot = 0; slot < MAX_PIECES; slot++) {
            const inst = this.instances[slot + 1];
            if (!inst) continue;
            if (slot >= pieces.length) {
                this.forEachRenderable(inst, (e) => this.scene.remove(e));
                continue;
            }
            const p = pieces[slot];
            const meshName = KIND_NAMES[p.kind] || '';
            const mat = this.materialNamed(p.color === 0 ? 'white' : 'black', inst);
            this.forEachRenderable(inst, (e, name) => {
                if (name === meshName) {
                    this.scene.addEntity(e);
                    if (mat) {
                        const ri = this.renderableManager.getInstance(e);
                        const prims = this.renderableManager.getPrimitiveCount(ri);
                        for (let pr = 0; pr < prims; pr++) this.renderableManager.setMaterialInstanceAt(ri, pr, mat);
                    }
                } else {
                    this.scene.remove(e);
                }
            });
            // y comes from the scene so the move-arc hop lifts the piece; resting pieces stay at y=0.
            this.setInstanceTransform(inst, p.x, p.y, p.z, p.rot);
        }
    },

    setCamera(px, py, pz, tx, ty, tz, ux, uy, uz, fov, aspect) {
        if (!this.camera) return;
        this.camera.lookAt([px, py, pz], [tx, ty, tz], [ux, uy, uz]);
        // Portrait FOV boost: with aspect < 1 the horizontal FOV shrinks too far for the board, so widen
        // the vertical FOV to hold a fixed ~60deg horizontal FOV. Identical formula to
        // CameraMath.effectiveFovYRad (commonMain Math3D.kt) and the iOS setCameraEncoded branch
        // (FilamentChessRenderer.mm), so all backends project the same FOV and tap-picking stays in
        // sync (memory: board3d-portrait-fov-picking).
        let effFov = fov;
        if (aspect < 1.0) {
            const tanHalfFovX = Math.tan((60.0 * Math.PI / 180.0) / 2.0);
            effFov = 2.0 * Math.atan(tanHalfFovX / aspect) * 180.0 / Math.PI;
        }
        this.camera.setProjectionFov(effFov, aspect, 0.05, 200.0, Filament.Camera${'$'}Fov.VERTICAL);
    },

    resize(w, h) {
        if (!this.view) return;
        this.view.setViewport([0, 0, w, h]);
    },

    render() {
        requestAnimationFrame(this.render.bind(this));
        if (this.isReady && this.renderer && this.view && this.swapChain) {
            this.renderer.render(this.swapChain, this.view);
        }
    },

    // Called on every 2D<->3D toggle (Kotlin detach()/dispose()). init() builds a brand-new Engine
    // each attach and the canvas can differ between attaches, so the correct model is destroy-and-
    // recreate: tear the Engine down here and let the next init() start from a clean slate (it
    // reassigns every field below). Destroying the Engine releases all the GPU resources it owns
    // (scene/view/camera/renderer/swapChain/IBL/skybox/asset/instances/material instances), so the
    // single Engine-level teardown is the primary cleanup. Verified static API in filament@1.72.0:
    // Filament.Engine.destroy(engine) (jsbindings.cpp: class_function("destroy", ...) -> Engine::destroy).
    // Wrapped in try/catch so a wrong call can't wedge re-init; state is always reset at the end.
    dispose() {
        try {
            if (this.assetLoader && this.asset) this.assetLoader.destroyAsset(this.asset);
        } catch (e) { console.warn('[filament] dispose: destroyAsset failed', e); }
        try {
            if (this.engine) Filament.Engine.destroy(this.engine);
        } catch (e) { console.warn('[filament] dispose: Engine.destroy failed', e); }
        this.isReady = false;
        this.engine = null;
        this.scene = null;
        this.view = null;
        this.camera = null;
        this.renderer = null;
        this.swapChain = null;
        this.assetLoader = null;
        this.asset = null;
        this.instances = null;
        this.transformManager = null;
        this.renderableManager = null;
    },

    // --- helpers (mirror the iOS Obj-C++ helpers in FilamentChessRenderer.mm) ---

    parseScene(s) {
        if (!s) return [];
        return s.split(';').map((rec) => {
            const f = rec.split(',');
            return { kind: +f[0], color: +f[1], x: +f[2], y: +f[3], z: +f[4], rot: +f[5] };
        });
    },

    // Iterate the renderable entities of an instance, resolving each glTF node name via the asset.
    forEachRenderable(inst, fn) {
        if (!inst) return;
        const es = inst.getEntities();
        const n = (typeof es.size === 'function') ? es.size() : es.length;
        for (let i = 0; i < n; i++) {
            const e = (typeof es.get === 'function') ? es.get(i) : es[i];
            if (!this.renderableManager.hasComponent(e)) continue;
            fn(e, this.asset.getName(e) || '');
        }
    },

    materialNamed(wanted, inst) {
        let found = null;
        this.forEachRenderable(inst, (e) => {
            if (found) return;
            const ri = this.renderableManager.getInstance(e);
            const prims = this.renderableManager.getPrimitiveCount(ri);
            for (let pr = 0; pr < prims; pr++) {
                const mi = this.renderableManager.getMaterialInstanceAt(ri, pr);
                if (mi && mi.getName && mi.getName() === wanted) {
                    found = mi;
                    break;
                }
            }
        });
        return found;
    },

    // Column-major translate * rotateY * uniformScale, matching iOS setInstanceTransform. Built by
    // hand (a plain number[16] is a valid Filament 'mat4') to avoid depending on Filament.math's shape.
    setInstanceTransform(inst, x, y, z, rotYDeg) {
        const r = rotYDeg * Math.PI / 180, c = Math.cos(r), s = Math.sin(r), k = PIECE_SCALE;
        const m = [
            k * c, 0, -k * s, 0,
            0,     k, 0,      0,
            k * s, 0, k * c,  0,
            x,     y, z,      1,
        ];
        const tm = this.transformManager;
        tm.setTransform(tm.getInstance(inst.getRoot()), m);
    }
};
"""
