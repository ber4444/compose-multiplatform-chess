# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project overview

Compose Multiplatform chess app (Kotlin 2.3.x, Compose Multiplatform 1.10.x) targeting Android, iOS, Linux desktop, macOS desktop, and Web (Wasm). The player plays White; Black is played by Stockfish where available, otherwise by a simple built-in CPU algorithm.

## Commands

```bash
./gradlew test                                  # shared unit tests across targets
./gradlew :app:desktopTest --tests "com.example.myapplication.MoveTest"   # single test class (fastest iteration)
./gradlew :androidApp:assembleDebug :androidApp:installDebug              # build + install Android app
./gradlew :app:run                              # launch desktop app (needs system stockfish installed)
./gradlew :app:wasmJsBrowserDevelopmentRun      # run web target
./gradlew :app:wasmJsBrowserDevelopmentWebpack  # build web dev bundle without dev server
./gradlew :app:connectedAndroidDeviceTest       # Android UI tests (needs device/emulator)
./gradlew :app:iosSimulatorArm64Test            # iOS Compose UI tests
xcodebuild -project iosApp/iosApp.xcodeproj -scheme iosApp -configuration Debug -destination "platform=iOS Simulator,name=iPhone 17" CODE_SIGNING_ALLOWED=NO test # iOS Swift tests
./gradlew :app:desktopTest --tests "*board3d*"  # run 3D desktop tests (Wgpu4kFrameDumpTest writes build/wgpu-frame.png to eyeball)
tools/ios_3d_screenshot.sh                      # screenshot the real iOS 3D board in a booted sim -> build/ios-3d-screenshot.png
```

Verifying the iOS SceneKit 3D *look* can't be done from a unit test — SceneKit needs Metal, which `MTLCreateSystemDefaultDevice()` cannot provide under the headless `simctl spawn` Kotlin/Native test runner. Use `tools/ios_3d_screenshot.sh` instead: it launches the real app with `CHESS_START_3D=1` (read in `MainViewController`) so it opens directly on the 3D board, then captures via `simctl io screenshot`. For the canonical scene-cycler demo (analog of the web three.js spike), use `tools/ios_baseline_demo.sh` with `CHESS_BASELINE_DEMO=1`, which replaces the chess app with a fullscreen SceneKit surface that cycles through `VisualBaselineScenes.ALL` on tap. The konan `IosBoard3DSnapshotTest` is only a CPU-side smoke test (asset load, Core Image cube-map decode, scene build) and gracefully skips the GPU render.

CI (`.github/workflows/android-tests.yml`) builds every target with:

```bash
./gradlew :androidApp:assembleDebug :app:assembleAndroidDeviceTest :app:check :app:desktopJar :app:packageDistributionForCurrentOS :app:wasmJsBrowserDistribution
```

then runs `:app:connectedAndroidDeviceTest` on an API 35 emulator. A change isn't done until those build for all three targets. A second job (`apple`) builds iOS/macOS targets and runs simulator tests.

## Module and source-set structure

Two Gradle modules:

- `:app` — KMP library holding all UI, game rules, and resources. Targets: `android` (via `com.android.kotlin.multiplatform.library` plugin), `jvm("desktop")`, `wasmJs`.
- `:androidApp` — thin Android application wrapper (manifest, launcher icons) that depends on `:app`.

`gradle.properties` sets `kotlin.mpp.applyDefaultHierarchyTemplate=false`, so the source-set hierarchy is manual. The KMP module graph is organized as follows:

```text
commonMain
 ├── jvmCommonMain
 │    ├── androidMain
 │    └── desktopMain
 ├── wasmJsMain
 └── iosMain
```

A custom intermediate source set `jvmCommonMain` sits between `commonMain` and the two JVM-backed targets (`androidMain`, `desktopMain`); it holds process/IO code that can't live in commonMain (Wasm has no `java.lang.Process`). `iosMain` dependsOn commonMain holding `MainViewController`; `iosSimulatorArm64Test` holds Compose UI tests; `iosApp/` Xcode project is generated using XcodeGen (`project.yml` as source of truth — regenerate with `xcodegen generate`).

All code uses package `com.example.myapplication` even though the project is named `game`. Generated compose resources class is `game.app.generated.resources`.

### Expect/Actual Boundaries & Platform Glue Fences

> [!CAUTION]
> **DO NOT TOUCH** platform glue and `actual` implementations unless explicitly instructed.
>
> The 3D actual implementations are finely tuned for each platform's rendering graphics APIs (WebGPU, Filament, SceneKit). Agents will often try to "helpfully" rewrite them to match generic KMP patterns or other platforms' code, which will break the build or rendering pipeline. **Treat these actual implementations as frozen.**

Key `expect/actual` boundaries and platform glue:
- **`Chess3DBoardRenderer` / `Board3DSupport` (3D Renderers)**: Each target has a dedicated implementation relying on platform-specific C/C++/Swift/JS interop. WebGPU is used for desktop and wasm, SceneKit for iOS, and Filament for Android. DO NOT attempt to unify these rendering paths or rewrite the actuals.
- **`StockfishEngine` / `BaseStockfishEngine`**: Each target has a very distinct way of locating, loading, and communicating with Stockfish (vendored binaries on Android, system processes on Desktop, Web Workers on Wasm, ChessKitEngine Swift bridge on iOS). Do not merge or modify these platform-specific bridges.
- **Process and I/O**: Process handling is isolated to `jvmCommonMain` (via `java.lang.Process`). `wasmJsMain` lacks this capability and relies on async JS APIs, while iOS manages it through Swift interop. Do not attempt to move `java.lang.*` usage into `commonMain`.

## Engine architecture

The chess-AI path is the part that spans the most files:

- `ChessEngine` (commonMain) — minimal interface: `suspend getBestMove(fen)` / `suspend evaluate(fen)` / `close()`.
- `UciProtocolClient` (commonMain) — handles async UCI protocol via `UciTransport` (used by Wasm).
- `BaseStockfishEngine` (jvmCommonMain) — blocking UCI process logic wrapped in `withContext(Dispatchers.IO)`; subclasses only implement `resolveExecutablePath()`. Returning `null` means "no binary, use embedded fallback".
- `StockfishEngine` (androidMain) — launches the vendored `libstockfish.so` from the app's `nativeLibraryDir`.
- `DesktopStockfishEngine` (desktopMain) — uses the system-installed `stockfish` binary or Homebrew installs.
- `WasmStockfishEngine` (wasmJsMain) — uses `stockfish-18-lite-single.js` running in a Web Worker.
- Swift `StockfishChessEngine` (iOS) — wrapping ChessKitEngine, async→sync semaphore bridge, NNUE via `setoption EvalFile`/`EvalFileSmall`, injected through `MainViewController(engine:)`.

Black's move flows through `pickMoveStockfish` (Move.kt): game state → FEN (`FenConverter`) → engine → UCI move → app move (`UciMoveConverter`) → `SelectedMove` validated against `getAllLegalMoves`. On any failure (null engine, illegal/unconvertible move) it falls back to `pickMoveCPU` (capture-preferring random, defaults to Queen for promotions). Engines are injected at platform entry points (`MainActivity`, desktop/wasm `Main.kt`) via `viewModel.attachEngine(...)` after an async `start()`.

Stockfish binaries are vendored at `app/src/androidMain/jniLibs/{arm64-v8a,armeabi-v7a}/libstockfish.so` — official `sf_17` builds, pinned because `sf_18` exceeds GitHub's 100 MB file limit. See `docs/Stockfish.md` for packaging rationale (must be in jniLibs, not assets, because app storage isn't executable on modern Android).

## State and UI

`GameViewModel` (commonMain) is a plain class, **not** an androidx ViewModel — it owns its own `CoroutineScope` and exposes `StateFlow`s (`gameState`, `animState`, `viewState`); callers must call `close()`. Game rules are top-level functions in `Move.kt` and `Piece.kt`. Board state in `GameUiState` is parallel lists (`piecesWhite`/`positionsWhite`, etc.) indexed together, along with a `castlingRights` field tracking availability for both colors. Turn alternation is driven by animation completion: `animationEnd()` triggers Black's move after White's animation finishes.

**Recent Features:**
- **Castling:** King moves of 2 squares automatically update the corresponding rook's position and castling rights. `PieceAnimationState` supports a `secondaryPiece` to animate the Rook alongside the King.
- **Pawn Promotion:** Reaching the back rank transitions `gameState` to a `pendingPromotion` state (which displays a `PromotionDialog` UI). Normal moves are blocked until the user selects a piece (or the CPU picks one), which then replaces the Pawn and completes the turn. `SelectedMove` encapsulates both the move coordinates and the optional `PromotionType`.
- **En Passant:** Captured pawns are removed from their original square (not the destination) in `deriveNewGameState`. The `enPassantTarget` state field tracks double pushes, and `FenConverter` correctly emits/parses the en passant FEN field.
- **Draw detection:** Threefold repetition (`positionHistory` of FEN position keys, cleared on irreversible moves), fifty-move rule (real `halfmoveClock`/`fullmoveNumber`, now emitted/parsed by `FenConverter` and sent to Stockfish), and insufficient material — all evaluated in `deriveNewGameState` via `applyDrawConditions` (`DrawConditions.kt`), setting `WinState.DRAW`.
- **Draw agreements:** Players can offer draws to the engine, which accepts or declines based on positional evaluation (`UciEvaluation.kt`) or material fallback. The engine may also proactively offer draws in drawish positions. Supported via new `drawOffer` fields in `GameUiState`.
- **3D Board View:** The `GameScreen` settings row shows a 3D toggle (`viewState.show3D`), but only when a `Board3DSupport` is injected (desktop, web, iOS, and Android entry points pass their respective `Board3DSupport`; unsupported platforms pass `null`, hiding the toggle). Toggling **swaps** the board: exactly one board (2D `chess_board` or 3D `board_3d`) is visible at a time. The 3D view is defined by the `Chess3DBoardRenderer` interface in `commonMain`, injected mirroring `ChessEngine`. `Board3DSceneMapper.fromFen` turns a FEN into a renderer-agnostic `Board3DScene`. Interaction (camera drag, pinch zoom, ray-picked tap-to-move, and piece transitions) uses shared commonMain logic (`OrbitCameraController`, `CameraMath.rayFromScreen`, `BoardRayPicker`, `Board3DSceneDiffer`) so backend cameras stay in sync with the picker.
  - **Desktop backend:** **WebGPU + WGSL** via the Panama FFM binding through wgpu4k. `DesktopWgpuChessRenderer` renders offscreen through a `TextureRenderingContext`/`CAMetalLayer`, then copies into a Compose `ImageBitmap`. Quality is selectable via `CHESS_DESKTOP_QUALITY=HIGH_QUALITY` (`DesktopRendererQualityPreset`): the `HIGH_QUALITY` preset enables 4× MSAA + a real-time 2048² PCF shadow mapping pass + exposure 5.0 (see `WgpuShaders.kt` / `WGPU_DEPTH_SHADER` / `docs/graphics/desktop-renderer-notes.md`). The DEFAULT preset stays at the original 1× MSAA / no-shadows / exposure 4.5 for byte-identical baseline captures.
  - **Web backend:** **three.js** (WebGLRenderer + MeshStandardMaterial + ACESFilmicToneMapping + PCFSoftShadowMap). `ThreeJsChessRenderer` injects three.js + a chess-renderer module (inline ES module via import map) into the page, loads `chess.glb` via three's `GLTFLoader`, and renders into an absolutely positioned overlay `<canvas>`. Kotlin drives camera, FEN, and selection via `@JsFun` interop through the same `Chess3DBoardRenderer` contract. The previous `WebGpuChessRenderer` is preserved but no longer the default. (The Phase B spike matched/exceeded Android's per-frame quality — see `docs/plans/web-graphics-spike-result.md`, verdict: ADOPTED.)
  - **iOS backend:** **three.js via WKWebView** (same renderer as web). `WKWebViewChessRenderer` loads a self-contained esbuild bundle (`chess3d-bundle.js` = three.js r169 + GLTFLoader + RoomEnvironment + chess-renderer code, fully offline, no CDN) alongside `chess.glb` from the app bundle. The WKWebView sits inside a `UIKitView(interactive = false)` so Compose `pointerInput` intercepts touches and the shared `OrbitCameraController` / `BoardRayPicker` pipeline drives the camera via `evaluateJavaScript`. The previous `IosSceneKitChessRenderer` (SceneKit through Kotlin/Native) is preserved as a fallback. RealityKit was evaluated and rejected — ModelIO can't load `.glb`, USDZ conversion loses materials, and even a native Sketchfab `.usdz` rendered as flat gray due to RealityKit's limited `UsdPreviewSurface` support (see `docs/plans/ios-graphics-spike-result.md`).
  - **Android backend:** A Filament renderer via **SceneView** (`io.github.sceneview:sceneview`), the Jetpack-Compose-native Filament wrapper (`AndroidSceneViewChessRenderer`). The renderer is a Compose-observable state holder behind `Chess3DBoardRenderer`; `AndroidBoard3DSurface` hosts SceneView with `SurfaceType.Surface`, papermill IBL/skybox KTX assets, and a transparent Compose overlay that receives the shared gestures because SceneView consumes touches. SceneView's model loader loads the `chess.glb` asset and fixed `ModelNode` pools render the board, pieces, and selected-square highlight. This replaced the original hand-written Filament `Engine`/`Renderer`/`SwapChain` + `SurfaceHolder.Callback` plumbing; Materia and a raw NDK Vulkan renderer were evaluated and rejected (see `docs/plans/issue-32-3d-ui-m3-android.md` and `docs/plans/issue-32-3d-ui-unresolved-questions.md`).

## Build quirks (don't "clean up")

- `app/build.gradle.kts` contains reflection-based workarounds wiring compose resources into Android assets (`...ComposeResourcesToAndroidAssets` task config and the `mergeAndroidDeviceTestAssets` copy hack). These exist so the androidApp module and device tests can see shared compose resources.
- `androidApp/build.gradle.kts` registers `:app`'s generated compose-resource assets dir as its own assets source and adds task dependencies for it.
- `androidApp` uses `jniLibs.useLegacyPackaging = true` so the Stockfish binary is extracted to `nativeLibraryDir` and can be executed.
- iOS framework uses `baseName = "ChessApp"`; `embedAndSignAppleFrameworkForXcode` must stay the first build phase with `ENABLE_USER_SCRIPT_SANDBOXING=NO`; simulator device pinned via `iosSimulatorDeviceId` property.
- Desktop 3D uses Panama FFM through its WebGPU binding. Desktop compile output is JVM 24, while `:app:run` and desktop tests use a scoped JDK 26 toolchain launcher.
- Wasm klib incremental compilation is intentionally disabled in `app/build.gradle.kts`; Kotlin 2.3.x otherwise crashes the klib export-name checker on incremental wasm recompiles.
