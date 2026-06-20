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
// Part C: EffectComposer supplies both crisper AA (HalfFloat MSAA render target + supersample +
// SMAA) and bloom. Pinned to three@0.169.0 via the import map (web) / esbuild (iOS bundle).
import { EffectComposer } from 'three/addons/postprocessing/EffectComposer.js'
import { RenderPass } from 'three/addons/postprocessing/RenderPass.js'
import { UnrealBloomPass } from 'three/addons/postprocessing/UnrealBloomPass.js'
import { SMAAPass } from 'three/addons/postprocessing/SMAAPass.js'
import { OutputPass } from 'three/addons/postprocessing/OutputPass.js'

// The board lives in the game's ±4 space (1-unit squares). chess.glb is authored at ±8 with
// 2-unit squares, so every glb node is scaled by 0.5 — matching Android's scale=0.5 exactly.
// Piece world coordinates now arrive already in this ±4 space from Kotlin (Board3DScene/BoardGeometry
// via chess3d.setScene), including the animated y (move arc hop + selection bounce).
const PIECE_SCALE = 0.5
// Part C AA + bloom tunables. SS supersamples the render buffer (sharper silhouettes / sculpt
// detail — the main web regression was MSAA-only softness); SS_CAP bounds the fill-rate cost.
const SS = 1.5            // render-buffer supersample factor (sharpness)
const SS_CAP = 2.5        // hard cap on the effective pixel ratio
// Bloom tuned to a subtle Android-matching halo. The earlier punchy preset (strength 0.7 /
// threshold 0.6) blew out the whole bright board centre; raise the threshold so only the brightest
// specular/sun highlights bloom, and lower the strength so it reads as atmosphere, not glare.
const BLOOM_STRENGTH = 0.35
const BLOOM_RADIUS = 0.4
const BLOOM_THRESHOLD = 0.85
// De-band (mirrors the desktop Vulkan renderer): the glb whites/blacks albedo bakes high-contrast
// horizontal wood grain and the shared metallicRoughness map's green channel is striped too, so on
// the lathe-turned pieces both wrap into hard rings. We soften the albedo grain toward the per-
// material mean wood colour and flatten roughness to a constant (even sheen, not glossy/matte bands).
// Means measured from the glb albedos (sRGB).
const WHITE_MEAN = [0.427, 0.361, 0.263]
const BLACK_MEAN = [0.176, 0.114, 0.075]
const GRAIN_STRENGTH = 0.5   // 1 = full grain; 0 = flat colour. 0.5 keeps subtle character.
const PIECE_ROUGHNESS = 0.4  // constant roughness for pieces (replaces the striped roughnessMap)
let composer, bloomPass, smaaPass   // alongside `renderer, scene, camera`
let usePost = false                 // set true once the composer builds; falls back to bare render
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
      // Part C: supersample the render buffer for crisper edges (the web regression was MSAA-only
      // softness). The composer below renders into a HalfFloat MSAA target so AA stays sharp and
      // bloom can work in HDR before the OutputPass tonemaps.
      renderer.setPixelRatio(Math.min((window.devicePixelRatio || 1) * SS, SS_CAP))
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
      // Part C: build the post chain. RenderPass -> UnrealBloom (HDR) -> SMAA (edge AA) -> OutputPass
      // (tonemap + sRGB). Wrapped so any addon/load failure falls back to the bare render loop below
      // rather than a black canvas.
      try {
        const size = renderer.getDrawingBufferSize(new THREE.Vector2())
        const rt = new THREE.WebGLRenderTarget(size.x, size.y, {
          type: THREE.HalfFloatType, samples: 4, // MSAA-resolved HDR input = crisp silhouettes
        })
        composer = new EffectComposer(renderer, rt)
        composer.addPass(new RenderPass(scene, camera))
        bloomPass = new UnrealBloomPass(new THREE.Vector2(size.x, size.y), BLOOM_STRENGTH, BLOOM_RADIUS, BLOOM_THRESHOLD)
        composer.addPass(bloomPass)
        smaaPass = new SMAAPass(size.x, size.y)
        composer.addPass(smaaPass)
        composer.addPass(new OutputPass())
        usePost = true
      } catch (e) {
        console.warn('[chess3d] EffectComposer init failed; falling back to bare render', e)
        composer = null; bloomPass = null; smaaPass = null; usePost = false
      }
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
    renderer.setPixelRatio(Math.min((window.devicePixelRatio || 1) * SS, SS_CAP))
    renderer.setSize(w, h, false)
    camera.aspect = w / h
    camera.updateProjectionMatrix()
    // Part C: keep the composer + its passes in sync with the (supersampled) drawing buffer.
    if (composer || bloomPass || smaaPass) {
      const s = renderer.getDrawingBufferSize(new THREE.Vector2())
      if (composer) composer.setSize(s.x, s.y)
      if (bloomPass) bloomPass.setSize(s.x, s.y)
      if (smaaPass) smaaPass.setSize(s.x, s.y)
    }
  },

  dispose() {
    if (composer) { composer.dispose(); composer = null }
    bloomPass = null; smaaPass = null; usePost = false
    if (renderer) { renderer.dispose(); renderer = null }
    scene = null
    camera = null
  },
}

// De-band a shared piece material in place (see WHITE_MEAN/BLACK_MEAN comment). Flattens the striped
// roughness/metalness maps to constants, and injects a tiny shader patch that pulls the sampled
// albedo toward the per-material mean wood colour so the baked grain reads as subtle texture, not
// hard rings — the three.js analogue of the desktop renderer's grainStrength/roughnessOverride.
function debandPieceMaterial(mat, meanSrgb) {
  if (!mat) return
  // Flatten roughness: the glb metallicRoughness map's green channel is striped. Drop the map and
  // use a constant; also drop the metalness map (wood is dielectric) so it can't add banding.
  mat.roughnessMap = null
  mat.roughness = PIECE_ROUGHNESS
  mat.metalnessMap = null
  mat.metalness = 0.0
  // Soften the striped albedo grain toward the linearised mean. The map is sampled into linear space
  // (three colour management), so `diffuseColor.rgb` after <map_fragment> is linear — mix toward the
  // linearised mean there. GRAIN_STRENGTH == 1 would be a no-op.
  const meanLin = new THREE.Vector3(
    Math.pow(meanSrgb[0], 2.2), Math.pow(meanSrgb[1], 2.2), Math.pow(meanSrgb[2], 2.2),
  )
  mat.onBeforeCompile = (shader) => {
    shader.uniforms.uGrainMean = { value: meanLin }
    shader.uniforms.uGrainStrength = { value: GRAIN_STRENGTH }
    shader.fragmentShader =
      'uniform vec3 uGrainMean;\nuniform float uGrainStrength;\n' +
      shader.fragmentShader.replace(
        '#include <map_fragment>',
        '#include <map_fragment>\n  diffuseColor.rgb = mix(uGrainMean, diffuseColor.rgb, uGrainStrength);',
      )
  }
  mat.needsUpdate = true
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
  debandPieceMaterial(whiteMat, WHITE_MEAN)
  debandPieceMaterial(blackMat, BLACK_MEAN)

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
  if (!renderer || !scene || !camera) return
  // Part C: drive the post chain when the composer built; otherwise fall back to the bare render.
  if (usePost && composer) composer.render()
  else renderer.render(scene, camera)
}

"""
