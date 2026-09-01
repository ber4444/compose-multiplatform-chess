package com.example.myapplication.board3d

import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.*
import org.w3c.dom.HTMLCanvasElement
import org.w3c.dom.HTMLScriptElement

@JsFun("(msg) => { console.error(msg); }")
private external fun error(msg: String)

class FilamentWasmChessRenderer : Chess3DBoardRenderer {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val peer = WasmFilamentPeer(scope, ::injectFilamentJs)
    private val delegate = FilamentEncodedChessRenderer(peer, scope)

    override fun attach(surface: Chess3DSurface) = delegate.attach(surface)
    override fun detach() = delegate.detach()
    override fun updatePosition(fen: String) = delegate.updatePosition(fen)
    override fun updatePosition(fen: String, transition: Board3DTransition?) = delegate.updatePosition(fen, transition)
    override fun setSelectedSquare(square: BoardSquare?) = delegate.setSelectedSquare(square)
    override fun setHighlightedSquares(squares: List<HighlightedSquare>) = delegate.setHighlightedSquares(squares)
    override fun onUserInteraction(event: Board3DInput) = delegate.onUserInteraction(event)
    override fun dispose() = delegate.dispose()

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

private class WasmFilamentPeer(
    private val scope: CoroutineScope,
    private val injectFilamentJs: suspend () -> Unit,
) : FilamentChessPeer {
    private var canvas: HTMLCanvasElement? = null
    private var initJob: Job? = null
    private var isReady = false
    private var pendingScene: String? = null
    private var pendingCamera: String? = null
    private var pendingSize: Pair<Int, Int>? = null

    // Latest [setRenderingActive], queued like the scene/camera above because the JS renderer does
    // not exist until `init()` has run. Starts true so a board that reaches the glue before the
    // driver's first signal is still drawn.
    private var wantsRender = true

    override fun attach(surface: Chess3DSurface?) {
        val wasmSurface = surface as? WasmChess3DSurface ?: return
        val isNewCanvas = canvas != wasmSurface.canvas
        canvas = wasmSurface.canvas
        pendingSize = wasmSurface.widthPx to wasmSurface.heightPx

        if (isReady && !isNewCanvas) {
            flushPending()
            return
        }

        if (isNewCanvas) {
            initJob?.cancel()
            filamentDispose()
            isReady = false
        }

        if (initJob?.isActive == true) return
        initJob = scope.launch {
            try {
                injectFilamentJs()
                if (!isFilamentJsLoaded()) {
                    error("[filament] ABORT: Filament not defined")
                    return@launch
                }

                val targetCanvas = canvas ?: return@launch
                filamentInitRenderer(targetCanvas)

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
                flushPending()
            } catch (t: Throwable) {
                error("[filament] attach() failed: ${t.message}")
            }
        }
    }

    override fun detach() {
        initJob?.cancel()
        initJob = null
        isReady = false
        canvas = null
        filamentDispose()
    }

    override fun resize(widthPx: Int, heightPx: Int) {
        pendingSize = widthPx to heightPx
        if (isReady) filamentResize(widthPx, heightPx)
    }

    override fun setScene(encoded: String) {
        pendingScene = encoded
        if (isReady) filamentSetScene(encoded)
    }

    override fun setCamera(encoded: String) {
        pendingCamera = encoded
        if (isReady) filamentSetCameraEncoded(encoded)
    }

    override fun setRenderingActive(active: Boolean) {
        wantsRender = active
        if (isReady) filamentSetRenderingActive(active)
    }

    override fun shutdown() {
        detach()
        pendingScene = null
        pendingCamera = null
        pendingSize = null
    }

    // The render-loop signal is flushed first, and the state pushes after, because each of those
    // raises the JS gate's own "undrawn state" term. Async init routinely outlives the driver's
    // dirty window — it polls at 100 ms granularity while the window is ~48 ms — so `wantsRender` is
    // usually already false by the time we get here, and a flush that ended on it would park the
    // loop with the board's very first scene never drawn.
    private fun flushPending() {
        filamentSetRenderingActive(wantsRender)
        val (width, height) = pendingSize ?: (canvas?.width to canvas?.height)
        if (width != null && height != null) filamentResize(width, height)
        pendingCamera?.let { filamentSetCameraEncoded(it) }
        pendingScene?.let { filamentSetScene(it) }
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

@JsFun("(s) => { const f = s.split(',').map(Number); if (f.length >= 11) window.chess3dFilament.setCamera(f[0],f[1],f[2],f[3],f[4],f[5],f[6],f[7],f[8],f[9],f[10]); }")
private external fun filamentSetCameraEncoded(s: String)

@JsFun("(w, h) => { window.chess3dFilament.resize(w, h); }")
private external fun filamentResize(w: Int, h: Int)

@JsFun("(active) => { window.chess3dFilament.setRenderingActive(active); }")
private external fun filamentSetRenderingActive(active: Boolean)

@JsFun("() => { if (window.chess3dFilament) window.chess3dFilament.dispose(); }")
private external fun filamentDispose()

// `internal` rather than private so FrameLoopGateTest can inject the glue into the karma browser
// and drive the frame-loop gate without a GPU; see that test for why it can't go through init().
internal val CHESS3D_FILAMENT_JS = """

// chess.glb uses 2-unit squares (board spans +/-8); the game uses 1-unit squares, so every node is
// scaled 0.5 — identical to iOS kModelScale (FilamentChessRenderer.mm) and AndroidBoard3D.
// All conventions below come from ChessSetConventions in commonMain (single source of truth).
const PIECE_SCALE = ${ChessSetConventions.PIECE_SCALE};
// A board holds at most 32 pieces (promotion replaces a pawn, never adds). Instance 0 is the board;
// 1..32 are the piece-pool slots — mirrors iOS createInstancedAsset(kMaxPieces + 1).
const MAX_PIECES = ${ChessSetConventions.MAX_PIECES};
const MAX_HIGHLIGHTS = ${ChessSetConventions.MAX_HIGHLIGHTS};
// One quad per HighlightTone, in ordinal order. Keep in sync with
// ChessSetConventions.HIGHLIGHT_NODE_NAMES (interpolated, so it cannot drift).
const HIGHLIGHT_NODE_NAMES = ${ChessSetConventions.HIGHLIGHT_NODE_NAMES.joinToString(prefix = "[", postfix = "]") { "'" + it + "'" }};
const INSTANCE_COUNT = MAX_PIECES + MAX_HIGHLIGHTS + 1;
// Lifts the highlight quad off the tile just enough to beat z-fighting.
const HIGHLIGHT_LIFT_Y = 0.005;
// PieceKind ordinals (Board3DScene.kt): KING, QUEEN, ROOK, BISHOP, KNIGHT, PAWN -> glTF node names.
const KIND_NAMES = [${ChessSetConventions.KIND_NAMES.joinToString(", ") { "'$it'" }}];
// How many frames the loop still owes the most recent state push; see chess3dFrameLoopGate.
const DRAW_SETTLE_FRAMES = 3;

// Whether requestAnimationFrame should be scheduled for another frame.
//
// Split out as a pure value for the same reason iOS's FrameLoopGate is a struct: the gate's whole
// job is to decide when *not* to draw, and getting that wrong shows up as a board that never
// appears — but the karma browser has no GPU (Filament's Engine.create crashes on swiftshader), so
// the decision has to be checkable without ever initialising a renderer. See FrameLoopGateTest.
window.chess3dFrameLoopGate = {
    create() {
        return {
            // Board3DAnimationDriver's dirty flag, pushed down from Kotlin: *a frame was published
            // recently*. Deliberately not "an animation is running" — the driver publishes with its
            // loop parked on mount, on a new game, on a coach highlight landing on an idle board,
            // and after async init, and the narrower signal strands all four undrawn.
            //
            // Starts true so anything published before Kotlin's first signal is still drawn.
            wantsRender: true,
            // Frames still owed to state that has reached the renderer but that no frame has drawn.
            // This is the backstop wantsRender cannot be: init() is asynchronous (Filament.init,
            // then loadResources), and Kotlin's peer polls for readiness every 100 ms, so the
            // driver's ~48 ms dirty window has almost always closed by the time the queued scene
            // actually reaches this object — parking on wantsRender alone never draws the board.
            //
            // A count rather than iOS's boolean because Renderer::beginFrame declines frames and
            // the web bindings hide the answer: filament.js exposes render(swapChain, view) as
            // void, and the beginFrame/renderView/endFrame triple cannot replace it because the
            // binding's render() also calls Engine::execute(), which is not exposed at all
            // (jsbindings.cpp). So the loop owes a few attempts instead of one confirmed frame.
            undrawnFrames: DRAW_SETTLE_FRAMES,
            // Whether the glTF asset has finished loading. Unlike Android this is not a
            // texture-upload fence — filament.js decodes on its own setInterval, not inside the
            // render loop — it just keeps the loop alive across init so the first frame after the
            // asset lands draws without waiting on another push from Kotlin.
            assetReady: false,
        };
    },

    shouldRender(gate) {
        return gate.wantsRender || gate.undrawnFrames > 0 || !gate.assetReady;
    },
};

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

    // --- frame loop ---
    gate: window.chess3dFrameLoopGate.create(),
    // requestAnimationFrame never returns 0, so 0 is a safe "not scheduled" sentinel. Holding the
    // handle in one field is also what makes a double init() harmless: it can't start a second loop.
    rafHandle: 0,
    boundRender: null,
    // True only between init() and dispose() — the web counterpart of iOS nilling its CADisplayLink
    // on shutdown. Without it the gate's `!assetReady` term would keep rescheduling frames after
    // dispose() (which sets isReady = false), which is the loop-per-toggle leak this replaces.
    loopActive: false,

    init(canvas) {
        Filament.init(['${ChessSetConventions.GLB_ASSET}', '${ChessSetConventions.IBL_ASSET}', '${ChessSetConventions.SKYBOX_ASSET_BLURRED}'], () => {
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
                this.scene.setSkybox(this.engine.createSkyFromKtx1(Filament.assets['${ChessSetConventions.SKYBOX_ASSET_BLURRED}']));

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
                    // A brand-new asset has drawn nothing, whatever Kotlin's dirty signal currently
                    // says — the scene queued in the peer's pendingScene was very likely published
                    // long enough ago for the driver's window to have closed. Reassert it rather
                    // than trusting gate.wantsRender.
                    this.stateChanged();
                });
                this.boundRender = this.render.bind(this);
                this.loopActive = true;
                this.updateFrameLoop();
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
            // 'Plane' is hidden but must stay in the asset: it is the only primitive bound to the
            // 'black' material, which keeps that MaterialInstance alive for the piece pool.
            // See ChessSetMeshNames.getMaterialName in commonMain.
            const hide = KIND_NAMES.indexOf(name) !== -1 || name === 'Plane'
                || HIGHLIGHT_NODE_NAMES.indexOf(name) !== -1;
            if (hide) this.scene.remove(e); else this.scene.addEntity(e);
        });
        this.setInstanceTransform(board, 0, 0, 0, 0);
        for (let i = 1; i < INSTANCE_COUNT; i++) {
            this.forEachRenderable(this.instances[i], (e) => this.scene.remove(e));
        }
    },

    // Reconcile the fixed instance pool against an encoded Board3DScene ("kind,color,x,y,z,rot;...").
    // Called every animation frame by the shared Board3DAnimationDriver. Mirrors iOS setSceneEncoded.
    setScene(encoded) {
        // Before the guard: new state has arrived for the renderer either way, and the loop is what
        // has to put it on screen. (A push that lands before isReady is dropped here, but the
        // Kotlin peer holds it in pendingScene and re-pushes it on flush.)
        this.stateChanged();
        if (!this.isReady || !encoded) return;

        let piecesStr = encoded;
        let highlightsStr = "";
        const pipePos = encoded.indexOf('|');
        if (pipePos !== -1) {
            piecesStr = encoded.substring(0, pipePos);
            highlightsStr = encoded.substring(pipePos + 1);
        }

        const pieces = piecesStr ? piecesStr.split(';').map(rec => {
            const f = rec.split(',');
            return {
                kind: parseInt(f[0], 10),
                color: parseInt(f[1], 10),
                x: parseFloat(f[2]),
                y: parseFloat(f[3]),
                z: parseFloat(f[4]),
                rotY: parseFloat(f[5])
            };
        }) : [];
        
        const highlights = highlightsStr ? highlightsStr.split(';').map(rec => {
            const f = rec.split(',');
            return {
                x: parseFloat(f[0]),
                y: parseFloat(f[1]),
                z: parseFloat(f[2]),
                // Absent on an old-format record; index 0 is NEUTRAL, the authored blue.
                tone: f.length > 3 ? parseInt(f[3], 10) || 0 : 0
            };
        }) : [];

        for (let slot = 0; slot < MAX_PIECES; slot++) {
            const inst = this.instances[slot + 1];
            if (!inst) continue;

            if (slot >= pieces.length) {
                this.forEachRenderable(inst, (e) => this.scene.remove(e));
                continue;
            }

            const p = pieces[slot];
            const meshName = (p.kind >= 0 && p.kind < KIND_NAMES.length) ? KIND_NAMES[p.kind] : "";
            const materialName = (p.color === 0) ? "white" : "black";
            const targetMat = this.materialNamed(materialName, inst);

            this.forEachRenderable(inst, (e, name) => {
                const show = (name === meshName);
                if (show) {
                    this.scene.addEntity(e);
                    if (targetMat) {
                        const rm = this.renderableManager;
                        const ri = rm.getInstance(e);
                        if (ri) {
                            const prims = rm.getPrimitiveCount(ri);
                            for (let pr = 0; pr < prims; pr++) {
                                rm.setMaterialInstanceAt(ri, pr, targetMat);
                            }
                        }
                    }
                } else {
                    this.scene.remove(e);
                }
            });

            this.setInstanceTransform(inst, p.x, p.y, p.z, p.rotY);
        }
        
        for (let slot = 0; slot < MAX_HIGHLIGHTS; slot++) {
            const inst = this.instances[MAX_PIECES + 1 + slot];
            if (!inst) continue;

            if (slot >= highlights.length) {
                this.forEachRenderable(inst, (e) => this.scene.remove(e));
                continue;
            }

            const h = highlights[slot];

            // No material binding: chess.glb carries one quad per tone and the tone selects which
            // node to show. Recolouring a single quad at runtime cannot work the obvious way — the
            // colour is the material's emissiveFactor, not baseColorFactor. See ChessSetConventions.
            const wanted = HIGHLIGHT_NODE_NAMES[h.tone] || HIGHLIGHT_NODE_NAMES[0];
            this.forEachRenderable(inst, (e, name) => {
                if (name === wanted) {
                    this.scene.addEntity(e);
                    const rm = this.renderableManager;
                    const ri = rm.getInstance(e);
                    if (ri) {
                        rm.setCastShadows(ri, false);
                        rm.setReceiveShadows(ri, false);
                    }
                } else {
                    this.scene.remove(e);
                }
            });

            // The Plane node's baked local translation was zeroed in chess.glb, so the square centre
            // applies directly; the small y lift clears the tile to avoid z-fighting.
            this.setInstanceTransform(inst, h.x, HIGHLIGHT_LIFT_Y, h.z, 0.0);
        }
    },

    setCamera(px, py, pz, tx, ty, tz, ux, uy, uz, fov, aspect) {
        this.stateChanged();
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
        this.stateChanged();
        if (!this.view) return;
        this.view.setViewport([0, 0, w, h]);
    },

    // Resume (true) or park (false) the requestAnimationFrame loop. This is the web's counterpart to
    // SceneView's `isRendering` on Android and displayLink.isPaused on iOS: an untouched 3D board
    // otherwise redraws at the display refresh rate for as long as the page is open. Called from
    // Kotlin with Board3DAnimationDriver's dirty flag; see FilamentChessPeer.setRenderingActive.
    setRenderingActive(active) {
        this.gate.wantsRender = !!active;
        this.updateFrameLoop();
    },

    // New state reached the renderer; keep drawing until some frames have put it on screen.
    stateChanged() {
        this.gate.undrawnFrames = DRAW_SETTLE_FRAMES;
        this.updateFrameLoop();
    },

    updateFrameLoop() {
        this.gate.assetReady = this.isReady;
        const wanted = this.loopActive && window.chess3dFrameLoopGate.shouldRender(this.gate);
        if (wanted && !this.rafHandle) {
            this.rafHandle = requestAnimationFrame(this.boundRender);
        } else if (!wanted && this.rafHandle) {
            cancelAnimationFrame(this.rafHandle);
            this.rafHandle = 0;
        }
    },

    render() {
        // Clear the handle first: this frame has fired, so the slot is free for updateFrameLoop()
        // below (or for a state push arriving from Kotlin mid-frame) to schedule the next one.
        this.rafHandle = 0;
        if (this.isReady && this.renderer && this.view && this.swapChain) {
            this.renderer.render(this.swapChain, this.view);
            if (this.gate.undrawnFrames > 0) this.gate.undrawnFrames--;
        }
        this.updateFrameLoop();
    },

    // Called on every 2D<->3D toggle (Kotlin detach()/dispose()). init() builds a brand-new Engine
    // each attach and the canvas can differ between attaches, so the correct model is destroy-and-
    // recreate: tear the Engine down here and let the next init() start from a clean slate (it
    // reassigns every field below). Destroying the Engine releases all the GPU resources it owns
    // (scene/view/camera/renderer/swapChain/IBL/skybox/asset/instances/material instances), so the
    // single Engine-level teardown is the primary cleanup. Verified static API in filament@1.53.4:
    // Filament.Engine.destroy(engine) (jsbindings.cpp: class_function("destroy", ...) -> Engine::destroy).
    // Wrapped in try/catch so a wrong call can't wedge re-init; state is always reset at the end.
    dispose() {
        // Stop the frame loop before the Engine it draws through is destroyed, and leave it stopped:
        // init() is what starts a loop, and `loopActive` is what keeps updateFrameLoop from
        // resurrecting one. Previously render() rescheduled unconditionally, so every 2D<->3D toggle
        // left another rAF loop running against a torn-down Engine for the life of the page.
        if (this.rafHandle) cancelAnimationFrame(this.rafHandle);
        this.rafHandle = 0;
        this.loopActive = false;
        this.boundRender = null;
        // A fresh gate, so the next init() starts from "nothing has been drawn yet" rather than
        // inheriting a parked signal from the board that was just torn down.
        this.gate = window.chess3dFrameLoopGate.create();
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
