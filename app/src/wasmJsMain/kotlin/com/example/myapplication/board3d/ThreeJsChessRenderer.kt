package com.example.myapplication.board3d

import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.*
import org.w3c.dom.HTMLCanvasElement
import org.w3c.dom.HTMLScriptElement
import org.w3c.dom.Element

// Console helpers — Kotlin/Wasm's `console` is internal, so go through @JsFun.
@JsFun("(msg) => { console.log(msg); }")
private external fun log(msg: String)
@JsFun("(msg) => { console.warn(msg); }")
private external fun warn(msg: String)
@JsFun("(msg) => { console.error(msg); }")
private external fun error(msg: String)

/**
 * Production three.js chess renderer for the wasm/web target. Loads three.js from CDN via
 * dynamic script injection (import map + ES module), renders chess.glb via WebGLRenderer with
 * PBR + shadows + ACES tonemapping. Driven from Kotlin via [window.chess3d] JS API.
 *
 * This is the same rendering pipeline as the iOS WKWebView path — same chess3d-renderer.js
 * module, same PBR defaults, same visual target. The only difference is the host: a browser
 * canvas on web vs a WKWebView canvas on iOS.
 */
class ThreeJsChessRenderer : Chess3DBoardRenderer {

    private var canvas: HTMLCanvasElement? = null
    private var pendingFen: String = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
    private var camera: CameraParams = OrbitCameraController.DEFAULT_WHITE_VIEW
    private var selectedSquare: BoardSquare? = null
    private var isReady = false
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // Move arc + selection bounce are computed in commonMain; each frame the interpolated scene is
    // pushed to three.js as the encoded wire form (chess3d.setScene).
    private val driver = Board3DAnimationDriver(scope) { scene ->
        if (isReady) chess3dSetScene(scene.encode())
    }

    override fun attach(surface: Chess3DSurface) {
        val wasmSurface = surface as? WasmChess3DSurface ?: run {
            warn("[chess3d] attach() rejected surface: ${surface::class.simpleName}")
            return
        }
        canvas = wasmSurface.canvas
        log("[chess3d] attach() called, canvas=${wasmSurface.widthPx}x${wasmSurface.heightPx}")

        scope.launch {
            try {
                log("[chess3d] step 1: injectThreeJs()")
                injectThreeJs()
                log("[chess3d] step 1 done: isThreeJsLoaded=${isThreeJsLoaded()}")
                if (!isThreeJsLoaded()) {
                    error("[chess3d] ABORT: window.chess3d not defined after injectThreeJs — " +
                        "three.js module failed to load (check Network tab for CDN failures or console for import errors)")
                    return@launch
                }
                delay(500) // let three.js + module parse settle
                log("[chess3d] step 2: initRenderer(canvas)")
                initRenderer(wasmSurface.canvas)
                log("[chess3d] step 2 done")
                delay(2000) // wait for chess.glb load
                isReady = true
                log("[chess3d] step 3: ready, applying scene + camera")
                driver.setPosition(runCatching { Board3DSceneMapper.fromFen(pendingFen) }.getOrNull(), null)
                applyCamera(camera)
                driver.setSelected(selectedSquare)
            } catch (t: Throwable) {
                error("[chess3d] attach() failed: ${t.message}")
            }
        }
    }

    override fun detach() {
        chess3dDispose()
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
                chess3dResize(event.widthPx, event.heightPx)
            }
            else -> {}
        }
    }

    override fun dispose() {
        driver.cancel()
        scope.cancel()
        chess3dDispose()
    }

    // --- JS interop ---

    private fun applyCamera(cam: CameraParams) {
        if (isReady) chess3dSetCamera(
            cam.position.x, cam.position.y, cam.position.z,
            cam.target.x, cam.target.y, cam.target.z,
            cam.up.x, cam.up.y, cam.up.z,
            cam.fovYDegrees, cam.aspect
        )
    }

    // --- Script injection ---

    private suspend fun injectThreeJs() {
        // Check if three.js is already loaded (e.g. from a previous renderer instance).
        if (isThreeJsLoaded()) return

        // The import map for bare specifiers ('three', 'three/addons/') is now static in index.html
        // — browsers reject import maps injected after module resolution begins, so the dynamic
        // <script type="importmap"> injection this code used to do was silently ignored on modern
        // browsers. Just inject the chess3d-renderer module; it resolves three.js via the static map.
        val module = document.createElement("script") as HTMLScriptElement
        module.type = "module"
        module.textContent = CHESS3D_RENDERER_JS
        document.head!!.appendChild(module)

        // Wait for the module to load.
        var attempts = 0
        while (!isThreeJsLoaded() && attempts < 100) {
            delay(100)
            attempts++
        }
    }

    private fun initRenderer(canvas: HTMLCanvasElement) {
        canvas.width = 1024
        canvas.height = 1024
        chess3dInit(canvas)
    }
}

private fun isThreeJsLoaded(): Boolean =
    js("(typeof window.chess3d !== 'undefined')")

@JsFun("(canvas) => { window.chess3d.init(canvas); }")
private external fun chess3dInit(canvas: HTMLCanvasElement)

@JsFun("(s) => { window.chess3d.setScene(s); }")
private external fun chess3dSetScene(s: String)

@JsFun("(px,py,pz,tx,ty,tz,ux,uy,uz,fov,aspect) => { window.chess3d.setCamera(px,py,pz,tx,ty,tz,ux,uy,uz,fov,aspect); }")
private external fun chess3dSetCamera(
    px: Float, py: Float, pz: Float, tx: Float, ty: Float, tz: Float,
    ux: Float, uy: Float, uz: Float, fov: Float, aspect: Float,
)

@JsFun("(w, h) => { window.chess3d.resize(w, h); }")
private external fun chess3dResize(w: Int, h: Int)

@JsFun("() => { if (window.chess3d) window.chess3d.dispose(); }")
private external fun chess3dDispose()

/**
 * The chess3d-renderer.js source. DO NOT EDIT BY HAND — this string is regenerated from
 * `tools/chess3d-renderer/chess3d-renderer.js` (the single source of truth shared with the iOS
 * WKWebView bundle) by `tools/chess3d-renderer/build.mjs`. The `import` statements below are
 * resolved by the static import map in `index.html`. The module assigns `window.chess3d`.
 */
private const val CHESS3D_RENDERER_JS = """
// Production three.js chess renderer — the single source of truth for BOTH the web (wasm) and
// iOS (WKWebView) 3D boards. The web target inlines this verbatim as a module <script> (resolving
// 'three' / 'three/addons/' via the static import map in index.html); the iOS target bundles it
// with esbuild into chess3d-bundle.js (see build.mjs). Do not edit the generated copies by hand.
//
// Visual target: faithful parity with the Android Filament reference. Android renders chess.glb
// verbatim — its marble tiles (nodes a1..h8), the engraved stone "frame" rim, and the six piece
// template meshes — scaled to the game's ±4 board and lit by the papermill environment. This
// renderer does the same with three.js: the whole glTF scene is shown (materials/textures baked in
// the glb), pieces are cloned per FEN square at scale 0.5 with the glb's own white/black materials,
// and lighting is a warm directional key + RoomEnvironment IBL (three's closest analogue to the
// papermill IBL Android loads from KTX).

import * as THREE from 'three'
import { GLTFLoader } from 'three/addons/loaders/GLTFLoader.js'
import { RGBELoader } from 'three/addons/loaders/RGBELoader.js'
import { RoomEnvironment } from 'three/addons/environments/RoomEnvironment.js'

// The board lives in the game's ±4 space (1-unit squares). chess.glb is authored at ±8 with
// 2-unit squares, so every glb node is scaled by 0.5 — matching Android's scale=0.5 exactly.
// Piece world coordinates now arrive already in this ±4 space from Kotlin (Board3DScene/BoardGeometry
// via chess3d.setScene), including the animated y (move arc hop + selection bounce).
const PIECE_SCALE = 0.5
// Glb node names that are piece templates (kept at origin as geometry sources) or stray helpers
// (the "Plane" shadow catcher Android also hides). Everything else (a1..h8 tiles + frame) is shown.
const HIDDEN_NODES = new Set(['king', 'queen', 'rook', 'bishop', 'knight', 'pawn', 'plane'])
// Piece template node NAMES in the glb (used to recognise + stash them).
const PIECE_NAMES = new Set(['king', 'queen', 'rook', 'bishop', 'knight', 'pawn'])
// Index = Kotlin PieceKind ordinal (see Board3DScene.encode): KING,QUEEN,ROOK,BISHOP,KNIGHT,PAWN.
const KIND_NAMES = ['king', 'queen', 'rook', 'bishop', 'knight', 'pawn']

let renderer, scene, camera, boardRoot
const pieceTemplates = {}
// Fixed pool of piece nodes, reconciled by index against the scene Kotlin pushes each frame
// (chess3d.setScene). Each slot = { holder: Object3D, kind: number, color: number }. Reusing nodes
// (only rebuilding a slot when its kind/colour changes) keeps the 60fps animation cheap.
const piecePool = []
// White/black materials sourced from the glb (their textured "white"/"black" materials), so pieces
// render with the same albedo the modeller authored — not a flat replacement colour.
let whiteMat = null
let blackMat = null

// Load the papermill environment (the same HDR Android's Filament backend and the desktop Vulkan
// backend use). The skybox equirect is the visible backdrop (vegetation + sun + sky); the IBL
// equirect is PMREM-prefiltered for PBR specular/diffuse lighting. Falls back to RoomEnvironment +
// a flat backdrop if the bundled .hdr files are unavailable.
async function loadEnvironment() {
  const rgbe = new RGBELoader()
  try {
    const sky = await rgbe.loadAsync('./papermill_skybox.hdr')
    sky.mapping = THREE.EquirectangularReflectionMapping
    scene.background = sky
    const ibl = await rgbe.loadAsync('./papermill_ibl.hdr')
    ibl.mapping = THREE.EquirectangularReflectionMapping
    const pmrem = new THREE.PMREMGenerator(renderer)
    const envMap = pmrem.fromEquirectangular(ibl).texture
    pmrem.dispose()
    scene.environment = envMap
    return true
  } catch (e) {
    console.warn('[chess3d] papermill HDR load failed; falling back to RoomEnvironment', e)
    scene.background = new THREE.Color(0x1a2a3a)
    const pmrem = new THREE.PMREMGenerator(renderer)
    scene.environment = pmrem.fromScene(new RoomEnvironment(), 0.04).texture
    return false
  }
}

window.chess3d = {
  async init(canvas) {
    try {
      // A re-init builds a fresh scene; drop pool slots pointing at the previous scene's nodes.
      piecePool.length = 0
      renderer = new THREE.WebGLRenderer({ canvas, antialias: true, alpha: false })
      renderer.setPixelRatio(window.devicePixelRatio || 1)
      // Android (the visual reference) has NO cast shadows — it's lit purely by the papermill IBL,
      // whose radiance cube bakes in the sun's direction so the lighting already reads as sunlight
      // and stays fixed relative to the world as the camera orbits (as it would in nature). Adding
      // a shadow-casting directional here lit the board "from nowhere" (no visible sun disk to
      // explain the hard shadows) and made the scene depart from Android, so we keep it IBL-only.
      renderer.shadowMap.enabled = false
      renderer.toneMapping = THREE.ACESFilmicToneMapping
      renderer.toneMappingExposure = 1.0
      renderer.outputColorSpace = THREE.SRGBColorSpace

      scene = new THREE.Scene()

      // Papermill skybox (visible background) + IBL (sole light source), matching desktop Vulkan +
      // Android Filament. Camera created before the env load so a failed load can't strand init.
      camera = new THREE.PerspectiveCamera(45, canvas.width / canvas.height, 0.05, 200)
      await loadEnvironment()

      // Selection is shown by bouncing the picked piece (its y oscillates in setScene), not a disc.

      await loadGlb()
      animate()
      return true
    } catch (e) {
      console.error('[chess3d] init failed', e)
      return false
    }
  },

  // Renders one animation frame. s = Board3DScene.encode() from Kotlin: pieces as
  // "kindOrdinal,colorOrdinal,x,y,z,rotationYDegrees" joined by ";" (empty string = no pieces).
  // World coords and y (move arc hop / selection bounce) are computed in commonMain; the pool just
  // mirrors them, rebuilding a slot only when its kind/colour changes (promotion or a new position).
  setScene(s) {
    if (!whiteMat) return
    const items = s.length ? s.split(';') : []
    for (let i = 0; i < items.length; i++) {
      const f = items[i].split(',')
      const kind = parseInt(f[0], 10)
      const color = parseInt(f[1], 10)
      let slot = piecePool[i]
      if (!slot) { slot = { holder: null, kind: -1, color: -1 }; piecePool[i] = slot }
      if (slot.kind !== kind || slot.color !== color) {
        if (slot.holder && slot.holder.parent) slot.holder.parent.remove(slot.holder)
        const tpl = pieceTemplates[KIND_NAMES[kind]]
        if (!tpl) { slot.holder = null; slot.kind = -1; slot.color = -1; continue }
        const isWhite = color === 0
        const piece = tpl.clone(true)
        piece.traverse(o => {
          if (!o.isMesh) return
          o.castShadow = true
          o.receiveShadow = true
          o.material = isWhite ? whiteMat : blackMat
        })
        piece.scale.setScalar(PIECE_SCALE)
        scene.add(piece)
        slot.holder = piece
        slot.kind = kind
        slot.color = color
      }
      const h = slot.holder
      if (!h) continue
      h.visible = true
      h.position.set(parseFloat(f[2]), parseFloat(f[3]), parseFloat(f[4]))
      h.rotation.y = parseFloat(f[5]) * Math.PI / 180
    }
    // Hide pool slots beyond the current piece count (e.g. after a capture).
    for (let i = items.length; i < piecePool.length; i++) {
      const slot = piecePool[i]
      if (slot && slot.holder) slot.holder.visible = false
    }
  },

  setCamera(px, py, pz, tx, ty, tz, ux, uy, uz, fov, aspect) {
    camera.position.set(px, py, pz)
    camera.up.set(ux, uy, uz)
    camera.lookAt(tx, ty, tz)
    camera.fov = fov
    camera.aspect = aspect
    camera.updateProjectionMatrix()
  },

  resize(w, h) {
    if (!renderer) return
    renderer.setSize(w, h, false)
    camera.aspect = w / h
    camera.updateProjectionMatrix()
  },

  dispose() {
    if (renderer) { renderer.dispose(); renderer = null }
    scene = null
    camera = null
  },
}

async function loadGlb() {
  const loader = new GLTFLoader()
  const paths = ['./chess.glb', 'chess.glb', '/app/src/commonMain/composeResources/files/models/chess.glb']
  let gltf = null
  for (const p of paths) {
    try {
      gltf = await new Promise((res, rej) => loader.load(p, res, undefined, rej))
      break
    } catch (e) { /* try next path */ }
  }
  if (!gltf) { console.error('[chess3d] chess.glb not found'); return }

  // The glb's white/black piece materials carry their albedo/mr textures; reuse them verbatim so
  // the ivory/ebony look matches the model rather than a flat colour override.
  const materials = await gltf.parser.getDependencies('material')
  whiteMat = materials.find(m => m.name === 'white') || materials[0] || null
  blackMat = materials.find(m => m.name === 'black') || whiteMat

  // Stash the six piece template meshes by kind (they sit at the glb origin).
  gltf.scene.traverse(o => {
    const name = o.name ? o.name.toLowerCase() : ''
    if (name && PIECE_NAMES.has(name)) pieceTemplates[name] = o
  })

  // Add the whole glb scene under a 0.5 group: marble tiles + stone frame land at the game's ±4
  // board, matching Android. Hide the piece templates (geometry sources) + the stray "Plane".
  boardRoot = new THREE.Group()
  boardRoot.scale.setScalar(PIECE_SCALE)
  gltf.scene.traverse(o => {
    const name = o.name ? o.name.toLowerCase() : ''
    if (name && HIDDEN_NODES.has(name)) o.visible = false
    if (o.isMesh) { o.castShadow = true; o.receiveShadow = true }
  })
  boardRoot.add(gltf.scene)
  scene.add(boardRoot)
  // Marble tiles + frame read as pixelated at grazing angles without anisotropic filtering —
  // Filament enables it by default; three.js needs it set per-texture. Crank every glb texture to
  // the renderer's max anisotropy and ensure mipmaps are generated.
  const maxAniso = renderer.capabilities.getMaxAnisotropy()
  const seenTextures = new Set()
  gltf.scene.traverse(o => {
    if (!o.isMesh) return
    for (const mat of (Array.isArray(o.material) ? o.material : [o.material])) {
      if (!mat) continue
      for (const t of [mat.map, mat.normalMap, mat.roughnessMap, mat.metalnessMap, mat.aoMap, mat.emissiveMap]) {
        if (t && !seenTextures.has(t.uuid)) {
          seenTextures.add(t.uuid)
          t.anisotropy = maxAniso
          t.generateMipmaps = true
          t.minFilter = THREE.LinearMipmapLinearFilter
          t.needsUpdate = true
        }
      }
    }
  })
}

function animate() {
  requestAnimationFrame(animate)
  if (renderer && scene && camera) renderer.render(scene, camera)
}

"""
