# Issue #32 — 3D UI: Overview and Architecture

Implementation plan for [issue #32](https://github.com/ber4444/compose-multiplatform-chess/issues/32): adopt the 3D look of [vkChess](https://github.com/jpbruyere/vkChess) (C++/Vulkan PBR chess) behind a `Chess3DBoardRenderer` abstraction. Initially target Vulkan + MoltenVK; WebGPU later. The 2D board stays the canonical interaction model; 3D is a view layer mirroring the same FEN/game state.

> **Committed direction (2026-06-14): unify all 3D onto a single [wgpu4k](https://github.com/wgpu4k/wgpu4k)
> WebGPU backend + WGSL.** This **supersedes** the "one engine isn't feasible → Materia-or-native per
> platform" conclusion in *Engine choice* below. The `Chess3DBoardRenderer` abstraction, the scene
> layer, and Decisions A/B/C are unchanged and are exactly what makes the swap possible; Decision D
> (desktop = LWJGL) is superseded by wgpu4k's headless `TextureRenderingContext` (readback per Decision
> C is kept). Desktop and web now ship through this shared `wgpuMain` WebGPU path. The mobile native
> backends (M2 iOS/SceneKit, M3 Android/SceneView/Filament) **stay until each wgpu4k replacement proves
> parity**, and wgpu4k's Android/iOS backends must leave WIP first. The per-platform engine-decision prose
> below is retained as historical rationale.

This document holds the decisions and the shared commonMain API. Each milestone has its own execution-ready plan doc:

| Milestone | Doc | Scope |
|---|---|---|
| M1 | [issue-32-3d-ui-m1-foundation.md](issue-32-3d-ui-m1-foundation.md) | Materia spike (**done → original desktop backend used LWJGL headless Vulkan**), commonMain abstraction + scene mapping, 2D/3D toggle with graceful fallback on all platforms, desktop JVM backend, assets, CI |
| M2 | [issue-32-3d-ui-m2-apple.md](issue-32-3d-ui-m2-apple.md) | iOS backend (MoltenVK via Materia, or Metal-direct — mini-spike decides) |
| M3 | [issue-32-3d-ui-m3-android.md](issue-32-3d-ui-m3-android.md) | Android backend (landed through SceneView/Filament after Materia/raw Vulkan were rejected) |
| M4 | [issue-32-3d-ui-m4-wasm.md](issue-32-3d-ui-m4-wasm.md) | Wasm backend (landed through the shared wgpu4k WebGPU renderer and overlay canvas) |
| M5 | [issue-32-3d-ui-m5-interaction-animation.md](issue-32-3d-ui-m5-interaction-animation.md) | 3D tap-to-move via ray picking, smooth piece animation, camera polish (backend-agnostic) |
| **M6** | [issue-32-3d-ui-m6-wgpu4k.md](issue-32-3d-ui-m6-wgpu4k.md) | **Committed unification: shared wgpu4k (WebGPU) + WGSL backend.** Desktop and web are implemented with papermill skybox plus PBR/IBL; Android/iOS wait for mature wgpu4k native targets. |
| **M7** | [issue-32-3d-ui-m7-apple-android-fidelity.md](issue-32-3d-ui-m7-apple-android-fidelity.md) | **vkChess-fidelity for iOS (SceneKit) + Android (Filament)** via their native IBL/skybox — interim until wgpu4k Apple/Android leave WIP. |

> Current implementation: desktop + web use wgpu4k/WebGPU; iOS uses SceneKit; Android uses SceneView
> over Filament. M5's interaction layer is above the renderer interface and is shared across all four
> active backends.

## Scrutiny of the issue's hints

The issue offers hints, not requirements. Each was evaluated against the codebase and the M1 spike; where reality diverged we followed reality. Summary of the verdicts (rationale lives in the sections below):

**Hint 1 — "Unify behind a single `Chess3DBoardRenderer` abstraction … provide expect/actuals per platform … the actual implementation can wrap Materia."**
- ✅ **Adopt the single abstraction.** The idea is sound and is the load-bearing decision of the whole feature.
- ❌ **Reject expect/actual; use a plain interface + factory injection** (see Decision A). expect/actual is compile-time only and cannot express the two things we actually need: runtime fallback (inject `null` → hide the toggle) and runtime engine selection (real backend vs `FakeChess3DRenderer` in tests). The repo also has zero expect/actual today and a manual source-set hierarchy that makes it the most error-prone wiring.
- ⚠️ **"Can wrap Materia" is only conditionally true.** The spike showed Materia *cannot* be wrapped on desktop (no offscreen path). The abstraction is what lets each platform wrap a different backend — which is the real value of hint 1, more than "one engine."
- 🔬 **The sketched interface itself, method by method:**
  - `attach(surface)` / `detach()` + a commonMain `Chess3DSurface` — kept. But the sketch is an *incomplete lifecycle*: it has no construction story and no resource release. We add (a) a `suspend Chess3DRendererFactory.create(): Renderer?` — `suspend` so the backend can load the glTF asset via `Res.readBytes`, nullable so an unsupported platform/init-failure falls back to 2D; and (b) `dispose()` — without it the renderer leaks GPU resources on toggle-off, since `detach()` only severs the surface.
  - `updatePosition(fen: String)` — kept the **FEN** boundary rather than passing the typed `Board3DScene`. Trade-off scrutinized: a stringly-typed boundary means each backend calls `Board3DSceneMapper.fromFen` itself (mild duplication). We keep FEN anyway because the mapper is shared commonMain code, mapping runs *once per position* (not per frame), and a string boundary keeps the renderer contract trivially testable (literal FENs) and decoupled from the scene types' evolution. FEN stays the single source of truth.
  - `onUserInteraction(event)` — kept, **but deliberately narrowed to visual/camera state only.** The sketch implies *all* input flows through the renderer; that would be wrong here, because game-affecting taps (selection/move) must stay in Compose (hint 3). So in our design `onUserInteraction` carries only camera events (`SetCamera`, computed by the host's `OrbitCameraController`); a 3D tap that should move a piece is ray-picked in **common** code and routed to the *same* `updateSelected`/`playerMove` lambdas the 2D board uses (M5) — it never enters the renderer. The renderer thus only ever receives *visual* state, exactly as hint 3 asks.

**Hint 2 — "Prefer one engine where feasible (Materia for Vulkan + WebGPU + Metal); if not, Vulkan-native / MoltenVK / WebGPU per platform."**
- ❌ **The "one engine" ideal is not feasible** — spike-confirmed. We land on the issue's *own* fallback: hand-written LWJGL Vulkan on desktop, and Materia-or-native per platform elsewhere (each milestone re-validates). So we explicitly do **not** prefer one engine; we prefer the abstraction that lets each platform choose. See "Engine choice" and "Open decision for M2–M4."
- 💡 **Elevate the "Three.js/BabylonJS bridge for wasm" sub-hint.** A JS-interop bridge sidesteps the Kotlin-klib-version risk entirely (M4's biggest risk) and is a serious M4 alternative to a Materia/WebGPU klib — recorded in the M4 doc.
- 🔬 **The hint's platform groupings don't map to our source sets.** It groups "Vulkan-native for Linux/Windows/Android" and "MoltenVK for macOS/iOS." In KMP terms our split is different: desktop (Linux/Windows/**macOS**) is one JVM target where LWJGL's `lwjgl-vulkan` covers all three — macOS via the bundled MoltenVK — so macOS desktop is grouped with Linux/Windows, **not** with iOS; Android and iOS are each their own native target. Treat the hint's grouping as "which API per OS," not "which code module."

**Hint 3 — "Host the 3D surface in a Composable; keep FEN/selection in Compose; render visual state in the engine. Keep the 2D board as the canonical interaction model initially; 3D as a view layer."**
- ✅ **Adopt the Compose-integration pattern** (Decision B + the `Board3D` host): game state stays in Compose, the renderer receives only FEN.
- ⚠️ **Deliberate divergence on "2D stays canonical/visible."** The product decision is to **hide** the 2D board in 3D mode (swap, not co-display). That made M5's 3D tap-to-move a requirement, and it has now landed: 3D taps are ray-picked in common code and routed to the same selection/move callbacks as the 2D board. 2D remains the canonical interaction model *when shown*; it just isn't shown in 3D mode.

Meta-point: the issue names vkChess for the look and Materia for the engine, but both proved partial — we take only vkChess's **asset** (its C++ engine isn't reusable here) and Materia is rejected on desktop. "Adopt the UI from vkChess" therefore means adopt its visual style/asset, not its code.

## Engine choice

The original candidate was **[Materia](https://github.com/codeyousef/Materia)** — a KMP 3D library (Three.js-style scene graph, glTF 2.0 loader; backends: WebGPU on JS/wasm, Vulkan via LWJGL 3.3.6 on JVM, Vulkan on Android API 24+, MoltenVK on Apple; Apache-2.0). The M1 Phase A spike has now **run** (2026-06-12; full per-criterion verdict in the [M1 doc's "Spike result"](issue-32-3d-ui-m1-foundation.md#spike-result)).

### Post-spike status (M1–M3 backend rationale; superseded as the *direction* by the wgpu4k commitment above)

- **Desktop (M1): LWJGL headless Vulkan — Materia rejected.** Materia's JVM backend is structurally a *windowed-swapchain* renderer with **no offscreen render-to-texture path**, which is exactly what the desktop interop needs (Decision C: render offscreen → CPU readback → Compose `ImageBitmap`, off the UI thread). The spike confirmed `RendererFactory` rejects a windowless surface, and GLFW demands the macOS main thread. So desktop uses the hand-written **LWJGL headless Vulkan** pipeline (Decision D), glTF via `de.javagl:jgltf-model`. Positive signal: LWJGL 3.3.6 + MoltenVK detected the GPU on the spike machine, so this pipeline is viable on the same hardware.
- **What the spike *did* validate about Materia (still useful if it's used elsewhere):** Kotlin 2.3.20 reads Materia's 2.2.20-built **JVM** artifacts with zero metadata errors; its glTF loader parses `chess.glb` in ~86 ms with all six piece nodes as PBR `MeshStandardMaterial`.
- **Materia is NOT vendored via composite build.** The plan originally assumed `includeBuild("third_party/materia")`. **That does not work**: Materia's `build.gradle.kts` uses Gradle's removed `javaexec{}` script API, but this repo runs Gradle 9.3.1 (AGP 9.1.1 requires it), so the included build won't even configure. If Materia is ever used, it must be **built with its own Gradle 8.13 and published to a Maven repo** (mavenLocal for spikes; a checked-in flat Maven dir or an internal repo for real use). Building it also needs a WGSL compiler (Tint/naga) unless the `compileShaders` task is disabled (SPIR-V is pre-committed), and consumers must add **LWJGL + per-OS native classifiers explicitly** (Materia exposes them runtime-only). See the M1 Spike result for the exact recipe.
- **Materia (v0.4.1.0) is alpha and not on Maven Central.** Built with **Kotlin 2.2.20** vs this repo's 2.3.20 — JVM klib consumption is proven, but **wasm klibs are far less forward-compatible**, so M4 must re-validate consumption for the `wasmJs` target specifically before relying on it.

### Open decision for M2–M4 — RESOLVED (wgpu4k unification, 2026-06-14)

**Resolved:** the engine is **wgpu4k** across all targets (see the committed-direction banner and
[M6](issue-32-3d-ui-m6-wgpu4k.md)). The "two renderer codebases" worry below is accepted *transitionally*
— the native backends remain only until each wgpu4k replacement proves parity. The original analysis is
kept for context:

The spike killer (no offscreen path) is **desktop-specific**: M2 (iOS), M3 (Android), and M4 (wasm) render to a *real* surface (`UIKitView`/`SurfaceView`/`<canvas>`), which is what Materia's swapchain renderer wants — so Materia is *not* ruled out there on the same grounds. But choosing it now means **two renderer codebases** (hand-written LWJGL on desktop + Materia on mobile/wasm) plus the publish-to-Maven plumbing and per-target Kotlin-version re-validation. Each of those milestones therefore keeps its own go/no-go mini-spike, and may instead extend a platform-native renderer behind the same `Chess3DBoardRenderer` interface. The `Chess3DBoardRenderer` abstraction isolates the rest of the app from whichever way each milestone lands.

## Architecture decisions

### A. Plain interface + factory injection, NOT expect/actual

The issue sketch mentions expect/actual, but this repo has zero expect/actual declarations today. The established pattern is `ChessEngine`: a plain commonMain interface with platform implementations injected at entry points (`MainActivity`, desktop `Main.kt`, wasm `Main.kt`, `MainViewController(engine:)`). The renderer follows the same pattern, because:

1. **Graceful fallback is a hard requirement.** A platform with no backend injects `null` (exactly like a missing Stockfish binary) and the UI hides the 3D toggle. With expect/actual, M1 would force stub actuals in every platform source set immediately, and "unavailable" becomes a runtime sentinel anyway.
2. **Runtime selection.** Desktop chooses Materia vs the LWJGL fallback at runtime; tests choose `FakeChess3DRenderer`. expect/actual is compile-time only.
3. **Testability.** The attach/detach/updatePosition lifecycle contract is tested once in commonTest against a fake, with no platform machinery.
4. The repo's **manual source-set hierarchy** (`kotlin.mpp.applyDefaultHierarchyTemplate=false`) makes expect/actual wiring the most error-prone part of the build; interfaces avoid it.

The only genuinely platform-specific Compose piece — the composable that creates the native surface — is injected as a lambda inside `Board3DSupport`. No platform types appear in commonMain signatures.

### B. `updatePosition(fen)` only through M4; animation deferred to M5

`GameViewModel` flips `gameState` to the post-move position before the 2D animation plays, so the 3D view snapping to each new FEN is consistent and simple: `GameScreen` derives `FenConverter.gameStateToFen(gameState)` and the host pushes it on change. Passing `PieceAnimationState` (a 2D UI type) into the renderer would couple every backend to 2D tweening before it can ship. M5 adds animation without touching `animState`: a pure `Board3DSceneDiffer` diffs consecutive scenes into a `Board3DTransition`, and the interface gains a default-arg overload so earlier backends keep compiling.

### C. Desktop interop = offscreen render + per-frame `ImageBitmap`

The desktop renderer draws into an offscreen GPU image; frames are read back and drawn with a plain Compose `Image`. Not SwingPanel/AWT-Vulkan, because:

- **Dialog correctness.** `GameScreen` shows Compose `Dialog`s (promotion, game over, draw offer) above the board. A bitmap is plain Compose content, so z-order, the `verticalScroll` column, and `testTag` semantics all work normally.
- **macOS threading.** GLFW windows must be created on the process's first thread, which AWT/Compose owns (spike-confirmed: GLFW off the main thread throws, and that is also why a windowed engine like Materia can't drive this). Headless Vulkan (no swapchain) renders offscreen from any thread.
- **CI.** GitHub's ubuntu runners have no GPU; offscreen Vulkan runs on lavapipe (`mesa-vulkan-drivers`). LWJGL's `lwjgl-vulkan` macOS natives bundle MoltenVK, so macos runners work too.
- **Cost is fine.** The board renders on demand (scene or camera change), not in a hot loop; a 700×700 RGBA readback is ~2 MB.

### D. Desktop engine = LWJGL headless Vulkan minimal pipeline (spike-confirmed choice)

This was the documented fallback; the M1 spike's rejection of Materia for desktop makes it **the** desktop backend. Pipeline: instance → device → one render pass → one graphics pipeline → vertex/index/uniform buffers → render to image → `vkCmdCopyImageToBuffer` → map. Offscreen rendering removes swapchain/WSI/resize complexity; roughly 1.5–2.5k lines, mechanical from the LWJGL Vulkan samples. glTF parsing via `de.javagl:jgltf-model` (MIT, Maven Central). Plain OpenGL would be less code but is deprecated on macOS and contradicts the issue's explicit Vulkan-first direction. vkChess's C++ code is **not** vendored — only its asset (`data/chess.gltf`); a JNI + CMake bridge to its `vke`/`vkvg` stack is rejected.

## commonMain API

Package `com.example.myapplication.board3d`, under `app/src/commonMain/kotlin/com/example/myapplication/board3d/`.

### `Chess3DBoardRenderer.kt`

```kotlin
/** Contract (KDoc'd on the interface, enforced by FakeChess3DRenderer tests):
 *  - All methods are called from the UI thread; implementations marshal to their own render thread.
 *  - attach() while already attached detaches the previous surface first.
 *  - updatePosition() before attach() stores the FEN; it is applied on attach.
 *  - detach() is idempotent and returns quickly (async GPU teardown allowed,
 *    but the surface must not be touched after detach() returns).
 *  - dispose() releases GPU resources; the renderer is unusable afterwards. */
interface Chess3DBoardRenderer {
    fun attach(surface: Chess3DSurface)
    fun detach()
    fun updatePosition(fen: String)
    fun updatePosition(fen: String, transition: Board3DTransition?) = updatePosition(fen)
    fun onUserInteraction(event: Board3DInput)
    fun dispose()
    fun setSelectedSquare(square: BoardSquare?) {}
}

/** Marker for a platform drawing target. Platform impls wrap native handles
 *  (frame sink / SurfaceHolder / CAMetalLayer / HTMLCanvasElement); renderers downcast. */
interface Chess3DSurface {
    val widthPx: Int
    val heightPx: Int
}

/** Returns null when 3D is unsupported or init fails -> UI falls back to 2D.
 *  suspend so implementations can load the glTF asset via Res.readBytes(). */
fun interface Chess3DRendererFactory {
    suspend fun create(): Chess3DBoardRenderer?
}

/** Injected at platform entry points, mirroring ChessEngine injection. */
@Immutable
class Board3DSupport(
    val rendererFactory: Chess3DRendererFactory,
    val surfaceContent: @Composable (renderer: Chess3DBoardRenderer, modifier: Modifier) -> Unit,
)
```

`dispose()` is the one addition over the issue's sketch — without it the factory-created renderer leaks GPU resources when the toggle is switched off (`detach()` only severs the surface). The `transition` overload and `setSelectedSquare` were added by M5 so animation and selection highlighting stay visual-only.

### `Board3DInput.kt`

```kotlin
sealed interface Board3DInput {
    /** Normalized [0,1] surface coords, origin top-left. Historical; taps are now ray-picked in Board3DHost. */
    data class Tap(val xNorm: Float, val yNorm: Float) : Board3DInput
    /** Camera orbit — purely visual state, owned by the 3D layer per the issue. */
    data class Drag(val deltaXNorm: Float, val deltaYNorm: Float) : Board3DInput
    data class Zoom(val factor: Float) : Board3DInput
    data class Resize(val widthPx: Int, val heightPx: Int) : Board3DInput
    /** Host-computed camera (from OrbitCameraController); renderers just render it. */
    data class SetCamera(val camera: CameraParams) : Board3DInput
}
```

### Scene layer (pure data, renderer-agnostic, fully unit-testable)

`Board3DScene.kt`:

```kotlin
enum class PieceKind { KING, QUEEN, ROOK, BISHOP, KNIGHT, PAWN }
enum class PieceColor { WHITE, BLACK }

/** Same convention as the 2D app: row 0 = rank 8 (black's back rank), col 0 = file a. */
data class BoardSquare(val row: Int, val col: Int)

data class Piece3DInstance(
    val kind: PieceKind,
    val color: PieceColor,
    val square: BoardSquare,
    val position: Vec3,            // world-space center from BoardGeometry.squareCenter
    val rotationYDegrees: Float,   // 0f for white, 180f for black (knights face each other)
)

data class Board3DScene(
    val pieces: List<Piece3DInstance>,
    val sideToMove: PieceColor,
    val selectedSquare: BoardSquare? = null,  // visual highlight state
)
```

`Board3DSceneMapper.kt`:

```kotlin
object Board3DSceneMapper {
    /** Parses only the placement + active-color FEN fields. Throws IllegalArgumentException on bad FEN. */
    fun fromFen(fen: String): Board3DScene
}
```

`BoardGeometry.kt` — world space: board centered at origin on the y=0 plane, +x toward file h, +z toward rank 1 (White's side), square edge 1.0. So `squareCenter(BoardSquare(row, col)) = Vec3((col - 3.5f) * S, 0f, (row - 3.5f) * S)`; a1 = `BoardSquare(7, 0)` → `Vec3(-3.5, 0, 3.5)`.

```kotlin
object BoardGeometry {
    const val SQUARE_SIZE: Float = 1f
    const val BOARD_HALF_EXTENT: Float = 4f * SQUARE_SIZE
    fun squareCenter(square: BoardSquare): Vec3            // y = 0
    fun squareFromWorld(x: Float, z: Float): BoardSquare?  // null outside the 8x8 area
}
```

`Math3D.kt`:

```kotlin
data class Vec3(val x: Float, val y: Float, val z: Float)  // plus/minus/times/dot/cross/normalized/length

data class Ray(val origin: Vec3, val direction: Vec3)

data class CameraParams(
    val position: Vec3, val target: Vec3, val up: Vec3,
    val fovYDegrees: Float, val aspect: Float, val near: Float, val far: Float,
)

object CameraMath {
    /** xNorm/yNorm in [0,1], origin top-left (matches Board3DInput.Tap). */
    fun rayFromScreen(camera: CameraParams, xNorm: Float, yNorm: Float): Ray
    /** Inverse, for round-trip tests: world point -> normalized screen coords (null if behind camera). */
    fun worldToScreen(camera: CameraParams, point: Vec3): Pair<Float, Float>?
}

object BoardRayPicker {
    /** Intersects the ray with the y=0 plane, then BoardGeometry.squareFromWorld.
     *  Null if parallel/behind/off-board. */
    fun pickSquare(ray: Ray): BoardSquare?
    fun pickSquare(ray: Ray, scene: Board3DScene?): BoardSquare?
}

/** Pure visual camera state machine (yaw/pitch/distance around board center). */
class OrbitCameraController(aspect: Float) {
    val camera: CameraParams
    fun onDrag(deltaXNorm: Float, deltaYNorm: Float)   // pitch clamped [15°, 85°]
    fun onZoom(factor: Float)                          // distance clamped [6, 26]
    fun onResize(aspect: Float)
    companion object { val DEFAULT_WHITE_VIEW: CameraParams /* behind White, ~35° pitch */ }
}
```

`ChessSetMeshNames.kt` — pure data table mapping `PieceKind`/`PieceColor` → glTF node/mesh name in `chess.glb`. Exact names are filled in during the M1 spike when the asset is inspected; the table exists so the mapping is unit-tested in common rather than buried in each backend.

### `Board3DHost.kt` — common host composable

```kotlin
@Composable
fun Board3D(
    support: Board3DSupport,
    fen: String,
    modifier: Modifier = Modifier,
    onUnavailable: () -> Unit,
    selectedSquare: BoardSquare? = null,
    onSquareTapped: (BoardSquare) -> Unit = {},
)
```

Behavior: `LaunchedEffect(Unit)` calls `support.rendererFactory.create()`; null → `onUnavailable()`. With a renderer: render `support.surfaceContent(renderer, modifier.testTag("board_3d"))` (the surfaceContent attaches/detaches the platform surface); `LaunchedEffect(renderer, fen)` maps the FEN to a scene, diffs it against the previous scene with `Board3DSceneDiffer`, and calls `renderer.updatePosition(fen, transition)`; `LaunchedEffect(renderer, selectedSquare)` forwards highlight state via `setSelectedSquare`. Pointer input uses an `OrbitCameraController` for drag/pinch zoom, forwards `Board3DInput.SetCamera`, and ray-picks taps with `CameraMath`/`BoardRayPicker` before routing the square back through `onSquareTapped`. `DisposableEffect(renderer) { onDispose { renderer.detach(); renderer.dispose() } }`.

### `FakeChess3DRenderer` (commonTest)

`app/src/commonTest/kotlin/com/example/myapplication/board3d/FakeChess3DRenderer.kt` — records an ordered `events: MutableList<String>` (`"attach"`, `"detach"`, `"updatePosition:<fen>"`, `"updatePositionWithTransition:<fen>:<bool>"`, `"input:<type>"`, `"select:<square>"`, `"dispose"`), exposes `lastFen` and `isAttached`. Reused by every platform's UI tests (commonTest is visible to all test source sets in this repo).

## State and UI wiring (commonMain)

- `ViewState` (`GameUiState.kt`) gains `show3D: Boolean = false` and `board3DUnavailable: Boolean = false`.
- `GameViewModel` gains `fun setShow3D(enabled: Boolean)` (clears `board3DUnavailable` when enabling) and `fun markBoard3DUnavailable()` (sets the flag, flips `show3D` back off).
- `ChessApp(viewModel, modifier, board3D: Board3DSupport? = null)` and `GameScreen(windowSize, viewModel, board3D: Board3DSupport? = null)` — default null means entry points without a backend keep compiling unchanged.
- `GameScreen`: in the settings row add a 3D `Checkbox` only when `board3D != null`, with `Modifier.testTag("board_3d_toggle")` + label. The board area shows **exactly one** board at a time: `if (viewState.show3D && board3D != null)` render `Board3D(...)` (testTag `board_3d`, `fillMaxWidth().aspectRatio(1f)`); otherwise render the 2D `Board(...)`. Enabling 3D **hides** the 2D board — the 2D `chess_board` is *not* composed while 3D is shown. When `viewState.board3DUnavailable`, show a `Text` with `Modifier.testTag("board_3d_unavailable")`. FEN derived with `remember(gameState) { FenConverter.gameStateToFen(gameState) }`. Selection and move callbacks are shared with the 2D board, so 3D tap-to-move follows the same legal-move path.
- New strings in `app/src/commonMain/composeResources/values/strings.xml`: `board_3d_toggle_label`, `board_3d_unavailable`.

## Execution rules

- One branch/PR per milestone (M1 may split into two PRs: abstraction+toggle, then desktop backend).
- Never modify the documented build quirks: the compose-resources reflection hacks in `app/build.gradle.kts`, `jniLibs.useLegacyPackaging` in `androidApp`, the `embedAndSignAppleFrameworkForXcode` build-phase ordering.
- A milestone is done only when the full CI matrix builds:
  `./gradlew :androidApp:assembleDebug :app:assembleAndroidDeviceTest :app:check :app:desktopJar :app:packageDistributionForCurrentOS :app:wasmJsBrowserDistribution`
  plus the apple-job tasks (`:app:iosSimulatorArm64Test`, `:app:desktopTest`, `:app:linkReleaseFrameworkIosArm64`, xcodebuild test).
- Spike/mini-spike verdicts are appended to the relevant milestone doc ("Spike result" section) so decisions aren't relitigated.

## Risk register

| Risk | Status / Mitigation |
|---|---|
| Materia offscreen render/readback unsupported on JVM | **Resolved (spike): confirmed unsupported.** M1 used LWJGL headless Vulkan; the current desktop factory has since moved to wgpu4k offscreen WebGPU. |
| Materia consumed via Gradle composite build (`includeBuild`) | **Resolved (spike): composite build fails under Gradle 9.x.** If Materia is used at all, build it with its own Gradle 8.13 and publish to a Maven repo. |
| Kotlin 2.2.20-built klibs unreadable from 2.3.20 | **Resolved by not using Materia.** wgpu4k resolves for desktop and wasm in this branch; wasm builds and tests compile. |
| Materia Apple backend is beta | M2 go/no-go mini-spike; rescope path = Metal-direct renderer behind the same interface |
| Two renderer codebases if M2–M4 adopt Materia (desktop is LWJGL) | **Resolved by direction change.** Desktop/web use shared wgpu4k; iOS/Android keep native interim engines until wgpu4k native targets mature. |
| chess.gltf piece-model license unverified (Matt Joos via Sketchfab) | **Resolved:** Replacing with CC-licensed model (see [issue-32-3d-ui-unresolved-questions.md](issue-32-3d-ui-unresolved-questions.md)). |
| FEN reflects the post-move state while the 2D animation is still playing | **Resolved by M5 transitions.** `Board3DSceneDiffer` supplies backend-agnostic movement transitions. |
| **(wgpu4k) Dependency is a pre-release SNAPSHOT** | `io.ygdrasil:wgpu4k-toolkit` has no stable release (latest `0.2.0-SNAPSHOT`, only milestone `0.1.0.M2`); from a GitLab Maven repo, not Central. Mitigation: pin the timestamped snapshot; track upstream for a 0.2.0 release before shipping. |
| **(wgpu4k) Android + iOS backends are WIP** | Confirmed WIP at v27.x — full unification can't complete yet. Mitigation: wasm-first; keep Filament/SceneKit until wgpu4k's native targets mature; gate each migration on visual parity ([M6](issue-32-3d-ui-m6-wgpu4k.md)). |
| **(wgpu4k) Desktop needs modern JDK / Panama FFM** | Desktop code currently targets JVM 24; `:app:run` and desktop tests use a scoped JDK 26 launcher. Mitigation: dev/CI on JDK 26 (✓); packaged desktop distribution must bundle/require a compatible modern JRE. |
| **(superseded) Materia rows above** | The Materia-specific risks are historical — the committed direction is wgpu4k, not Materia. |
