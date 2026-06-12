# Issue #32 — 3D UI: Overview and Architecture

Implementation plan for [issue #32](https://github.com/ber4444/compose-multiplatform-chess/issues/32): adopt the 3D look of [vkChess](https://github.com/jpbruyere/vkChess) (C++/Vulkan PBR chess) behind a `Chess3DBoardRenderer` abstraction. Initially target Vulkan + MoltenVK; WebGPU later. The 2D board stays the canonical interaction model; 3D is a view layer mirroring the same FEN/game state.

This document holds the decisions and the shared commonMain API. Each milestone has its own execution-ready plan doc:

| Milestone | Doc | Scope |
|---|---|---|
| M1 | [issue-32-3d-ui-m1-foundation.md](issue-32-3d-ui-m1-foundation.md) | Materia spike, commonMain abstraction + scene mapping, 2D/3D toggle with graceful fallback on all platforms, real desktop JVM backend, assets, CI |
| M2 | [issue-32-3d-ui-m2-apple.md](issue-32-3d-ui-m2-apple.md) | iOS backend (MoltenVK via Materia; Metal-direct rescope path) |
| M3 | [issue-32-3d-ui-m3-android.md](issue-32-3d-ui-m3-android.md) | Android backend (Vulkan from a `SurfaceView`) |
| M4 | [issue-32-3d-ui-m4-wasm.md](issue-32-3d-ui-m4-wasm.md) | Wasm backend (WebGPU overlay canvas) |
| M5 | [issue-32-3d-ui-m5-interaction-animation.md](issue-32-3d-ui-m5-interaction-animation.md) | 3D tap-to-move via ray picking, smooth piece animation, camera polish |

## Engine choice

Primary: wrap **[Materia](https://github.com/codeyousef/Materia)** — a KMP 3D library with a Three.js-style scene graph, glTF 2.0 loader, and backends matching our target matrix: WebGPU (JS/wasm), Vulkan via LWJGL 3.3.6 (JVM), Vulkan (Android API 24+), MoltenVK (Apple, beta). Apache-2.0.

Known constraints (verified June 2026, record any changes here):

- **Alpha software** (v0.4.1.0), APIs may change.
- **Not published to Maven Central** — must be vendored: git submodule at `third_party/materia` pinned to a tag, consumed via Gradle composite build (`includeBuild` + dependency substitution in `settings.gradle.kts`).
- Built with **Kotlin 2.2.20** vs this repo's 2.3.20. A composite build keeps each build's Kotlin Gradle plugin separate, so the risk is klib/metadata-level consumption, not plugin clash. Lowest risk on JVM, highest on wasm.

The adoption is gated by a **timeboxed spike** (M1 Phase A). If the gate fails, the fallback is a direct **LWJGL headless Vulkan** renderer on desktop (see M1 doc); the `Chess3DBoardRenderer` abstraction isolates the rest of the app from this choice either way. Each later milestone has its own go/no-go mini-spike with a documented rescope path.

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
- **macOS threading.** GLFW windows must be created on the process's first thread, which AWT/Compose owns. Headless Vulkan (no swapchain) renders offscreen from any thread.
- **CI.** GitHub's ubuntu runners have no GPU; offscreen Vulkan runs on lavapipe (`mesa-vulkan-drivers`). LWJGL's `lwjgl-vulkan` macOS natives bundle MoltenVK, so macos runners work too.
- **Cost is fine.** The board renders on demand (scene or camera change), not in a hot loop; a 700×700 RGBA readback is ~2 MB.

### D. Fallback engine = LWJGL headless Vulkan minimal pipeline

If the Materia spike fails: instance → device → one render pass → one graphics pipeline → vertex/index/uniform buffers → render to image → `vkCmdCopyImageToBuffer` → map. Offscreen rendering removes swapchain/WSI/resize complexity; roughly 1.5–2.5k lines, mechanical from the LWJGL Vulkan samples. glTF parsing via `de.javagl:jgltf-model` (MIT, Maven Central). Plain OpenGL would be less code but is deprecated on macOS and contradicts the issue's explicit Vulkan-first direction. vkChess's C++ code is **not** vendored — only its asset (`data/chess.gltf`); a JNI + CMake bridge to its `vke`/`vkvg` stack is rejected.

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
    fun onUserInteraction(event: Board3DInput)
    fun dispose()
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

`dispose()` is the one addition over the issue's sketch — without it the factory-created renderer leaks GPU resources when the toggle is switched off (`detach()` only severs the surface).

### `Board3DInput.kt`

```kotlin
sealed interface Board3DInput {
    /** Normalized [0,1] surface coords, origin top-left. M1 renderers may ignore; M5 consumes via ray pick. */
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
    val selectedSquare: BoardSquare? = null,  // unused until M5 highlight
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
}

/** Pure visual camera state machine (yaw/pitch/distance around board center). */
class OrbitCameraController(aspect: Float) {
    val camera: CameraParams
    fun onDrag(deltaXNorm: Float, deltaYNorm: Float)   // pitch clamped [15°, 85°]
    fun onZoom(factor: Float)                          // distance clamped [6, 20]
    fun onResize(aspect: Float)
    companion object { val DEFAULT_WHITE_VIEW: CameraParams /* behind White, ~45° pitch */ }
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
)
```

Behavior: `LaunchedEffect(Unit)` calls `support.rendererFactory.create()`; null → `onUnavailable()`. With a renderer: render `support.surfaceContent(renderer, modifier.testTag("board_3d"))` (the surfaceContent attaches/detaches the platform surface); `LaunchedEffect(renderer, fen) { renderer.updatePosition(fen) }`; pointer input (drag/scroll) feeds an `OrbitCameraController` whose camera is forwarded via `Board3DInput.SetCamera`; `DisposableEffect(renderer) { onDispose { renderer.detach(); renderer.dispose() } }`.

### `FakeChess3DRenderer` (commonTest)

`app/src/commonTest/kotlin/com/example/myapplication/board3d/FakeChess3DRenderer.kt` — records an ordered `events: MutableList<String>` (`"attach"`, `"detach"`, `"updatePosition:<fen>"`, `"input:<type>"`, `"dispose"`), exposes `lastFen` and `isAttached`. Reused by every platform's UI tests (commonTest is visible to all test source sets in this repo).

## State and UI wiring (commonMain)

- `ViewState` (`GameUiState.kt`) gains `show3D: Boolean = false` and `board3DUnavailable: Boolean = false`.
- `GameViewModel` gains `fun setShow3D(enabled: Boolean)` (clears `board3DUnavailable` when enabling) and `fun markBoard3DUnavailable()` (sets the flag, flips `show3D` back off).
- `ChessApp(viewModel, modifier, board3D: Board3DSupport? = null)` and `GameScreen(windowSize, viewModel, board3D: Board3DSupport? = null)` — default null means entry points without a backend keep compiling unchanged.
- `GameScreen`: in the settings row next to the AutoPlay `Checkbox`, add (only when `board3D != null`) a `Checkbox` with `Modifier.testTag("board_3d_toggle")` + label. When `viewState.show3D`, render `Board3D(...)` (testTag `board_3d`, `fillMaxWidth().aspectRatio(1f)`) **above** the existing `Board(...)`. The 2D board always stays mounted and remains the canonical interaction surface. When `viewState.board3DUnavailable`, show a `Text` with `Modifier.testTag("board_3d_unavailable")`. FEN derived with `remember(gameState) { FenConverter.gameStateToFen(gameState) }`.
- New strings in `app/src/commonMain/composeResources/values/strings.xml`: `board_3d_toggle_label`, `board_3d_unavailable`.

## Execution rules

- One branch/PR per milestone (M1 may split into two PRs: abstraction+toggle, then desktop backend).
- Never modify the documented build quirks: the compose-resources reflection hacks in `app/build.gradle.kts`, `jniLibs.useLegacyPackaging` in `androidApp`, the `embedAndSignAppleFrameworkForXcode` build-phase ordering.
- A milestone is done only when the full CI matrix builds:
  `./gradlew :androidApp:assembleDebug :app:assembleAndroidDeviceTest :app:check :app:desktopJar :app:packageDistributionForCurrentOS :app:wasmJsBrowserDistribution`
  plus the apple-job tasks (`:app:iosSimulatorArm64Test`, `:app:desktopTest`, `:app:linkReleaseFrameworkIosArm64`, xcodebuild test).
- Spike/mini-spike verdicts are appended to the relevant milestone doc ("Spike result" section) so decisions aren't relitigated.

## Risk register

| Risk | Mitigation |
|---|---|
| Materia offscreen render/readback unsupported on JVM | Explicit spike gate S3; ≤200-line fork patch acceptable; else LWJGL fallback (Decision D) |
| Kotlin 2.2.20-built klibs unreadable from 2.3.20 | Composite build isolates plugins; spike S1 (JVM) and M4 day-1 (wasm, highest risk) catch it; rescope = fork Materia and bump Kotlin |
| Materia Apple backend is beta | M2 go/no-go mini-spike; rescope path = Metal-direct renderer behind the same interface |
| chess.gltf piece-model license unverified (Matt Joos via Sketchfab) | Blocking M1 task: verify (likely CC-BY 4.0 → attribute); NC/ND/unverifiable → CC0 set substitution; last resort procedural meshes |
| FEN reflects the post-move state while the 2D animation is still playing | Accepted for M1–M4 (3D snaps); resolved by M5 transitions |
