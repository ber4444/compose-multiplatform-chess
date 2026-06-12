# Issue #32 — 3D UI, Milestone 4: Wasm (WebGPU)

Prereqs: [issue-32-3d-ui-overview.md](issue-32-3d-ui-overview.md) and merged [M1](issue-32-3d-ui-m1-foundation.md) (abstraction, `wasmJsTest` toggle tests, `chess.glb`). Suggested branch: `issue-32-3d-m4`.

Goal: a WebGPU backend for the web target, injected from the wasm `Main.kt`. This milestone exercises the production fallback path for real: any browser without WebGPU gets the 2D board with the unavailable message.

## Go/no-go mini-spike (timebox: 1 day)

Kotlin-version klib risk is **highest here** — wasm klibs are the least forward-compatible. Question: does Materia's WebGPU module compile as a klib consumable from this repo's Kotlin 2.3.20 `wasmJsMain` via the composite build (`:app:wasmJsBrowserDistribution` succeeds), and can its renderer bind to a **caller-supplied** `HTMLCanvasElement`?

No-go → maintain a fork branch of Materia bumped to Kotlin 2.3.20 (patch branch, rebased per Materia release), and record that maintenance cost in the verdict below.

## Canvas strategy: overlay

Compose-on-wasm renders the entire app into its own canvas inside the `ComposeTarget` element, so the 3D surface must be a **second, absolutely positioned WebGPU canvas** overlaid on the board slot:

- The overlay canvas gets `pointer-events: none` — Compose keeps receiving all input; the M1 host already forwards drags/taps/zoom from Compose's `pointerInput` into `Board3DInput`.
- The overlay is rect-synced to the composed `Board3D` slot via `Modifier.onGloballyPositioned` → `boundsInWindow()`, divided by `window.devicePixelRatio`, written to the canvas's CSS `left/top/width/height`.
- While any Compose dialog is open (promotion / game over / draw offer), the overlay must hide, since Compose dialogs draw inside the Compose canvas **below** the overlay. Implement by toggling `canvas.style.visibility` from a `LaunchedEffect` keyed on `gameState.pendingPromotion != null || gameState.winState != WinState.NONE || gameState.drawOffer == Set.BLACK`.
- Rejected alternative (record, don't relitigate): making the Compose canvas transparent over the board and putting the WebGPU canvas underneath — Compose-wasm's canvas alpha behavior is not guaranteed across versions.

## Files

All in `app/src/wasmJsMain/kotlin/com/example/myapplication/board3d/` unless noted:

- **`WasmBoard3D.kt`** —
  - `class WasmChess3DSurface(val canvas: HTMLCanvasElement, override val widthPx: Int, override val heightPx: Int) : Chess3DSurface`
  - `@Composable fun WasmBoard3DSurface(renderer: Chess3DBoardRenderer, modifier: Modifier)`: `DisposableEffect` creates `<canvas id="board3d-overlay">` appended to `document.body` (`position: absolute; pointer-events: none`), attaches the renderer, removes the canvas and detaches on dispose; `onGloballyPositioned` drives the rect sync; dialog-state visibility toggle as above.
  - `internal fun overlayCssRect(boundsInWindow: Rect, devicePixelRatio: Double): CssRect` — the rect-sync math extracted as a pure function for unit testing.
  - `fun wasmBoard3DSupport(): Board3DSupport` — factory: `if (navigator.gpu == null) null else runCatching { WebGpuChessRenderer(Res.readBytes("files/models/chess.glb")) }.getOrNull()`.
- **`WebGpuChessRenderer.kt`** — Materia WebGPU backend bound to the overlay canvas; same structure as the other renderers (scene from `Board3DSceneMapper` + `ChessSetMeshNames`, render on demand; on wasm "render thread" = rAF-driven single render when dirty).
- **`Main.kt`** (modify) — inject `wasmBoard3DSupport()` into `ChessApp`.
- **`app/build.gradle.kts`** — wasmJsMain dependency on the substituted Materia modules (or the fork branch).

## Tests

Unit tests (`app/src/wasmJsTest/kotlin/com/example/myapplication/board3d/`):

- **`OverlayCssRectTest`** — `overlayCssRect` for devicePixelRatio 1.0, 2.0, fractional; zero-size bounds.

UI tests (`app/src/wasmJsTest/kotlin/com/example/myapplication/`):

- M1's fake-based `Board3DToggleUiTest` keeps passing untouched.
- Add `webgpu unavailable falls back` — uses the **real** `wasmBoard3DSupport()` factory. Headless Chrome in CI has no WebGPU by default, so this test asserts the production fallback end-to-end: toggle on → `board_3d_unavailable` appears, toggle reverts, `chess_board` interactive. (On a developer machine with WebGPU it asserts the opposite branch: `board_3d` exists — branch on `navigator.gpu` presence inside the test.)

## CI

- Existing `:app:check` runs the wasm tests; `:app:wasmJsBrowserDistribution` already in the matrix.
- Optional render smoke job: run the wasm tests with Chrome flags `--enable-unsafe-webgpu --use-webgpu-adapter=swiftshader` (Karma config), `continue-on-error: true`.

## Definition of done

- In a WebGPU-capable browser the toggle renders the 3D board, rect-synced under resize/scroll, hidden behind dialogs; without WebGPU the fallback message shows and the game is unaffected.
- `./gradlew :app:check :app:wasmJsBrowserDistribution` green.
- Full CI matrix builds (overview "Execution rules").

## Spike result

_To be appended: wasm klib verdict, canvas-binding verdict, fork-branch decision._
