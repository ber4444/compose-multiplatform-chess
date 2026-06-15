# Issue #32 — 3D UI, Milestone 4: Wasm (WebGPU)

> **Status update — engine decision RESOLVED by [M6](issue-32-3d-ui-m6-wgpu4k.md).** The deferred
> spike below is answered: the renderer is **wgpu4k** (`io.ygdrasil:wgpu4k-toolkit`) — this milestone's
> own no-go **option (b)** ("a native WebGPU renderer in Kotlin"). Wasm is now delivered as **Phase 2 of
> the unified wgpu4k backend**, i.e. the *shared* commonMain renderer compiled to `wasmJs`, **not** a
> wasm-specific Materia/Three.js renderer.
> - **Still valid here (reused as-is):** the canvas-overlay strategy, the `navigator.gpu` fallback,
>   the `WasmBoard3D.kt` / `overlayCssRect` / test layout, and the DoD.
> - **Superseded:** all Materia-specific parts (consumption recipe, Gradle 8.13 publish, fork branch,
>   the "publish Materia to a Maven repo first" CI step) and the wasm-only `WebGpuChessRenderer`.
> - **vkChess-fidelity is in scope here too:** wasm reuses the desktop WGSL (skybox + PBR+IBL) and the
>   env-cubemap approach from [M6](issue-32-3d-ui-m6-wgpu4k.md). Both are WebGPU, so the *renderer* is
>   near-identical; only the **canvas surface** and the **asset pipeline** differ (see
>   "vkChess-fidelity & desktop reuse" below).

Prereqs: [issue-32-3d-ui-overview.md](issue-32-3d-ui-overview.md) and merged [M1](issue-32-3d-ui-m1-foundation.md) (abstraction, `wasmJsTest` toggle tests, `chess.glb`). Suggested branch: `issue-32-3d-m4`.

Goal: a WebGPU backend for the web target, injected from the wasm `Main.kt`. This milestone exercises the production fallback path for real: any browser without WebGPU gets the 2D board with the unavailable message.

> **Engine context (post-M1 spike).** This is where Materia is *most* attractive — it would save hand-writing a WebGPU renderer, and wasm renders to a real `<canvas>` (no offscreen-readback requirement, so the desktop killer doesn't apply). But it's also the **highest-risk consumption**: wasm klibs are far less forward-compatible than the JVM klibs that passed in M1. Composite build is not an option (Gradle 9.3.1); Materia must be published from its own Gradle 8.13 build (M1 "Materia consumption recipe"). The alternative is a native WebGPU renderer (via `kotlinx-browser` / external WebGPU bindings) behind the same interface.

## Go/no-go mini-spike (timebox: 1 day)

Kotlin-version klib risk is **highest here** — wasm klibs are the least forward-compatible, and M1's clean JVM result does **not** carry over. Question: does Materia's WebGPU module (built with its own Gradle 8.13, published to a Maven repo per the M1 recipe — **not** `includeBuild`) compile as a klib consumable from this repo's Kotlin 2.3.20 `wasmJsMain` (`:app:wasmJsBrowserDistribution` succeeds), and can its renderer bind to a **caller-supplied** `HTMLCanvasElement`?

No-go → three options, in rough preference order: (a) **a Three.js / Babylon.js JS-interop bridge** behind the `Chess3DBoardRenderer` interface — this is the issue's own wasm sub-hint and its big advantage is that it **sidesteps the Kotlin-klib-version risk entirely** (the renderer lives in JS, called via `external`/`@JsModule`), which is exactly the risk that makes Materia-on-wasm fragile; (b) a native WebGPU renderer in Kotlin via `kotlinx-browser` / external WebGPU bindings; (c) maintain a fork branch of Materia bumped to Kotlin 2.3.20 (rebased per release). Record the choice and its maintenance cost in the verdict below.

## Canvas strategy: overlay

Compose-on-wasm renders the entire app into its own canvas inside the `ComposeTarget` element, so the 3D surface must be a **second, absolutely positioned WebGPU canvas** overlaid on the board slot:

- The overlay canvas gets `pointer-events: none` — Compose keeps receiving all input; the M1 host already forwards drags/taps/zoom from Compose's `pointerInput` into `Board3DInput`.
- The overlay is rect-synced to the composed `Board3D` slot via `Modifier.onGloballyPositioned` → `boundsInWindow()`, divided by `window.devicePixelRatio`, written to the canvas's CSS `left/top/width/height`.
- While any Compose dialog is open (promotion / game over / draw offer), the overlay must hide, since Compose dialogs draw inside the Compose canvas **below** the overlay. Implement by toggling `canvas.style.visibility` from a `LaunchedEffect` keyed on `gameState.pendingPromotion != null || gameState.winState != WinState.NONE || gameState.drawOffer == Set.BLACK`.
- Rejected alternative (record, don't relitigate): making the Compose canvas transparent over the board and putting the WebGPU canvas underneath — Compose-wasm's canvas alpha behavior is not guaranteed across versions.

## vkChess-fidelity & desktop reuse

The wasm renderer is the desktop wgpu renderer minus the JVM-only bits — **both are wgpu4k + WGSL**, so
the *rendering* is essentially identical and should be shared, not rewritten:

**Reuse from desktop (M6) verbatim:**
- The **WGSL** — sky shader + PBR+IBL fragment (`WgpuShaders.kt`). Move these into a shared source set
  (commonMain, or a `wgpuMain` intermediate shared by desktop+wasm) so both targets compile the same
  strings. Mind the WGSL gotchas in M6 (no Y-flip, `cullMode = None`, std140 `invViewProj` offset,
  ASCII-only comments, `includeGround = false`).
- The renderer structure: env cubemap + skybox pass + Cook-Torrance/IBL PBR + Uncharted2 tonemap, plus
  the shared scene/geometry (`Board3DSceneMapper`, `ChessSceneGeometry`, `OrbitCameraController`).

**Wasm-specific (the actual M4 work):**
- **Surface:** render straight to the overlay `<canvas>`'s WebGPU surface — **no offscreen readback**
  (desktop reads back only because Compose Desktop can't composite a foreign surface; the browser
  composites the canvas natively). Configure a swapchain on the canvas and present per frame.
- **Asset pipeline must be wasm-compatible** (desktop uses JVM-only libs):
  - `KtxLoader` uses LWJGL `MemoryUtil`/`ByteBuffer` → write a pure-Kotlin/common KTX parser (or move a
    JVM-free one to commonMain) for `papermill_hdr16f_cube.ktx`.
  - `GltfChessMeshes`/`GltfChessTextures` use `de.javagl:jgltf` + `javax.imageio` (JVM only) → use a wasm
    glTF path (e.g. wgpu4k-scenes' commonMain `GLTF2` helper) + browser/Kotlin image decode.
  - Bundle the env + `chess.glb` as commonMain compose resources so `Res.readBytes` works on wasm.
- **No FFM / JDK-toolchain concerns** — wasm uses the browser's WebGPU directly.

Net: if the WGSL + scene code is shared, F1/F2 come almost for free on wasm; the real effort is the canvas
surface plus the KTX/glTF/image loaders for `wasmJs`.

## Files

All in `app/src/wasmJsMain/kotlin/com/example/myapplication/board3d/` unless noted:

- **`WasmBoard3D.kt`** —
  - `class WasmChess3DSurface(val canvas: HTMLCanvasElement, override val widthPx: Int, override val heightPx: Int) : Chess3DSurface`
  - `@Composable fun WasmBoard3DSurface(renderer: Chess3DBoardRenderer, modifier: Modifier)`: `DisposableEffect` creates `<canvas id="board3d-overlay">` appended to `document.body` (`position: absolute; pointer-events: none`), attaches the renderer, removes the canvas and detaches on dispose; `onGloballyPositioned` drives the rect sync; dialog-state visibility toggle as above.
  - `internal fun overlayCssRect(boundsInWindow: Rect, devicePixelRatio: Double): CssRect` — the rect-sync math extracted as a pure function for unit testing.
  - `fun wasmBoard3DSupport(): Board3DSupport` — factory: `if (navigator.gpu == null) null else runCatching { WebGpuChessRenderer(Res.readBytes("files/models/chess.glb")) }.getOrNull()`.
- **`WebGpuChessRenderer.kt`** — the **wgpu4k** WebGPU backend bound to the overlay canvas, reusing the
  shared WGSL + env/PBR from M6; same structure as the other renderers (scene from `Board3DSceneMapper`,
  render on demand — on wasm the "render thread" is an rAF-driven render when dirty). Renders to the canvas
  surface (no readback).
- **`Main.kt`** (modify) — inject `wasmBoard3DSupport()` into `ChessApp`.
- **`app/build.gradle.kts`** — add `io.ygdrasil:wgpu4k-toolkit` to `wasmJsMain` (the GitLab repo is already
  in `settings.gradle.kts` from M6); confirm the wasm variant resolves and `:app:wasmJsBrowserDistribution`
  compiles. No Materia, no `includeBuild`.

## Tests

Unit tests (`app/src/wasmJsTest/kotlin/com/example/myapplication/board3d/`):

- **`OverlayCssRectTest`** — `overlayCssRect` for devicePixelRatio 1.0, 2.0, fractional; zero-size bounds.

UI tests (`app/src/wasmJsTest/kotlin/com/example/myapplication/`):

- M1's fake-based `Board3DToggleUiTest` keeps passing untouched.
- Add `webgpu unavailable falls back` — uses the **real** `wasmBoard3DSupport()` factory. Headless Chrome in CI has no WebGPU by default, so this test asserts the production fallback end-to-end: toggle on → `board_3d_unavailable` appears, toggle reverts, `chess_board` interactive. (On a developer machine with WebGPU it asserts the opposite branch: `board_3d` exists — branch on `navigator.gpu` presence inside the test.)

## CI

- Existing `:app:check` runs the wasm tests; `:app:wasmJsBrowserDistribution` already in the matrix.
- No extra publish step: `wgpu4k-toolkit` resolves from the GitLab Maven repo already configured in `settings.gradle.kts` (M6).
- Optional render smoke job: run the wasm tests with Chrome flags `--enable-unsafe-webgpu --use-webgpu-adapter=swiftshader` (Karma config), `continue-on-error: true`.

## Definition of done

- In a WebGPU-capable browser the toggle renders the 3D board, rect-synced under resize/scroll, hidden behind dialogs; without WebGPU the fallback message shows and the game is unaffected.
- `./gradlew :app:check :app:wasmJsBrowserDistribution` green.
- Full CI matrix builds (overview "Execution rules").

## Spike result

**Resolved by [M6](issue-32-3d-ui-m6-wgpu4k.md).** Engine = **wgpu4k** (option (b)). The Kotlin-klib
forward-compat risk that this milestone flagged as "highest here" is retired in principle — wgpu4k
tracks Kotlin 2.3.21 (repo is 2.3.20) and resolved + compiled cleanly on desktop. **Still open (M6
Phase 2):** confirm the `wasmJs` *variant* resolves/compiles and binds to a caller-supplied
`HTMLCanvasElement` via the overlay strategy above. Fork-branch question is moot (no Materia).
