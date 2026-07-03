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
./gradlew :app:desktopTest --tests "*board3d*"  # run 3D desktop tests (DesktopRendererSmokeTest writes build/chess3d-*.png to eyeball)
tools/fetch_filament_desktop.sh                 # fetch gitignored desktop Filament headers/libs for native bridge builds
tools/ios_3d_screenshot.sh                      # screenshot the real iOS 3D board in a booted sim -> build/ios-3d-screenshot.png
```

When an Android SDK path is needed, use the Android CLI first: `android info sdk`.
On this machine it currently reports `/Users/presence/Library/Android/sdk`; prefer the CLI result over guessing or hard-coding `ANDROID_HOME`.

Verifying the iOS Filament/Metal 3D *look* can't be done from a unit test — the Metal-backed `UIKitView` needs the real app/simulator rendering stack, which the headless `simctl spawn` Kotlin/Native test runner cannot provide. Use `tools/ios_3d_screenshot.sh` instead: it launches the real app with `CHESS_START_3D=1` (read in `MainViewController`) so it opens directly on the 3D board, then captures via `simctl io screenshot`.

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
> The 3D renderer implementations are finely tuned for each platform's Filament surface path (native C++ desktop, WebGL-backed web, Metal iOS, SceneView Android). Agents will often try to "helpfully" rewrite them to match generic KMP patterns or other platforms' code, which will break the build or rendering pipeline. **Treat these implementations as frozen.**

Key `expect/actual` boundaries and platform glue:
- **`Chess3DBoardRenderer` / `Board3DSupport` (3D Renderers)**: Each target has a dedicated Filament implementation relying on platform-specific C/C++/Swift/JS interop. Desktop uses a native C++ Filament bridge with headless readback; Android uses SceneView; iOS uses Metal through Swift/Obj-C++; Web/Wasm uses WebGL through Filament JS. DO NOT attempt to rewrite the platform glue.
- **`StockfishEngine` / `BaseStockfishEngine`**: Each target has a very distinct way of locating, loading, and communicating with Stockfish (vendored binaries on Android, system processes on Desktop, Web Workers on Wasm, ChessKitEngine Swift bridge on iOS). Do not merge or modify these platform-specific bridges.
- **Process and I/O**: Process handling is isolated to `jvmCommonMain` (via `java.lang.Process`). `wasmJsMain` lacks this capability and relies on async JS APIs, while iOS manages it through Swift interop. Do not attempt to move `java.lang.*` usage into `commonMain`.
- **`createSettings(name)` (`persistence/SettingsFactory.kt`)**: russhwolf `Settings` factory; each target constructs its native K/V backend (`SharedPreferencesSettings` on Android, `PreferencesSettings` on desktop, `StorageSettings` on wasm, `NSUserDefaultsSettings` on iOS). Constructed once at each entry point and shared by `AppSettings`, `CurrentGameStore`, and `GameHistoryRepository`.
- **`PgnSharer` (`share/PgnSharer.kt`)**: platform PGN export — `ACTION_SEND`+`EXTRA_TEXT` (Android), `FileDialog` save (desktop), `UIActivityViewController` (iOS), `Blob`+`<a download>` (wasm). Injected like `Board3DSupport`; `null` hides the Share button.
- **`nowEpochMillis()` / `todayPgnDate()` (`persistence/Clock.kt`)**: tiny per-platform clock so `commonMain` can timestamp saves without adding `kotlinx-datetime`. JVM actual lives in `jvmCommonMain` (covers Android+desktop); wasm uses `@JsFun`; iOS computes the UTC date from the epoch (avoids the finicky `NSTimeZone` K/N mapping).

## Engine architecture

The chess-AI path is the part that spans the most files:

- `ChessEngine` (commonMain) — minimal interface: `suspend getBestMove(fen)` / `suspend evaluate(fen)` / `suspend configure(difficulty)` (defaulted no-op) / `close()`. `configure` applies a persisted `EngineDifficulty` (Easy/Medium/Hard/Max → Stockfish `Skill Level` 0–20 + a per-move `movetime` budget); the CPU fallback ignores it.
- `UciProtocolClient` (commonMain) — handles async UCI protocol via `UciTransport` (used by Wasm). `configure()` sends `setoption name Skill Level value N` (+ `isready` sync) and stores the `movetime` for subsequent `go movetime`.
- `BaseStockfishEngine` (jvmCommonMain) — blocking UCI process logic wrapped in `withContext(Dispatchers.IO)`; subclasses only implement `resolveExecutablePath()`. Returning `null` means "no binary, use embedded fallback". `configure()` sends the same `setoption` and switches the `movetime`.
- `StockfishEngine` (androidMain) — launches the vendored `libstockfish.so` from the app's `nativeLibraryDir`.
- `DesktopStockfishEngine` (desktopMain) — uses the system-installed `stockfish` binary or Homebrew installs.
- `WasmStockfishEngine` (wasmJsMain) — uses `stockfish-18-lite-single.js` running in a Web Worker.
- Swift `StockfishChessEngine` (iOS) — wrapping ChessKitEngine, async→sync semaphore bridge, NNUE via `setoption EvalFile`/`EvalFileSmall`, injected through `MainViewController(engine:filamentFactory:)` alongside the Swift Filament view factory.

Black's move flows through `pickMoveStockfish` (Move.kt): game state → FEN (`FenConverter`) → engine → UCI move → app move (`UciMoveConverter`) → `SelectedMove` validated against `getAllLegalMoves`. On any failure (null engine, illegal/unconvertible move) it falls back to `pickMoveCPU` (capture-preferring random, defaults to Queen for promotions). Engines are injected at platform entry points (`MainActivity`, desktop/wasm `Main.kt`) via `viewModel.attachEngine(...)` after an async `start()`.

Stockfish binaries are vendored at `app/src/androidMain/jniLibs/{arm64-v8a,armeabi-v7a}/libstockfish.so` — official `sf_17` builds, pinned because `sf_18` exceeds GitHub's 100 MB file limit. See `docs/Stockfish.md` for packaging rationale (must be in jniLibs, not assets, because app storage isn't executable on modern Android).

## Persistence & settings

The app persists three things via `multiplatform-settings` (russhwolf) + `kotlinx-serialization`, all constructed over one `createSettings("chess")` backend per entry point:

- **`AppSettings`** (`commonMain/.../persistence/AppSettings.kt`) — typed, observable view over `Settings`. Plain class (mirrors `GameViewModel` — not androidx ViewModel), constructed at the entry point and threaded into `AppRoot` via `LocalAppSettings` (`staticCompositionLocalOf<AppSettings?>`, nullable so `GameScreen` renders in tests without `AppRoot`). Holds `MutableStateFlow`s seeded from settings + write-through setters. Surface: `board3DEnabled` (default true; drives the 3D surface mount/teardown via a `GameScreen` `LaunchedEffect`) and `engineDifficulty` (default `MEDIUM`; bridged to `viewModel.setEngineDifficulty` by an `AppRoot` collector). The persisted theme override was removed — theme always follows system dark mode.
- **`CurrentGameStore`** (`commonMain/.../persistence/CurrentGameStore.kt`) — autosave/resume-later. The in-progress game is serialized as a `GameSnapshot` (FEN + small `@Serializable` DTOs for `moveHistory`/`positionHistory`/win/draw fields) under a versioned key `current_game.v1`. Saved on every completed move/draw resolution (explicit `autosave()` calls in `deriveNewGameState`/draw handlers — **not** on transient `selectedSquare` updates); restored at construction via `CurrentGameStoreSupport.loadInitialState` (a finished game starts fresh + clears the stale snapshot). `resetGame()` clears it.
- **`GameHistoryRepository`** (`commonMain/.../persistence/GameHistory.kt`) — the list of finished games (`SavedGame` DTOs: id/result/players/moveCount/pgn), persisted as one JSON blob under `game_history.v1`, exposed as a `StateFlow<List<SavedGame>>` (newest first, capped at 200). `GameActions` builds the PGN (`PgnSerializer`/`PgnTags`) + `SavedGame` from a `GameUiState` at game end.

`PgnSharer` (see the expect/actual section above) is injected alongside, mirroring `Board3DSupport`; the Share button hides when `null`. On Android the `AndroidGameViewModel` holder owns `CurrentGameStore` + `GameHistoryRepository` (survives config changes); `PgnSharer` is built in `onCreate` (needs the host `Activity`).

> **Serialize DTOs, never `GameUiState` directly** — `GameUiState` holds `Piece` objects and `Pair`s that are awkward to serialize and easy to desync from rules. The snapshot round-trips board/clocks/castling/ep/turn through FEN (lossless) + small DTOs for the rest. `GameUiState`'s auto-generated `equals` is identity-based on `Piece` instances (they're plain `class`, not `data class`), so round-trip tests compare via FEN + SAN list, not `equals`.

## Navigation

`AppRoot` (`commonMain/.../AppRoot.kt`) is the single navigation host and the single home for `MyApplicationTheme` (always `darkTheme = isSystemInDarkTheme()` — the per-entry-point theme duplication was removed). It owns a `Screen` enum (`GAME`, `HISTORY`, `SETTINGS`) in `rememberSaveable` state and a multiplatform `BackHandler` (`androidx.compose.ui.backhandler.BackHandler`, CMP 1.10) that pops to `GAME`. `AppRoot` also bridges `AppSettings.engineDifficulty` → `viewModel.setEngineDifficulty` via a `LaunchedEffect` collector. Entry points render `AppRoot(viewModel, settings, board3D, gameHistory, pgnSharer, switchTopPadding)` instead of `ChessApp` directly.

## State and UI

`GameViewModel` (commonMain) is a plain class, **not** an androidx ViewModel — it owns its own `CoroutineScope` and exposes `StateFlow`s (`gameState`, `animState`, `viewState`); callers must call `close()`. Game rules are top-level functions in `Move.kt` and `Piece.kt`. Board state in `GameUiState` is parallel lists (`piecesWhite`/`positionsWhite`, etc.) indexed together, along with a `castlingRights` field tracking availability for both colors. Turn alternation is driven by animation completion: `animationEnd()` triggers Black's move after White's animation finishes.

**Recent Features:**
- **Castling:** King moves of 2 squares automatically update the corresponding rook's position and castling rights. `PieceAnimationState` supports a `secondaryPiece` to animate the Rook alongside the King.
- **Pawn Promotion:** Reaching the back rank transitions `gameState` to a `pendingPromotion` state (which displays a `PromotionDialog` UI). Normal moves are blocked until the user selects a piece (or the CPU picks one), which then replaces the Pawn and completes the turn. `SelectedMove` encapsulates both the move coordinates and the optional `PromotionType`.
- **En Passant:** Captured pawns are removed from their original square (not the destination) in `deriveNewGameState`. The `enPassantTarget` state field tracks double pushes, and `FenConverter` correctly emits/parses the en passant FEN field.
- **Draw detection:** Threefold repetition (`positionHistory` of FEN position keys, cleared on irreversible moves), fifty-move rule (real `halfmoveClock`/`fullmoveNumber`, now emitted/parsed by `FenConverter` and sent to Stockfish), and insufficient material — all evaluated in `deriveNewGameState` via `applyDrawConditions` (`DrawConditions.kt`), setting `WinState.DRAW`.
- **Draw agreements:** Players can offer draws to the engine, which accepts or declines based on positional evaluation (`UciEvaluation.kt`) or material fallback. The engine may also proactively offer draws in drawish positions. Supported via new `drawOffer` fields in `GameUiState`.
- **Game lifecycle & persistence (issue #39):**
  - **Move history + SAN + PGN** — `deriveNewGameState` appends a `MoveRecord` (UCI + SAN + `fenAfter`) per ply; `SanConverter` builds SAN (with disambiguation/castling/promotion/`+`/`#`); `PgnSerializer` emits the Seven Tag Roster + movetext.
  - **Autosave + resume-later** — the in-progress game is serialized as a `GameSnapshot` (FEN + DTOs) on every move and restored on next launch (`CurrentGameStore`/`CurrentGameStoreSupport`); a finished game clears the snapshot so the app starts fresh.
  - **Game history + save/share** — at game end the popup offers **Save game** (writes a `SavedGame` to `GameHistoryRepository`, double-save guarded) and **Share PGN** (platform `PgnSharer`); the **History** screen lists saved games with a detail view + delete.
  - **Engine difficulty** — a persisted `EngineDifficulty` (Easy/Medium/Hard/Max) weakens/strengthens Stockfish via `setoption name Skill Level` + a per-move `movetime` budget, applied through the additive `ChessEngine.configure`. Selected in **Settings**.
  - **Settings & navigation** — `AppRoot` hosts a `Screen` enum (GAME/HISTORY/SETTINGS) + multiplatform `BackHandler`; `SettingsScreen` holds the engine-difficulty selector and the 3D toggle (persisted, default 3D on). The persisted theme override was removed (theme follows system dark mode).
- **3D Board View:** The 3D toggle lives in **Settings** (`AppSettings.board3DEnabled`, default on); `GameScreen` observes it and re-runs its entry/teardown frame choreography when it flips. Exactly one board (2D `chess_board` or 3D `board_3d`) is visible at a time. The 3D view is defined by the `Chess3DBoardRenderer` interface in `commonMain`, injected mirroring `ChessEngine`. `Board3DSceneMapper.fromFen` turns a FEN into a renderer-agnostic `Board3DScene`. Interaction (camera drag, pinch zoom, ray-picked tap-to-move, and piece transitions) uses shared commonMain logic (`OrbitCameraController`, `CameraMath.rayFromScreen`, `BoardRayPicker`, `Board3DSceneDiffer`) so backend cameras stay in sync with the picker.
  - **Desktop backend:** **Native C++ Filament**. `DesktopFilamentChessRenderer` delegates shared FEN-to-scene, camera, selection, and transition logic to `FilamentEncodedChessRenderer`, then drives a JNI/CMake bridge (`desktop_filament_bridge`) that renders `chess.glb` with the same fixed Filament instance-pool convention as iOS/web. The native bridge uses a headless Filament swap chain, papermill IBL/blurred skybox KTX assets from common Compose resources, and `Renderer::readPixels` to hand RGBA frames to `ImageBitmapChess3DSurface`. Run `tools/fetch_filament_desktop.sh` after a clean checkout to fetch the gitignored Filament desktop release used by the bridge.
  - **Web backend:** **Filament (Wasm)**. `FilamentWasmChessRenderer` delegates lifecycle, camera, scene, selection, and animation state to `FilamentEncodedChessRenderer`, then its Wasm peer dynamically loads `filament.js` from `unpkg` and renders through WebGL into an absolutely positioned overlay `<canvas>`. The peer queues encoded scene/camera state until the JS renderer is ready. Filament replaced the previous three.js implementation for better rendering consistency across targets.
  - **iOS backend:** **Metal-native Filament**. `FilamentIosChessRenderer` delegates the same shared encoded renderer lifecycle to `FilamentEncodedChessRenderer`, then hosts a Swift `FilamentChessView` (`CAMetalLayer` + `CADisplayLink`) through `UIKitView(interactive = false)` so Compose `pointerInput` intercepts touches and the shared `OrbitCameraController` / `BoardRayPicker` pipeline drives the camera. Swift/Obj-C++ owns the Filament C++ renderer (`FilamentChessRenderer.mm`), while Kotlin owns FEN-to-scene mapping, move arcs, selection bounce, and camera state. The normal `iosApp` Debug/Release configs link Filament through `iosApp/iosApp/Filament/filament.xcconfig`; run `tools/fetch_filament_ios.sh` after a clean checkout to fetch Filament v1.72.0 xcframeworks and stage the KTX IBL assets. The previous iOS three.js/WKWebView path was removed after issue #54; see `docs/plans/ios-filament-spike-result.md`.
  - **Android backend:** A Filament renderer via **SceneView** (`io.github.sceneview:sceneview`), the Jetpack-Compose-native Filament wrapper (`AndroidSceneViewChessRenderer`). The renderer is a Compose-observable state holder behind `Chess3DBoardRenderer`; `AndroidBoard3DSurface` hosts SceneView with `SurfaceType.Surface`, papermill IBL/skybox KTX assets, and a transparent Compose overlay that receives the shared gestures because SceneView consumes touches. SceneView's model loader loads the `chess.glb` asset and fixed `ModelNode` pools render the board, pieces, and selected-square highlight. This replaced the original hand-written Filament `Engine`/`Renderer`/`SwapChain` + `SurfaceHolder.Callback` plumbing; Materia and a raw NDK Vulkan renderer were evaluated and rejected (see `docs/plans/issue-32-3d-ui-m3-android.md` and `docs/plans/issue-32-3d-ui-unresolved-questions.md`).

## Build quirks (don't "clean up")

- `app/build.gradle.kts` contains reflection-based workarounds wiring compose resources into Android assets (`...ComposeResourcesToAndroidAssets` task config and the `mergeAndroidDeviceTestAssets` copy hack). These exist so the androidApp module and device tests can see shared compose resources.
- `androidApp/build.gradle.kts` registers `:app`'s generated compose-resource assets dir as its own assets source and adds task dependencies for it.
- `androidApp` uses `jniLibs.useLegacyPackaging = true` so the Stockfish binary is extracted to `nativeLibraryDir` and can be executed.
- iOS framework uses `baseName = "ChessApp"`; `embedAndSignAppleFrameworkForXcode` must stay the first build phase with `ENABLE_USER_SCRIPT_SANDBOXING=NO`; simulator device pinned via `iosSimulatorDeviceId` property. The iOS app target links Filament through `iosApp/iosApp/Filament/filament.xcconfig`, which expects the gitignored `iosApp/iosApp/Filament/filament/` xcframework payload from `tools/fetch_filament_ios.sh`.
- Desktop 3D uses the gitignored Filament desktop payload from `tools/fetch_filament_desktop.sh` plus the CMake-built `desktop_filament_bridge` JNI library. Desktop compile output is JVM 24, while `:app:run` and desktop tests use a scoped JDK 26 toolchain launcher.
- Wasm klib incremental compilation is intentionally disabled in `app/build.gradle.kts`; Kotlin 2.3.x otherwise crashes the klib export-name checker on incremental wasm recompiles.
