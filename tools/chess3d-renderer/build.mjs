// Regenerates BOTH 3D-renderer artifacts from the single source of truth:
//
//   tools/chess3d-renderer/chess3d-renderer.js
//
//   1. iosApp/iosApp/Resources/chess3d-bundle.js  — esbuild IIFE bundle (three + GLTFLoader +
//      RoomEnvironment + the renderer) loaded by chess3d-host.html inside the iOS WKWebView.
//   2. app/src/wasmJsMain/kotlin/.../board3d/ThreeJsChessRenderer.kt — the CHESS3D_RENDERER_JS
//      raw string, inlined by the wasm target as a module <script> and resolved via index.html's
//      static import map.
//
// Run after editing chess3d-renderer.js:
//   npm install && node build.mjs
//
// The JS source is written to be Kotlin-raw-string safe: it contains no `$` (no template literals)
// and no triple-double-quote sequences, so it can be embedded verbatim inside """...""".

import * as esbuild from 'esbuild'
import { readFileSync, writeFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, resolve } from 'node:path'

const __dirname = dirname(fileURLToPath(import.meta.url))
const root = resolve(__dirname, '..', '..')
const sourcePath = resolve(__dirname, 'chess3d-renderer.js')
const sourceJs = readFileSync(sourcePath, 'utf8')

// --- 0. Regenerate the papermill equirect .hdr files (skybox + ibl) for web + iOS from the
//        Filament KTX cubemaps desktop/Android already ship. Runs first so the bundle/string see
//        the latest env assets. Pure JS, no deps. ---
await import('./build-papermill-hdr.mjs')

// --- 1. iOS bundle (esbuild, unminified to match the existing committed artifact) ---
const iosBundlePath = resolve(root, 'iosApp', 'iosApp', 'Resources', 'chess3d-bundle.js')
await esbuild.build({
  entryPoints: [sourcePath],
  bundle: true,
  format: 'iife',           // exposes window.chess3d as a plain <script>
  target: 'safari15',       // WKWebView on iOS 15+
  legalComments: 'inline',  // keep the three.js license block in-bundle
  minify: false,
  outfile: iosBundlePath,
})
console.log('[chess3d] wrote', iosBundlePath)

// --- 2. wasm Kotlin raw string (splice into ThreeJsChessRenderer.kt) ---
const kotlinPath = resolve(
  root,
  'app', 'src', 'wasmJsMain', 'kotlin', 'com', 'example', 'myapplication', 'board3d',
  'ThreeJsChessRenderer.kt',
)
const kotlin = readFileSync(kotlinPath, 'utf8')

// Safety checks: the source must survive inside a Kotlin """...""" raw string verbatim.
if (sourceJs.includes('$')) {
  throw new Error('chess3d-renderer.js contains "$" — would trigger Kotlin string interpolation. Rewrite without template literals / "$".')
}
if (sourceJs.includes('"""')) {
  throw new Error('chess3d-renderer.js contains a triple-double-quote sequence — cannot embed in a Kotlin raw string.')
}

const marker = 'private const val CHESS3D_RENDERER_JS'
const markerIdx = kotlin.indexOf(marker)
if (markerIdx === -1) {
  throw new Error('Could not find "private const val CHESS3D_RENDERER_JS" in ' + kotlinPath)
}
const opening = kotlin.indexOf('"""', markerIdx)
if (opening === -1) throw new Error('Could not find opening """ after CHESS3D_RENDERER_JS marker.')
const closing = kotlin.indexOf('"""', opening + 3)
if (closing === -1) throw new Error('Could not find closing """ for CHESS3D_RENDERER_JS.')

const replaced =
  kotlin.slice(0, opening + 3) +
  '\n' + sourceJs + '\n' +
  kotlin.slice(closing)

writeFileSync(kotlinPath, replaced, 'utf8')
console.log('[chess3d] rewrote CHESS3D_RENDERER_JS in', kotlinPath)
