package com.example.myapplication.board3d

import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.*
import org.w3c.dom.HTMLCanvasElement
import org.w3c.dom.HTMLScriptElement
import org.w3c.dom.Element

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

    override fun attach(surface: Chess3DSurface) {
        val wasmSurface = surface as? WasmChess3DSurface ?: return
        canvas = wasmSurface.canvas

        scope.launch {
            injectThreeJs()
            delay(500) // let three.js + module parse settle
            initRenderer(wasmSurface.canvas)
            delay(2000) // wait for chess.glb load
            isReady = true
            applyFen(pendingFen)
            applyCamera(camera)
            applySelection()
        }
    }

    override fun detach() {
        chess3dDispose()
        isReady = false
    }

    override fun updatePosition(fen: String) {
        pendingFen = fen
        applyFen(fen)
    }

    override fun setSelectedSquare(square: BoardSquare?) {
        selectedSquare = square
        applySelection()
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
        scope.cancel()
        chess3dDispose()
    }

    // --- JS interop ---

    private fun applyFen(fen: String) { if (isReady) chess3dSetFEN(fen) }
    private fun applyCamera(cam: CameraParams) {
        if (isReady) chess3dSetCamera(
            cam.position.x, cam.position.y, cam.position.z,
            cam.target.x, cam.target.y, cam.target.z,
            cam.up.x, cam.up.y, cam.up.z,
            cam.fovYDegrees, cam.aspect
        )
    }
    private fun applySelection() {
        if (!isReady) return
        val sq = selectedSquare
        if (sq != null) chess3dSetSelectedSquare(sq.row, sq.col)
        else chess3dSetSelectedSquare(-1, -1)
    }

    // --- Script injection ---

    private suspend fun injectThreeJs() {
        // Check if three.js is already loaded (e.g. from a previous renderer instance).
        if (isThreeJsLoaded()) return

        // Inject import map for bare module specifiers.
        val importMap = document.createElement("script") as HTMLScriptElement
        importMap.type = "importmap"
        importMap.textContent = """
            {"imports":{
              "three":"https://cdn.jsdelivr.net/npm/three@0.169.0/build/three.module.js",
              "three/addons/":"https://cdn.jsdelivr.net/npm/three@0.169.0/examples/jsm/"
            }}
        """.trimIndent()
        document.head!!.appendChild(importMap)

        // Inject the chess3d-renderer module. This is the same code as chess3d-renderer.js but
        // inlined so it loads without a separate file. The module creates window.chess3d.
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

// --- JS interop functions ---

private fun isThreeJsLoaded(): Boolean =
    js("(typeof window.chess3d !== 'undefined')")

@JsFun("(canvas) => { window.chess3d.init(canvas); }")
private external fun chess3dInit(canvas: HTMLCanvasElement)

@JsFun("(fen) => { window.chess3d.setFEN(fen); }")
private external fun chess3dSetFEN(fen: String)

@JsFun("(px,py,pz,tx,ty,tz,ux,uy,uz,fov,aspect) => { window.chess3d.setCamera(px,py,pz,tx,ty,tz,ux,uy,uz,fov,aspect); }")
private external fun chess3dSetCamera(
    px: Float, py: Float, pz: Float, tx: Float, ty: Float, tz: Float,
    ux: Float, uy: Float, uz: Float, fov: Float, aspect: Float,
)

@JsFun("(row, col) => { window.chess3d.setSelectedSquare(row >= 0 ? row : null, col >= 0 ? col : null); }")
private external fun chess3dSetSelectedSquare(row: Int, col: Int)

@JsFun("(w, h) => { window.chess3d.resize(w, h); }")
private external fun chess3dResize(w: Int, h: Int)

@JsFun("() => { if (window.chess3d) window.chess3d.dispose(); }")
private external fun chess3dDispose()

/**
 * The chess3d-renderer.js source (unbundled — loads three.js from the import map). This is the
 * same code as chess3d-renderer.js in the iOS bundle, minus the `import` statements (which are
 * resolved by the import map injected above). The module assigns window.chess3d.
 */
private const val CHESS3D_RENDERER_JS = """
import * as THREE from 'three'
import { GLTFLoader } from 'three/addons/loaders/GLTFLoader.js'
import { RoomEnvironment } from 'three/addons/environments/RoomEnvironment.js'

const BOARD_HALF = 3.5
const FEN_TO_NODE = { k:'king', q:'queen', r:'rook', b:'bishop', n:'knight', p:'pawn' }
let renderer, scene, camera, pieceTemplates = {}, livePieces = [], selectionMesh = null
const whiteMat = new THREE.MeshStandardMaterial({ color: 0xf5f0e0, roughness: 0.35, metalness: 0.05 })
const blackMat = new THREE.MeshStandardMaterial({ color: 0x202028, roughness: 0.35, metalness: 0.05 })

window.chess3d = {
    async init(canvas) {
        renderer = new THREE.WebGLRenderer({ canvas, antialias: true, alpha: false })
        renderer.setPixelRatio(window.devicePixelRatio || 1)
        renderer.shadowMap.enabled = true
        renderer.shadowMap.type = THREE.PCFSoftShadowMap
        renderer.toneMapping = THREE.ACESFilmicToneMapping
        renderer.toneMappingExposure = 1.0
        renderer.outputColorSpace = THREE.SRGBColorSpace
        scene = new THREE.Scene()
        scene.background = new THREE.Color(0x1a2a3a)
        const pmrem = new THREE.PMREMGenerator(renderer)
        scene.environment = pmrem.fromScene(new RoomEnvironment(renderer), 0.04).texture
        camera = new THREE.PerspectiveCamera(45, canvas.width / canvas.height, 0.05, 100)
        const keyLight = new THREE.DirectionalLight(0xffffff, 2.5)
        keyLight.position.set(5, 9, 4.5)
        keyLight.castShadow = true
        keyLight.shadow.mapSize.set(2048, 2048)
        keyLight.shadow.camera.near = 0.5; keyLight.shadow.camera.far = 50
        keyLight.shadow.camera.left = -8; keyLight.shadow.camera.right = 8
        keyLight.shadow.camera.top = 8; keyLight.shadow.camera.bottom = -8
        keyLight.shadow.bias = -0.0005
        scene.add(keyLight)
        scene.add(new THREE.HemisphereLight(0xb1c8e0, 0x3a3a3a, 0.4))
        // Board tiles
        const lightTile = new THREE.MeshStandardMaterial({ color: 0xe8d8b0, roughness: 0.6 })
        const darkTile = new THREE.MeshStandardMaterial({ color: 0x5a3a1c, roughness: 0.6 })
        const tileGeo = new THREE.BoxGeometry(1, 0.1, 1)
        for (let r = 0; r < 8; r++) for (let c = 0; c < 8; c++) {
            const t = new THREE.Mesh(tileGeo, (r+c)%2===0 ? lightTile : darkTile)
            t.position.set(c - BOARD_HALF, -0.05, r - BOARD_HALF); t.receiveShadow = true
            scene.add(t)
        }
        // Selection highlight
        selectionMesh = new THREE.Mesh(
            new THREE.CylinderGeometry(0.46, 0.46, 0.04, 48),
            new THREE.MeshStandardMaterial({ color: 0x3DDC6B, emissive: 0x3DDC6B, emissiveIntensity: 0.3, transparent: true, opacity: 0.55 })
        )
        selectionMesh.visible = false; scene.add(selectionMesh)
        await loadGlb()
        animate()
        return true
    },
    setFEN(fen) {
        for (const p of livePieces) scene.remove(p); livePieces = []
        if (!pieceTemplates || Object.keys(pieceTemplates).length === 0) return
        const rows = fen.split(' ')[0].split('/')
        for (let r = 0; r < rows.length; r++) {
            let c = 0
            for (const ch of rows[r]) {
                if (/\\d/.test(ch)) { c += parseInt(ch); continue }
                const lower = ch.toLowerCase()
                const tpl = pieceTemplates[lower]; if (!tpl) { c++; continue }
                const isWhite = ch === ch.toUpperCase()
                const piece = tpl.clone(true)
                piece.traverse(o => { if (!o.isMesh) return; o.castShadow = true; o.receiveShadow = true; o.material = isWhite ? whiteMat : blackMat })
                piece.position.set(c - BOARD_HALF, 0.05, r - BOARD_HALF)
                if (!isWhite) piece.rotation.y = Math.PI
                scene.add(piece); livePieces.push(piece); c++
            }
        }
    },
    setCamera(px,py,pz,tx,ty,tz,ux,uy,uz,fov,aspect) {
        camera.position.set(px,py,pz); camera.up.set(ux,uy,uz); camera.lookAt(tx,ty,tz)
        camera.fov = fov; camera.aspect = aspect; camera.updateProjectionMatrix()
    },
    setSelectedSquare(row, col) {
        if (row === null || col === null || row === undefined || col === undefined) { selectionMesh.visible = false; return }
        selectionMesh.position.set(col - BOARD_HALF, 0.03, row - BOARD_HALF); selectionMesh.visible = true
    },
    resize(w, h) { if (!renderer) return; renderer.setSize(w, h, false); camera.aspect = w / h; camera.updateProjectionMatrix() },
    dispose() { if (renderer) { renderer.dispose(); renderer = null } scene = null; camera = null }
}

async function loadGlb() {
    const loader = new GLTFLoader()
    const paths = ['/app/src/commonMain/composeResources/files/models/chess.glb', './chess.glb']
    let gltf = null
    for (const p of paths) { try { gltf = await new Promise((res, rej) => loader.load(p, res, undefined, rej)); break } catch(e){} }
    if (!gltf) { console.error('[chess3d] chess.glb not found'); return }
    const kindByName = { king:'k', queen:'q', rook:'r', bishop:'b', knight:'n', pawn:'p' }
    gltf.scene.traverse(obj => { const name = obj.name?.toLowerCase(); if (name && kindByName[name]) pieceTemplates[kindByName[name]] = obj })
}
function animate() { requestAnimationFrame(animate); if (renderer && scene && camera) renderer.render(scene, camera) }
"""
