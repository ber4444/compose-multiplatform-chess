# Issue #32 — 3D UI, Milestone 1: Foundation + Desktop Backend

Prereq reading: [issue-32-3d-ui-overview.md](issue-32-3d-ui-overview.md) (architecture decisions, full commonMain API spec, execution rules). Suggested branch: `issue-32-3d-m1`.

Deliverables: the Materia spike verdict, the commonMain `Chess3DBoardRenderer` abstraction and scene layer, a 2D/3D toggle with graceful fallback compiled on **all** platforms, a real 3D backend on **desktop JVM**, the chess.glb asset, and CI updates. Phases B and C are independent of the spike outcome; run Phase A first only because it gates Phase D's engine choice.

## Phase A — Materia spike (timeboxed, throwaway)

**Timebox: 3 working days equivalent; hard stop.** Work on a throwaway branch (`spike/materia-desktop`). Nothing from the spike branch merges; the outputs are (1) a "Spike result" section appended to this doc with a PASS/FAIL verdict per criterion, and (2) if PASS, the pinned submodule SHA and the actual glTF mesh names for `ChessSetMeshNames.kt`.

Setup:

```bash
git submodule add https://github.com/codeyousef/Materia third_party/materia
cd third_party/materia && git checkout <v0.4.1.0 tag> && cd -
```

In `settings.gradle.kts`: `includeBuild("third_party/materia")` with `dependencySubstitution` so `:app`'s desktopMain can depend on Materia's engine/GPU modules. Materia is not on Maven Central; it builds with Kotlin 2.2.20 vs this repo's 2.3.20 — the composite build keeps each build's Kotlin Gradle plugin separate, so the risk being probed is klib/metadata-level consumption.

Spike tasks, each with a binary criterion:

| # | Task | Pass criterion | Gate? |
|---|---|---|---|
| S1 | Composite build: `:app` desktopMain depends on Materia modules; `./gradlew :app:desktopJar` | Compiles; Kotlin 2.3.20 consumes the 2.2.20-built JVM artifacts without metadata errors | Yes |
| S2 | Plain `main()`: init Materia's JVM backend (Vulkan/LWJGL), render a cube | Renders on the dev machine (macOS = MoltenVK path) | No |
| S3 | **Offscreen + readback**: render to texture, read pixels to an `IntArray`/`ByteBuffer` — no visible/GLFW-owned window required, callable off the main thread | API exists, or a ≤200-line fork patch exposes render-to-texture + buffer copy. Materia's JVM backend hints at a hidden GLFW surface, which is a macOS main-thread hazard under AWT — **this is the likely killer; test it first** | Yes |
| S4 | Materia's glTF loader parses `chess.glb` from a `ByteArray`; node/mesh names enumerable; one piece renders with a PBR material | Loads without crash; record the actual mesh names | Yes |
| S5 | Frame-loop control: render a single frame on demand from our own coroutine (no captive run loop) | A `render(scene, camera)`-style call exists | Yes |
| S6 | Feed readback frames into a Compose `Image` in a minimal Compose Desktop window | ≥30 fps at 700×700 during continuous orbit; UI stays responsive | No |

**Gate: S1, S3, S4, S5 must all pass → use Materia** (add the submodule on the milestone branch, pin the SHA). **Any gate failure → LWJGL fallback** (overview Decision D; see Phase D fallback notes) and `third_party/materia` is never added to the main branches. Phases B–F proceed unchanged either way.

## Phase B — commonMain abstraction + scene mapping (no rendering)

Create every file specified in the overview's "commonMain API" section under `app/src/commonMain/kotlin/com/example/myapplication/board3d/`:

- `Chess3DBoardRenderer.kt`, `Board3DInput.kt`, `Board3DScene.kt`, `Board3DSceneMapper.kt`, `BoardGeometry.kt`, `Math3D.kt`, `ChessSetMeshNames.kt`, `Board3DHost.kt`

Plus the state/UI wiring (also specified in the overview):

- `GameUiState.kt` — `ViewState` gains `show3D`, `board3DUnavailable`.
- `GameViewModel.kt` — `setShow3D(enabled)`, `markBoard3DUnavailable()`.
- `ChessApp.kt` / `GameScreen.kt` — `board3D: Board3DSupport? = null` parameter, toggle checkbox (`testTag("board_3d_toggle")`) in the settings row next to AutoPlay, `Board3D` above the always-mounted 2D `Board`, unavailable text (`testTag("board_3d_unavailable")`).
- `app/src/commonMain/composeResources/values/strings.xml` — `board_3d_toggle_label`, `board_3d_unavailable`.
- `app/src/commonTest/kotlin/com/example/myapplication/board3d/FakeChess3DRenderer.kt`.

### Unit tests (`app/src/commonTest/kotlin/com/example/myapplication/board3d/`)

kotlin.test, backtick function names, following `MoveTest.kt` conventions. FEN inputs can be produced with `FenConverter` or written literally.

**`Board3DSceneMapperTest`**
- starting FEN → exactly 32 instances, 16 per color; white king at `BoardSquare(7, 4)`, black king at `BoardSquare(0, 4)`
- FEN after `1. e4` → a white PAWN at `BoardSquare(4, 4)`, none at `BoardSquare(6, 4)`
- a capture FEN (e.g. after exd5) → 31 instances
- white kingside-castled FEN → KING at `(7, 6)` **and** ROOK at `(7, 5)`
- promotion FEN → `PieceKind.QUEEN` on the back rank, no PAWN there
- en-passant-capture FEN → the captured pawn's square is empty
- `sideToMove` parses `w`/`b`
- black instances have `rotationYDegrees == 180f`, white `0f`
- malformed FEN (truncated placement, bad chars) → `IllegalArgumentException`

**`BoardGeometryTest`**
- `squareCenter(BoardSquare(7, 0))` (a1) == `Vec3(-3.5f, 0f, 3.5f)`; h8 mirrored at `Vec3(3.5f, 0f, -3.5f)`; e4 exact
- `squareFromWorld` round-trips all 64 centers
- points beyond `±BOARD_HALF_EXTENT` → null
- board centered: sum of all 64 centers ≈ origin

**`CameraMathTest`**
- ray through screen center ≈ normalized `(target − position)`
- `worldToScreen(p)` → `rayFromScreen` round-trip passes within ε of `p` for several squares
- aspect ≠ 1 handled (no x/y swap)

**`BoardRayPickerTest`**
- vertical ray at each square center → that square
- ray parallel to the board plane → null; ray pointing away → null
- full 64-square round-trip with `OrbitCameraController.DEFAULT_WHITE_VIEW`: `worldToScreen(squareCenter(s))` → `rayFromScreen` → `pickSquare` == `s`

**`OrbitCameraControllerTest`**
- pitch clamps at [15°, 85°]; zoom distance clamps at [6, 20]
- drag changes yaw monotonically; `onResize` updates aspect only

**`GameViewModelTest` (extend the existing test class)**
- `setShow3D(true)` sets the flag; `markBoard3DUnavailable()` resets `show3D` and sets the flag; `setShow3D(true)` again clears `board3DUnavailable`

**`FakeChess3DRendererTest`**
- `updatePosition` before `attach` buffers `lastFen`; double `attach` records a `detach` first (contract self-check)

## Phase C — Toggle + fallback UI tests on all platforms

All of these use `FakeChess3DRenderer` plus a trivial fake `surfaceContent` (`Box(modifier)`) injected via `Board3DSupport`, so **no GPU anywhere**. They inject the support directly into `GameScreen`, validating the full toggle path on Android/iOS/wasm ahead of those platforms' backend milestones (their entry points still pass `null` in M1).

Test cases (same five bodies on each platform):

1. `toggle shows 3d surface` — click `board_3d_toggle`; assert node `board_3d` exists **and** `chess_board` still exists.
2. `renderer lifecycle follows composition` — after toggle on, fake events begin `["attach", "updatePosition:<starting FEN>"]`; after toggle off, events end `["detach", "dispose"]`.
3. `2d interaction drives 3d while active` — toggle on; click `board_square_WhitePiece_6_4` then `board_square_PossibleMove_4_4` (e2–e4, existing `squareTestTag` format); `waitUntil` the fake's `lastFen` equals the post-e4 FEN.
4. `factory null falls back to 2d` — factory returns null; click toggle; assert `board_3d` does not exist, `board_3d_unavailable` exists, toggle is unchecked, `chess_board` still interactive.
5. `no support hides toggle` — `board3D = null` → `board_3d_toggle` does not exist.

Locations:

- `app/src/desktopTest/kotlin/com/example/myapplication/board3d/Board3DToggleUiTest.kt` — **new capability**: add to `app/build.gradle.kts` a `desktopTest` dependency on `compose.uiTest` (with `@OptIn(org.jetbrains.compose.ExperimentalComposeLibrary::class)`), mirroring the existing `wasmJsTest` and `iosSimulatorArm64Test` blocks. Use `runComposeUiTest`.
- `app/src/androidDeviceTest/kotlin/com/example/myapplication/Board3DToggleTest.kt` — `createComposeRule()` + JUnit4, following `GameScreenTest.kt`.
- `app/src/iosSimulatorArm64Test/kotlin/com/example/myapplication/Board3DToggleUiTest.kt` — `runComposeUiTest`, following `GameScreenUiTest.kt`.
- `app/src/wasmJsTest/kotlin/com/example/myapplication/Board3DToggleUiTest.kt` — `runComposeUiTest`.

## Phase D — Desktop backend (`app/src/desktopMain/kotlin/com/example/myapplication/board3d/`)

Files (Materia path; on fallback, swap `MateriaChessRenderer` for `VulkanChessRenderer` + pipeline files — the public surface is identical):

- **`ImageBitmapChess3DSurface.kt`** — `class ImageBitmapChess3DSurface(override val widthPx: Int, override val heightPx: Int, val onFrame: (ImageBitmap) -> Unit) : Chess3DSurface`, plus `internal fun IntArray.toImageBitmap(w: Int, h: Int): ImageBitmap` (Skia `Bitmap.installPixels` → `asComposeImageBitmap`).
- **`DesktopBoard3DSurface.kt`** — `@Composable fun DesktopBoard3DSurface(renderer: Chess3DBoardRenderer, modifier: Modifier)`: `remember` a `mutableStateOf<ImageBitmap?>` frame; create the surface sized via `onSizeChanged` (also emit `Board3DInput.Resize`); `DisposableEffect(renderer, surface) { renderer.attach(surface); onDispose { renderer.detach() } }`; draw `Image(frame, ...)`.
- **`MateriaChessRenderer.kt`** — `class MateriaChessRenderer(private val chessSetGlb: ByteArray) : Chess3DBoardRenderer`. Owns a dedicated render dispatcher (`newSingleThreadContext("chess3d-render")` — Vulkan is thread-affine). Builds the Materia scene from `Board3DSceneMapper.fromFen(...)` + `ChessSetMeshNames`; the board itself is 64 instanced quad/box meshes with two materials. Renders **on demand** (scene or camera change) and pushes frames through the attached `ImageBitmapChess3DSurface.onFrame`.
- **`DesktopBoard3D.kt`** — `fun desktopBoard3DSupport(): Board3DSupport` wiring `Chess3DRendererFactory { runCatching { MateriaChessRenderer(Res.readBytes("files/models/chess.glb")) }.getOrNull() }` (any init failure → null → graceful 2D fallback) + `::DesktopBoard3DSurface`.
- **`Main.kt`** (modify) — `ChessApp(viewModel = viewModel, board3D = remember { desktopBoard3DSupport() })`.

Gradle (`app/build.gradle.kts` + `gradle/libs.versions.toml`):

- Materia path: desktopMain `implementation` on the substituted Materia modules.
- Fallback path: versions `lwjgl = "3.3.6"`, `jgltf = "2.0.5"`; artifacts `org.lwjgl:lwjgl`, `org.lwjgl:lwjgl-vulkan` plus runtime natives classifiers `natives-linux`, `natives-macos`, `natives-macos-arm64`, `natives-windows` (lwjgl-vulkan needs no GLFW for offscreen rendering and its macOS natives bundle MoltenVK), and `de.javagl:jgltf-model`.

### Desktop backend unit tests (`app/src/desktopTest/kotlin/com/example/myapplication/board3d/`)

- **`GltfChessSetTest`** — `chess.glb` resource parses; contains every mesh name in `ChessSetMeshNames`; each piece's bounding box fits within ~1×3×1 world units (catches scale mismatches between the asset and `BoardGeometry`).
- **`RendererContractTest`** — against the real renderer with a no-op frame sink: `updatePosition` before `attach` doesn't crash and is applied on attach; `detach` is idempotent; calls from two threads don't deadlock (the renderer marshals internally); `dispose` after `detach` succeeds. Skipped via JUnit `Assume` when Vulkan init fails (no driver).
- **`DesktopRendererSmokeTest`** — guarded by `Assume.assumeTrue(System.getProperty("chess3d.smoke") == "true")`: attach a 256×256 sink, `updatePosition(STARTING_FEN)`, request 3 frames, assert at least one frame arrived containing >1 distinct color; Vulkan validation layers clean if `VK_LAYER_PATH` is present.

## Phase E — Assets

1. Fetch `data/chess.gltf` (+ buffers/textures) from https://github.com/jpbruyere/vkChess at a pinned commit (the repo is MIT). **Blocking license task:** the piece models are credited to Matt Joos via Sketchfab; locate the model's actual license page and record the finding here. Likely CC-BY 4.0 → fine **with attribution**. If NC/ND or unverifiable → substitute a CC0 chess set (search OpenGameArt/Kenney for "chess CC0"); last-resort safe option: procedural lathe-profile meshes generated in code.
2. Convert to a single self-contained binary (avoids multi-file URI resolution on wasm/iOS later): `npx @gltf-transform/cli copy chess.gltf chess.glb`. Record the exact command/version here; commit only the `.glb`.
3. Place at `app/src/commonMain/composeResources/files/models/chess.glb` — loadable on every platform (including wasm and the Android assets reflection hack, which already handles compose resources per CLAUDE.md) via `Res.readBytes("files/models/chess.glb")` inside each platform factory.
4. Add `THIRD_PARTY_NOTICES.md` at the repo root (model attribution; Materia Apache-2.0; LWJGL/jgltf licenses if on the fallback path) and link it from the README.

## Phase F — CI (`.github/workflows/android-tests.yml`)

- **ubuntu job**: add a step `sudo apt-get update && sudo apt-get install -y mesa-vulkan-drivers vulkan-tools` (lavapipe = CPU Vulkan). After the main build step, add `./gradlew :app:desktopTest --tests "*board3d*" -Dchess3d.smoke=true` with `continue-on-error: true` initially; promote to required once stable. The existing `:app:check` already runs all new commonTest/desktopTest non-smoke tests with zero GPU needs.
- **macos (apple) job**: append `-Dchess3d.smoke=true` to the Gradle test invocation (MoltenVK available via LWJGL natives / Materia); `continue-on-error: true` first.
- If Materia passed the gate: add `submodules: recursive` to both jobs' checkout steps.

## Definition of done

- Spike verdict recorded below; engine decision made and reflected in Phase D.
- All Phase B unit tests and Phase C UI tests pass: `./gradlew :app:check :app:desktopTest :app:iosSimulatorArm64Test`, and the new androidDeviceTest cases pass under `./gradlew :app:connectedAndroidDeviceTest` (device/emulator required).
- Desktop app shows the 3D toggle; enabling it renders the 3D board (or falls back gracefully with the unavailable message if the local machine lacks Vulkan); the game remains fully playable via the 2D board throughout.
- Full CI matrix builds (see overview "Execution rules").
- `THIRD_PARTY_NOTICES.md` present; license verification recorded in Phase E.

## Spike result

_To be appended by the implementing agent: per-criterion PASS/FAIL table, chosen engine, pinned Materia SHA (if applicable), actual glTF mesh names, and any fork patches required._
