# Issue #32 — 3D UI, Milestone 1: Foundation + Desktop Backend

Prereq reading: [issue-32-3d-ui-overview.md](issue-32-3d-ui-overview.md) (architecture decisions, full commonMain API spec, execution rules). Suggested branch: `issue-32-3d-m1`.

Deliverables: the Materia spike verdict, the commonMain `Chess3DBoardRenderer` abstraction and scene layer, a 2D/3D toggle with graceful fallback compiled on **all** platforms, a real 3D backend on **desktop JVM**, the chess.glb asset, and CI updates. Phases B and C are independent of the spike outcome; run Phase A first only because it gates Phase D's engine choice.

> **Status: IMPLEMENTED & verified on desktop.** Phases A–F are done. The desktop LWJGL headless Vulkan renderer actually renders the board + glTF pieces via MoltenVK and reads back to a Compose `ImageBitmap` (verified by `DesktopRendererSmokeTest`, which writes PNGs to `app/build/`). The abstraction/toggle/fallback compile on all targets; commonTest + desktopTest are green. Deferred to later sessions: iOS/Android/wasm backends (M2–M4) and the animation half of M5. (3D tap-to-move from M5 was pulled forward to make desktop 3D playable — see M5.)

## Phase A — Materia spike (timeboxed, throwaway) — ✅ DONE

> **STATUS: COMPLETED 2026-06-12. Gate FAILED at S3 → desktop backend = LWJGL headless Vulkan.** Do not re-run this spike. See the [Spike result](#spike-result) at the bottom for the verdict and the reusable Materia consumption recipe. The remainder of this Phase A section is retained as the record of what was tested.

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
- `ChessApp.kt` / `GameScreen.kt` — `board3D: Board3DSupport? = null` parameter, toggle checkbox (`testTag("board_3d_toggle")`) in the settings row next to AutoPlay, and a board area that shows `Board3D` **instead of** the 2D `Board` when 3D is enabled (the 2D board is hidden, not shown alongside), plus the unavailable text (`testTag("board_3d_unavailable")`).
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

Test cases — the board **swaps**, so 2D and 3D are never both present (as implemented in `Board3DUiTest.kt` on each platform):

1. `toggle swaps 2d for 3d` — initially `chess_board` present, `board_3d` absent; click `board_3d_toggle` (or `setShow3D(true)`); assert `board_3d` present **and** `chess_board` absent; toggle off → `chess_board` back, `board_3d` gone.
2. `renderer lifecycle follows composition` — after toggle on, the fake records one `attach`; after toggle off, exactly one `detach` and one `dispose`.
3. `factory null falls back to 2d` — factory returns null; toggle on → `board_3d` absent, `board_3d_unavailable` present, `chess_board` still present (the 2D board is what's shown on fallback).

(Deferred to M5, when 3D becomes interactive: a "2d interaction still drives the game" case — not applicable in M1 because 3D mode hides the 2D board and 3D has no tap-to-move yet.)

Locations (implemented; `desktopTest` gains `compose.desktop.uiTestJUnit4`):

- `app/src/desktopTest/kotlin/com/example/myapplication/board3d/Board3DUiTest.kt` — `createComposeRule()` + JUnit4.
- `app/src/androidDeviceTest/kotlin/com/example/myapplication/board3d/Board3DUiTest.kt` — `createComposeRule()` + JUnit4.
- `app/src/iosSimulatorArm64Test/kotlin/com/example/myapplication/board3d/Board3DUiTest.kt` — `runComposeUiTest`.
- `app/src/wasmJsTest/kotlin/com/example/myapplication/board3d/Board3DUiTest.kt` — `runComposeUiTest`.

## Phase D — Desktop backend (`app/src/desktopMain/kotlin/com/example/myapplication/board3d/`)

**Engine: LWJGL headless Vulkan (per the spike — Materia is not used here).** The public surface below is renderer-agnostic; only `VulkanChessRenderer`'s internals are LWJGL-specific.

- **`ImageBitmapChess3DSurface.kt`** — `class ImageBitmapChess3DSurface(override val widthPx: Int, override val heightPx: Int, val onFrame: (ImageBitmap) -> Unit) : Chess3DSurface`, plus `internal fun IntArray.toImageBitmap(w: Int, h: Int): ImageBitmap` (Skia `Bitmap.installPixels` → `asComposeImageBitmap`).
- **`DesktopBoard3DSurface.kt`** — `@Composable fun DesktopBoard3DSurface(renderer: Chess3DBoardRenderer, modifier: Modifier)`: `remember` a `mutableStateOf<ImageBitmap?>` frame; create the surface sized via `onSizeChanged` (also emit `Board3DInput.Resize`); `DisposableEffect(renderer, surface) { renderer.attach(surface); onDispose { renderer.detach() } }`; draw `Image(frame, ...)`.
- **`VulkanChessRenderer.kt`** — `class VulkanChessRenderer(private val chessSetGlb: ByteArray) : Chess3DBoardRenderer`. Owns a dedicated render dispatcher (`newSingleThreadContext("chess3d-render")` — Vulkan is thread-affine). **Offscreen pipeline** (overview Decision D): VkInstance → device → render pass → graphics pipeline → vertex/index/uniform buffers → render into an offscreen `VkImage` → `vkCmdCopyImageToBuffer` → map → `IntArray`. Parses `chessSetGlb` with `de.javagl:jgltf-model` into per-piece meshes keyed by `ChessSetMeshNames`; builds the scene from `Board3DSceneMapper.fromFen(...)`; the board itself is 64 quads with two materials. Renders **on demand** (scene or camera change) and pushes frames through the attached `ImageBitmapChess3DSurface.onFrame`. No GLFW window, no swapchain (this is exactly why Materia's windowed renderer was rejected).
- **`DesktopBoard3D.kt`** — `fun desktopBoard3DSupport(): Board3DSupport` wiring `Chess3DRendererFactory { runCatching { VulkanChessRenderer(Res.readBytes("files/models/chess.glb")) }.getOrNull() }` (no Vulkan driver / init failure → null → graceful 2D fallback) + `::DesktopBoard3DSurface`.
- **`Main.kt`** (modify) — `ChessApp(viewModel = viewModel, board3D = remember { desktopBoard3DSupport() })`.

Gradle (`app/build.gradle.kts` + `gradle/libs.versions.toml`): versions `lwjgl = "3.3.6"`, `jgltf = "2.0.5"`; desktopMain artifacts `org.lwjgl:lwjgl`, `org.lwjgl:lwjgl-vulkan` (+ `lwjgl-shaderc` if compiling GLSL at runtime) plus runtime natives classifiers `natives-linux`, `natives-macos`, `natives-macos-arm64`, `natives-windows` (lwjgl-vulkan needs no GLFW for offscreen rendering and its macOS natives bundle MoltenVK — spike-confirmed GPU detection on Apple silicon), and `de.javagl:jgltf-model`. No Materia dependency, no composite build, no submodule.

### Desktop backend unit tests (`app/src/desktopTest/kotlin/com/example/myapplication/board3d/`)

- **`GltfChessSetTest`** — `chess.glb` resource parses; contains every mesh name in `ChessSetMeshNames`; each piece's bounding box fits within ~1×3×1 world units (catches scale mismatches between the asset and `BoardGeometry`).
- **`RendererContractTest`** — against the real renderer with a no-op frame sink: `updatePosition` before `attach` doesn't crash and is applied on attach; `detach` is idempotent; calls from two threads don't deadlock (the renderer marshals internally); `dispose` after `detach` succeeds. Skipped via JUnit `Assume` when Vulkan init fails (no driver).
- **`DesktopRendererSmokeTest`** — guarded by `Assume.assumeTrue(System.getProperty("chess3d.smoke") == "true")`: attach a 256×256 sink, `updatePosition(STARTING_FEN)`, request 3 frames, assert at least one frame arrived containing >1 distinct color; Vulkan validation layers clean if `VK_LAYER_PATH` is present.

## Phase E — Assets

1. Fetch `data/chess.gltf` from https://github.com/jpbruyere/vkChess at a pinned commit (single self-contained `.gltf`, ~8.16 MB, with embedded base64 buffers + textures). The repo is MIT, **but the spike found the README credits the piece models to Matt Joos (sketchfab.com/mathiasjoos) with NO license stated for the models themselves** — MIT on the repo does not clear the assets. **Blocking license task (unchanged, still open):** verify the Sketchfab model's actual license. CC-BY → fine **with attribution**. NC/ND or unverifiable → substitute a CC0 chess set (search OpenGameArt/Kenney for "chess CC0"); last-resort safe option: procedural lathe-profile meshes generated in code. **Do not commit the asset until this is cleared.**
2. Convert to a self-contained binary (spike-verified: 8.16 MB → 6.01 MB; one embedded BIN chunk; 72 nodes/meshes, 4 source materials `white`/`black`/`black-case`/`Material`; six piece template nodes named exactly `king`/`queen`/`rook`/`bishop`/`knight`/`pawn`): `npx --yes @gltf-transform/cli copy chess.gltf chess.glb`. Commit only the `.glb`.
3. Place at `app/src/commonMain/composeResources/files/models/chess.glb` — loadable on every platform (including wasm and the Android assets reflection hack, which already handles compose resources per CLAUDE.md) via `Res.readBytes("files/models/chess.glb")` inside each platform factory.
4. Add `THIRD_PARTY_NOTICES.md` at the repo root (chess-model attribution per the license task; **LWJGL (BSD-3) and `de.javagl:jgltf-model` (MIT)** for the desktop backend) and link it from the README. Add Materia (Apache-2.0) only if a later milestone actually adopts it.

## Phase F — CI (`.github/workflows/android-tests.yml`)

- **ubuntu job**: add a step `sudo apt-get update && sudo apt-get install -y mesa-vulkan-drivers vulkan-tools` (lavapipe = CPU Vulkan). After the main build step, add `./gradlew :app:desktopTest --tests "*board3d*" -Dchess3d.smoke=true` with `continue-on-error: true` initially; promote to required once stable. The existing `:app:check` already runs all new commonTest/desktopTest non-smoke tests with zero GPU needs.
- **macos (apple) job**: append `-Dchess3d.smoke=true` to the Gradle test invocation (MoltenVK is bundled in LWJGL's `lwjgl-vulkan` macOS natives); `continue-on-error: true` first.
- No submodule / composite-build steps are needed (Materia is not used on desktop).

## Definition of done

- Spike verdict recorded below; engine decision made and reflected in Phase D.
- All Phase B unit tests and Phase C UI tests pass: `./gradlew :app:check :app:desktopTest :app:iosSimulatorArm64Test`, and the new androidDeviceTest cases pass under `./gradlew :app:connectedAndroidDeviceTest` (device/emulator required).
- Desktop app shows the 3D toggle; enabling it renders the 3D board (or falls back gracefully with the unavailable message if the local machine lacks Vulkan); the game remains fully playable via the 2D board throughout.
- Full CI matrix builds (see overview "Execution rules").
- `THIRD_PARTY_NOTICES.md` present; license verification recorded in Phase E.

## Spike result

Executed 2026-06-12 on Apple M4 / macOS / JDK 21 (Temurin), against Materia v0.4.1.0 (`codes.yousef:materia`). The spike workspace (`tmp/`, git-ignored) and its `mavenLocal` publish were cleaned up after the run; the evidence and the reusable consumption recipe are folded into this section so the docs stay self-contained.

| # | Task | Result |
|---|---|---|
| S1 | Kotlin 2.3.20 consumes Materia 2.2.20 artifacts | **PASS, with build caveats.** Metadata reads cleanly (0 `io.materia.*` errors). BUT: composite build (`includeBuild`) **fails under Gradle 9.x** — Materia's `build.gradle.kts:746` uses the removed `javaexec{}` script API, and this repo is on Gradle 9.3.1 (AGP 9.1.1). Must build/publish Materia with its own Gradle 8.13 → Maven repo. Also: building needs Tint/naga (`compileShaders`) unless disabled (SPIR-V is pre-committed), and consumers must add LWJGL + native classifiers explicitly (Materia exposes them runtime-only). |
| S2 | Plain `main()` render | **FAIL.** GPU detected via MoltenVK (*Apple M4, Vulkan 1.2.296*); swapchain creation failed (`VkResult=-1000000001`, hidden-window/MoltenVK). |
| S3 | Offscreen + readback, off main thread | **FAIL (gate).** No windowless render path — `RendererFactory` requires a GLFW-window-backed `VulkanSurface`; renderer hardwires `VulkanSwapchain` through `initialize()`/`render()`. GLFW also requires the macOS main thread + `-XstartOnFirstThread`, conflicting with Compose/AWT and with off-thread readback. |
| S4 | Parse `chess.glb`, enumerate meshes, PBR | **PASS.** Parsed in 86 ms; all 6 piece nodes present (`king`/`queen`/`rook`/`bishop`/`knight`/`pawn`) with `MeshStandardMaterial`. Mesh-name + `white`/`black` material findings feed `ChessSetMeshNames.kt` regardless of renderer. |
| S5 | On-demand frame render | **API present, frames not.** `render(scene, camera)` is imperative/callable, but `initialize()` can't succeed without a working window swapchain (see S2/S3). |
| S6 | Readback into Compose `Image` | **N/A** — blocked by S3. |

**Conclusion**: Gate fails at S3. Materia's JVM backend is structurally a windowed-swapchain renderer with no offscreen render-to-texture path — the one capability the desktop interop (overview Decision C: offscreen → CPU readback → Compose `ImageBitmap`, off the UI thread) requires. Adding it is a deep fork of the 2694-line `VulkanRenderer`, well beyond the ≤200-line escape hatch.

**Decision**: Proceed with the **LWJGL headless Vulkan** renderer (overview Decision D) for the desktop backend; glTF parsing via `de.javagl:jgltf-model`. `third_party/materia` will not be added (no submodule, no composite build). The commonMain abstraction (Phases B/C) and the asset work (Phase E) are unchanged. Positive signal: LWJGL 3.3.6 + MoltenVK detected the GPU on this hardware (*Apple M4, Vulkan 1.2.296*), so the offscreen pipeline is viable on the same machine.

### Materia consumption recipe (only if a later milestone revisits it)

The spike proved this works for the JVM target; reuse it for M2/M3/M4 instead of the (broken) composite-build approach. **Re-verify S1 per target** first — wasm klibs are far less forward-compatible than JVM.

1. **Build & publish Materia with its OWN Gradle 8.13** (not this repo's 9.3.1 — Materia's `build.gradle.kts:746` uses the removed `javaexec{}` script API and won't configure under Gradle 9):
   ```bash
   # in the Materia checkout, with JDK 21 and an Android SDK (local.properties sdk.dir=...)
   ./gradlew publishKotlinMultiplatformPublicationToMavenLocal \
             publish<Target>PublicationToMavenLocal \
             -x test --init-script disable-shaders.init.gradle.kts
   ```
   where `disable-shaders.init.gradle.kts` neutralises the Tint/naga-dependent `compileShaders` task (SPIR-V is pre-committed):
   ```kotlin
   allprojects { tasks.matching { it.name == "compileShaders" }.configureEach { enabled = false; actions.clear() } }
   ```
   (Alternatively install `naga-cli`/Tint on the build machine.) Publish to a checked-in flat Maven dir or internal repo for real use, not just `mavenLocal`.
2. **Consume from a Maven repo** (`mavenLocal()` / the flat dir), `implementation("codes.yousef:materia:<ver>")`.
3. **Add LWJGL explicitly** — Materia exposes it runtime-only and without natives: add `org.lwjgl:lwjgl{,-glfw,-vulkan,-shaderc}:3.3.6` for compile access **plus** the per-OS `:natives-*` runtime classifiers.
