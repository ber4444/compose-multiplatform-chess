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
