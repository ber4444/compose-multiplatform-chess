# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project overview

Compose Multiplatform chess app (Kotlin 2.3.x, Compose Multiplatform 1.10.x) targeting Android, iOS, Linux desktop, macOS desktop, and Web (Wasm). The player plays White; Black is played by Stockfish where available, otherwise by a simple built-in CPU algorithm. Beyond the game there are two other large subsystems: the 3D board (per-platform Filament backends) and five AI coaching surfaces (three on-device, two cloud-routed through the `:server` module) — see [AI features](#ai-features).

## Commands

```bash
./gradlew test                                  # shared unit tests across targets
./gradlew :app:desktopTest --tests "com.example.myapplication.MoveTest"   # single test class (fastest iteration)
./gradlew :chess-core:check :ondeviceai:check :coachapi:check              # core + AI module suites (all targets)
./gradlew :server:test                          # cloud-AI service tests (Testcontainers Postgres; skips without Docker)
./gradlew :evals:run                            # AI grounding/length eval gate; rewrites evals/scorecard.md
./gradlew :chess-core:desktopTest --tests "*Perft*"                        # perft gate (canonical counts + Stockfish cross-check)
CHESS_ENABLE_COACH=1 ./gradlew :app:run         # desktop app WITH the on-device coach/summary (downloads Qwen3-0.6B)
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
./gradlew :chess-core:check :ondeviceai:check :coachapi:check :perft-mcp:test :androidApp:assembleDebug :app:assembleAndroidDeviceTest :app:check :app:desktopJar :app:packageDistributionForCurrentOS :app:wasmJsBrowserDistribution
```

then runs `:app:connectedAndroidDeviceTest` on an API 35 emulator. A change isn't done until that
command passes — it covers Android, desktop, and wasm. A second job (`apple`) builds the iOS/macOS
targets and runs `:app:iosSimulatorArm64Test :ondeviceai:iosSimulatorArm64Test
:coachapi:iosSimulatorArm64Test :app:desktopTest :chess-core:desktopTest` plus the `iosApp` xcodebuild
tests; a nightly job runs the deep perft tier. Touching AI code also triggers
`.github/workflows/ai-coach-evals.yml` (`:evals:run`), which fails on any grounding violation.

## Module and source-set structure

Nine Gradle projects. The five KMP ones carry the app; the rest are JVM-only tooling/services:

> **Gradle path gotcha** — `settings.gradle.kts` includes the two AI modules as **lowercase**
> `:ondeviceai` / `:coachapi` (with `projectDir` overrides to the camelCase directories). CI uses the
> lowercase paths; camelCase (`:onDeviceAi:…`) only works via Gradle's case-insensitive name
> matching. Prefer the lowercase form in scripts and workflows.

- `:chess-core` — the Compose-free, platform-agnostic chess engine core: all game rules, FEN/UCI/SAN/PGN converters, `GameViewModel`, and the pure-Kotlin 3D-board math/scene mapping. Targets: `android`, `jvm("desktop")`, `iosArm64`, `iosSimulatorArm64`, `js(IR)`, `wasmJs`. Published to GitHub Packages as `io.github.ber4444:chess-core` (see `.github/workflows/publish-chess-core.yml`) so the React Native repo `ber4444/react-native-kotlin-multiplatform-chess` can consume it with zero Kotlin duplication. Boundary rules — **no Compose** (no `androidx.compose.*`, no `DrawableResource`, no `@Composable`, no `@Immutable`), **no russhwolf/Settings**, **no `java.lang.Process`**, no platform glue. This is the single source of truth for chess logic.
- `:app` — KMP library holding all UI, platform glue, and resources. Depends on `:chess-core` via `api(project(":chess-core"))`. Targets: `android` (via `com.android.kotlin.multiplatform.library` plugin), `jvm("desktop")`, `iosArm64`, `iosSimulatorArm64`, `wasmJs`.
- `:androidApp` — thin Android application wrapper (manifest, launcher icons) that depends on `:app`.
- `:onDeviceAi` — AI orchestration for all five surfaces (move coach, game summary, rules Q&A, opening explainer, position chat) plus the route policy. On-device generation lives behind its `OnDeviceTextGenerator` seam; the two cloud surfaces keep only their orchestrators here and take an injected client. Targets: `android`, `jvm("desktop")`, `iosArm64`, `iosSimulatorArm64`, `js(IR)`, `wasmJs`. Published to GitHub Packages as `io.github.ber4444:onDeviceAi` alongside `:coachApi` (see `.github/workflows/publish-on-device-ai.yml`) so the React Native repo can consume it. Has `api(project(":coachApi"))` — coachApi types leak into `OpeningExplainer.kt`'s public signatures, so both artifacts move in lockstep under one `on-device-ai-v*` tag.
- `:coachApi` — serialization-only KMP wire models (opening-explain request/response, `PositionChatRequest`, `ChatTurn`, `ChatStreamEvent`) shared by `:onDeviceAi`, `:app`, and the JVM `:server`. Published as `io.github.ber4444:coachApi`. Targets mirror `:onDeviceAi`.
- `:server` — JVM-only Ktor cloud-AI service: `POST /v1/openings/explain` (one-shot) and `POST /v1/positions/chat/stream` (SSE), both over one Postgres+pgvector corpus with ONNX MiniLM embeddings. Deterministic template composers by default; the provider-LLM composers are opt-in via `COACH_LLM_*` env vars and always validated + fallback-guarded. Deployed to Fly.io (see README).
- `:evals` — JVM-only rule-based eval harness; regenerates `evals/scorecard.md` and fails on grounding regression. Gated in CI by `.github/workflows/ai-coach-evals.yml` on changes to `:onDeviceAi`, `:coachApi`, `:server`, `:evals`, or `app/src/**/{opening,chat}/**`.
- `:litert-eval` — JVM-only driver that runs the desktop `LitertLmTextGenerator` from the CLI. Depends on `:ondeviceai` + `:coachapi` only, deliberately **not** `:server` (keeps Ktor from pinning this module's coroutines version — see the `force` block in its `build.gradle.kts`).
- `:perft-mcp` — JVM-only stdio MCP server exposing the perft rig as agent tools. No dependency on `:app`/`:chess-core` (shells out to gradle + stockfish).

`gradle.properties` sets `kotlin.mpp.applyDefaultHierarchyTemplate=false`, so the source-set hierarchy is manual. The KMP module graph is organized as follows:

```text
chess-core commonMain            (pure Kotlin; published as io.github.ber4444:chess-core)

coachApi commonMain              (serialization-only wire models; published as io.github.ber4444:coachApi)

onDeviceAi commonMain            (on-device AI orchestration; published as io.github.ber4444:onDeviceAi)
 ├── androidMain                 (Cactus / llama.cpp)
 ├── desktopMain                 (LiteRT-LM `litertlm-jvm`; gated by `CHESS_ENABLE_COACH=1`)
 ├── iosMain                     (Foundation Models)
 ├── wasmJsMain                  (LiteRT-LM for Web `@litert-lm/core` via module worker; gated by `?coach=1`; WebGPU-only)
 └── jsMain                      (deterministic fallback — the target the RN port consumes)

:app commonMain
 ├── jvmCommonMain
 │    ├── androidMain
 │    └── desktopMain
 ├── wasmJsMain
 └── iosMain
```

A custom intermediate source set `jvmCommonMain` sits between `commonMain` and the two JVM-backed targets (`androidMain`, `desktopMain`); it holds process/IO code that can't live in commonMain (Wasm has no `java.lang.Process`). `iosMain` dependsOn commonMain holding `MainViewController`; `iosSimulatorArm64Test` holds Compose UI tests; `iosApp/` Xcode project is generated using XcodeGen (`project.yml` as source of truth — regenerate with `xcodegen generate`).

All code uses package `com.example.myapplication` even though the project is named `game`. Generated compose resources class is `game.app.generated.resources`.

### chess-core ↔ :app boundary (do not re-couple)

The core was extracted from `:app` with three deliberate seams. **Do not undo them** — they keep the core publishable as a Compose-free artifact:

- **Piece drawables** — the `Piece` interface has **no** `asset` field. `:app` resolves a piece's 2D drawable via the `Piece.asset()` extension in `app/src/commonMain/.../PieceAssets.kt` (it returns a `DrawableResource`). The core must never reference `DrawableResource` / `Res.drawable.*` / `game.app.generated.resources`.
- **`@Immutable`** — was stripped from core types (`GameUiState`, `Piece` classes, `MoveRecord`). It's a Compose stability hint with no runtime effect; `:app`'s Compose compiler re-infers stability (all are immutable-by-construction). Do not re-add `androidx.compose.runtime.Immutable` to core files.
- **Persistence** — `GameViewModel` takes an optional `GameSnapshotSink` (a core interface: `save(GameSnapshot)` / `clear()`), **not** the russhwolf-backed `CurrentGameStore`. `:app` adapts its store via `CurrentGameStore.asSnapshotSink()` (in `persistence/CurrentGameStore.kt`). `GameSnapshot` + `GameSnapshotMapper` live in the core's top-level package so the VM can build snapshots without pulling in platform persistence.

When editing chess logic, ask: *does this need Compose, platform I/O, or russhwolf?* If not, it belongs in `:chess-core`. The `:chess-core` module is the source the RN repo compiles against — re-coupling it breaks that consumer.

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
- **`OnDeviceTextGeneratorFactory` / `defaultRulesQaAnswerer` / `defaultNowMs` (`:onDeviceAi`)**: the AI runtime seam. Each target wires a different LLM stack (Cactus/llama.cpp on Android, Foundation Models via a Swift bridge registry on iOS, `litertlm-jvm` on desktop, `@litert-lm/core` in a module Web Worker on wasm, `UnsupportedTextGenerator`/`null` on JS). These are not interchangeable and their init/warmup/reset sequences are load-bearing (see the Cactus `reset()` note under AI features). Don't unify them or "promote" one to commonMain.
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

- **`AppSettings`** (`commonMain/.../persistence/AppSettings.kt`) — typed, observable view over `Settings`. Plain class (mirrors `GameViewModel` — not androidx ViewModel), constructed at the entry point and threaded into `AppRoot` via `LocalAppSettings` (`staticCompositionLocalOf<AppSettings?>`, nullable so `GameScreen` renders in tests without `AppRoot`). Holds `MutableStateFlow`s seeded from settings + write-through setters. Surface: `board3DEnabled` (default true; drives the 3D surface mount/teardown via a `GameScreen` `LaunchedEffect`), `engineDifficulty` (default `MEDIUM`; bridged to `viewModel.setEngineDifficulty` by an `AppRoot` collector), and `aiCoachEnabled` (default true; bridged to `viewModel.aiCoachEnabled` — the user-facing half of the Move Coach gate, on top of the per-platform build gate). The persisted theme override was removed — theme always follows system dark mode. **Note:** the class KDoc still says "3D toggle + engine difficulty" and predates `aiCoachEnabled`.
- **`CurrentGameStore`** (`commonMain/.../persistence/CurrentGameStore.kt`) — autosave/resume-later. The in-progress game is serialized as a `GameSnapshot` (FEN + small `@Serializable` DTOs for `moveHistory`/`positionHistory`/win/draw fields) under a versioned key `current_game.v1`. Saved on every completed move/draw resolution (explicit `autosave()` calls in `deriveNewGameState`/draw handlers — **not** on transient `selectedSquare` updates); restored at construction via `CurrentGameStoreSupport.loadInitialState` (a finished game starts fresh + clears the stale snapshot). `resetGame()` clears it.
- **`GameHistoryRepository`** (`commonMain/.../persistence/GameHistory.kt`) — the list of finished games (`SavedGame` DTOs: id/result/players/moveCount/pgn), persisted as one JSON blob under `game_history.v1`, exposed as a `StateFlow<List<SavedGame>>` (newest first, capped at 200). `GameActions` builds the PGN (`PgnSerializer`/`PgnTags`) + `SavedGame` from a `GameUiState` at game end.

`PgnSharer` (see the expect/actual section above) is injected alongside, mirroring `Board3DSupport`; the Share button hides when `null`. On Android the `AndroidGameViewModel` holder owns `CurrentGameStore` + `GameHistoryRepository` (survives config changes); `PgnSharer` is built in `onCreate` (needs the host `Activity`).

> **Serialize DTOs, never `GameUiState` directly** — `GameUiState` holds `Piece` objects and `Pair`s that are awkward to serialize and easy to desync from rules. The snapshot round-trips board/clocks/castling/ep/turn through FEN (lossless) + small DTOs for the rest. `GameUiState`'s auto-generated `equals` is identity-based on `Piece` instances (they're plain `class`, not `data class`), so round-trip tests compare via FEN + SAN list, not `equals`.

## Navigation

`AppRoot` (`commonMain/.../AppRoot.kt`) is the single navigation host and the single home for `MyApplicationTheme` (always `darkTheme = isSystemInDarkTheme()` — the per-entry-point theme duplication was removed). It owns a `Screen` enum (`GAME`, `HISTORY`, `SETTINGS`, `RULES`, `CHAT`) in `rememberSaveable` state and a multiplatform `BackHandler` (`androidx.compose.ui.backhandler.BackHandler`, CMP 1.10) that pops to `GAME`. Entry points render `AppRoot(viewModel, settings, board3D, gameHistory, pgnSharer, moveCoachManager, gameSummaryManager, switchTopPadding)` instead of `ChessApp` directly.

`AppRoot` is also the wiring point for the AI surfaces:
- Two `LaunchedEffect` collectors bridge settings → VM: `AppSettings.engineDifficulty` → `viewModel.setEngineDifficulty`, and `AppSettings.aiCoachEnabled` → `viewModel.aiCoachEnabled`.
- It `remember`s the cloud/on-device holders it owns — `OpeningExplainerStateHolder(createOpeningExplainer())`, `ChatViewModel(createPositionChat())`, and `RulesQaStateHolder(...)` over `defaultRulesQaAnswerer(createBundledRuleLookupTool())` — and closes the first two in `DisposableEffect`. The move-coach and game-summary managers are *not* created here: entry points own them (they need the platform runtime + lifecycle) and pass them in, then `AppRoot` publishes them via `LocalMoveCoachManager` / `LocalGameSummaryManager` / `LocalOpeningExplainerStateHolder` so `GameScreen` can read them.

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
  - **Settings & navigation** — `AppRoot` hosts a `Screen` enum (GAME/HISTORY/SETTINGS/RULES/CHAT) + multiplatform `BackHandler`; `SettingsScreen` holds the engine-difficulty selector, the 3D toggle (persisted, default on), and the AI Move Coach toggle (persisted, default on). The persisted theme override was removed (theme follows system dark mode).
- **3D Board View:** The 3D toggle lives in **Settings** (`AppSettings.board3DEnabled`, default on); `GameScreen` observes it and re-runs its entry/teardown frame choreography when it flips. Exactly one board (2D `chess_board` or 3D `board_3d`) is visible at a time. The 3D view is defined by the `Chess3DBoardRenderer` interface in `commonMain`, injected mirroring `ChessEngine`. `Board3DSceneMapper.fromFen` turns a FEN into a renderer-agnostic `Board3DScene`. Interaction (camera drag, pinch zoom, ray-picked tap-to-move, and piece transitions) uses shared commonMain logic (`OrbitCameraController`, `CameraMath.rayFromScreen`, `BoardRayPicker`, `Board3DSceneDiffer`) so backend cameras stay in sync with the picker.
  - **Desktop backend:** **Native C++ Filament**. `DesktopFilamentChessRenderer` delegates shared FEN-to-scene, camera, selection, and transition logic to `FilamentEncodedChessRenderer`, then drives a JNI/CMake bridge (`desktop_filament_bridge`) that renders `chess.glb` with the same fixed Filament instance-pool convention as iOS/web. The native bridge uses a headless Filament swap chain, papermill IBL/blurred skybox KTX assets from common Compose resources, and `Renderer::readPixels` to hand RGBA frames to `ImageBitmapChess3DSurface`. Run `tools/fetch_filament_desktop.sh` after a clean checkout to fetch the gitignored Filament desktop release used by the bridge.
  - **Web backend:** **Filament (Wasm)**. `FilamentWasmChessRenderer` delegates lifecycle, camera, scene, selection, and animation state to `FilamentEncodedChessRenderer`, then its Wasm peer dynamically loads `filament.js` from `unpkg` and renders through WebGL into an absolutely positioned overlay `<canvas>`. The peer queues encoded scene/camera state until the JS renderer is ready. Filament replaced the previous three.js implementation for better rendering consistency across targets.
  - **iOS backend:** **Metal-native Filament**. `FilamentIosChessRenderer` delegates the same shared encoded renderer lifecycle to `FilamentEncodedChessRenderer`, then hosts a Swift `FilamentChessView` (`CAMetalLayer` + `CADisplayLink`) through `UIKitView(interactive = false)` so Compose `pointerInput` intercepts touches and the shared `OrbitCameraController` / `BoardRayPicker` pipeline drives the camera. Swift/Obj-C++ owns the Filament C++ renderer (`FilamentChessRenderer.mm`), while Kotlin owns FEN-to-scene mapping, move arcs, selection bounce, and camera state. The normal `iosApp` Debug/Release configs link Filament through `iosApp/iosApp/Filament/filament.xcconfig`; run `tools/fetch_filament_ios.sh` after a clean checkout to fetch Filament v1.72.0 xcframeworks and stage the KTX IBL assets. The previous iOS three.js/WKWebView path was removed after issue #54; see `docs/plans/ios-filament-spike-result.md`.
  - **Android backend:** A Filament renderer via **SceneView** (`io.github.sceneview:sceneview`), the Jetpack-Compose-native Filament wrapper (`AndroidSceneViewChessRenderer`). The renderer is a Compose-observable state holder behind `Chess3DBoardRenderer`; `AndroidBoard3DSurface` hosts SceneView with `SurfaceType.Surface`, papermill IBL/skybox KTX assets, and a transparent Compose overlay that receives the shared gestures because SceneView consumes touches. SceneView's model loader loads the `chess.glb` asset and fixed `ModelNode` pools render the board, pieces, and selected-square highlight. This replaced the original hand-written Filament `Engine`/`Renderer`/`SwapChain` + `SurfaceHolder.Callback` plumbing; Materia and a raw NDK Vulkan renderer were evaluated and rejected (see `docs/plans/issue-32-3d-ui-m3-android.md` and `docs/plans/issue-32-3d-ui-unresolved-questions.md`).

## AI features

**Five** AI surfaces exist. Before changing any of them, know which one you're in — they share the
`:onDeviceAi` seam but differ in privacy class, runtime, and gating:

| Surface | UI entry | Policy | Orchestrator (`:onDeviceAi`) | `:app` holder |
|---|---|---|---|---|
| Move Coach | panel under the board, after Black's move | `moveCoachOffline` (LOCAL_ONLY, 0¢) | `DefaultAiCoachOrchestrator` | `movecoach/MoveCoachManager` |
| Game Summary | *Get Coach Summary* in the game-over popup | reuses `moveCoachOffline` | `DefaultGameSummaryOrchestrator` | `movecoach/GameSummaryManager` |
| Rules Q&A | **Rules** screen | `rulesQaOffline` (LOCAL_ONLY, 0¢) | `DefaultRulesQaOrchestrator` + `BundledRuleLookupTool` | `rules/RulesQaStateHolder` |
| Opening Explainer | post-game panel | `openingExplainer` (PUBLIC_OR_SYNTHETIC, 0.2¢) | `DefaultOpeningExplainer` | `opening/OpeningExplainerStateHolder` |
| Position Chat | **Chat** screen | `positionChat` (PUBLIC_OR_SYNTHETIC, 0.2¢) | `DefaultPositionChat` | `chat/ChatViewModel` |

Shared plumbing: `AiRoutePolicies` + `AiRoutePolicyDecider` (route selection from an
`AiContextSnapshot`: model presence, network, user setting, thermal state, foreground),
`OnDeviceTextGenerator`/`OnDeviceTextGeneratorFactory` (the `expect` seam every local runtime
implements), `MoveCoachPromptBuilder`/`MoveCoachResponseValidator` (300-char bound, forbidden phrases,
grounding + one retry), `MoveCoachFallback` (deterministic text). `:coachApi` holds the cloud wire
models; `:server` holds the two cloud routes; `:evals` gates grounding regressions in CI.

Cloud is reachable by exactly two policies (`openingExplainer`, `positionChat`) and only with
public/synthetic chess data. The three LOCAL_ONLY policies can never be handed a cloud route —
`AiRoutePolicyDeciderTest` proves it over a 60-context sweep (2 model × 2 network × 3 user-setting ×
5 thermal). **Do not add `allowCloud = true` to a LOCAL_ONLY policy or route local prompts through
`:server`.**

- **On-device AI Move Coach:** A Compose panel (`MoveCoachPanel`) that surfaces a natural-language explanation of Black's move after it animates. Wiring: `GameViewModel` fires its `onMoveCoached` callback after applying the engine's move (skipped when the move ends the game) and exposes an `aiCoachEnabled` flag; `MoveCoachManager` (in `:app`) registers that callback in its `init` and owns the private `triggerCoach(...)` — cancellable, never blocks the move. There is **no** `GameViewModel.triggerCoach`; the VM stays coach-agnostic apart from the callback + flag. Gated twice: the entry point must attach an orchestrator (Android `FLAG_DEBUGGABLE`, desktop `CHESS_ENABLE_COACH=1`, wasm `?coach=1`; iOS attaches when Foundation Models reports available), **and** `AppSettings.aiCoachEnabled` (Settings switch, default on) must be true. Backed by a shared KMP module `:onDeviceAi` holding the routing policy (`AiRoutePolicies.moveCoachOffline`), prompt builder, validator, and deterministic fallback in `commonMain`; platform runtimes are injected at the entry points.
  - **Android backend:** **Cactus (`com.cactuscompute:cactus:1.4.1-beta`)** — llama.cpp CPU runtime. The `gemma3-270m` model (~200 MB GGUF) is downloaded from Hugging Face by Cactus on first launch into `filesDir` (debug APK ~258 MB; no model bundled in the APK). `AndroidManifest.xml` declares `INTERNET` so Cactus can fetch the model. Cold start ~1–2 s. Replaced the earlier LiteRT-LM path (7–9 s cold init, streaming crash, no resolvable Maven coordinate) and the ML Kit Prompt API path (narrow AICore device support); `MoveCoachModelAsset.kt` and `AndroidCoachWiring` were removed in the migration. See `docs/benchmarks/on-device-ai/android-delivery-decision.md`.
  - **iOS backend:** **Foundation Models** (Apple Intelligence) via `FoundationMoveCoachBridge` registered into `FoundationModelsBridgeRegistry` from `iOSApp.swift`. Requires **iOS 26.0+** (every Foundation Models call is `@available(iOS 26.0, *)`-gated; the app's own deployment target is 16.0) plus `SystemLanguageModel.default.availability == .available`. Unlike Android/desktop/wasm there is **no build flag or debug/release distinction** — `MainViewController` probes availability at launch on every build and falls back to rule-based text when unavailable (old OS, ineligible device, or Apple Intelligence off in Settings — all surface identically). iOS has **not** been migrated to Cactus yet — it stays on Foundation Models, though the `:onDeviceAi` KMP module makes that swap feasible later.
  - **Desktop backend:** **LiteRT-LM** (`com.google.ai.edge.litertlm:litertlm-jvm`, Google AI Edge) — the Kotlin/JVM API over LiteRT-LM, with native libs bundled inside the jar (linux-x86_64 / linux-aarch64 / darwin-aarch64 / win-x86_64; **no Intel Mac** — those hosts fall back). The Qwen3-0.6B-int4 model (~347 MB `.litertlm`) is downloaded from Hugging Face on first launch by `LitertLmModelStore` and cached under `~/.chess-coach-models/`. Gated behind `CHESS_ENABLE_COACH=1` (env var) in `Main.kt`, mirroring Android's `FLAG_DEBUGGABLE` gate — without it the coach panel stays `Hidden` (the previous default). Implemented by `LitertLmTextGenerator` in `:onDeviceAi` desktopMain, wired via the same `OnDeviceTextGenerator` seam as Cactus/Foundation Models; the entire `DefaultAiCoachOrchestrator` → `MoveCoachManager` pipeline is reused unchanged. (The literal "LiteRT.js"/prebuilt LiteRT C++ SDK are not LLM runtimes — see `docs/benchmarks/on-device-ai/desktop-wasm-litert-lm.md` for why LiteRT-LM was chosen.)
  - **Wasm backend:** **LiteRT-LM for Web** (`@litert-lm/core`, loaded from the jsdelivr CDN at runtime) running in a **module Web Worker** so inference is off the main thread. The worker script is embedded as a Kotlin string and spawned from a Blob URL (`LitertLmWasmInterop.kt`) — no webpack/resource-packaging changes, mirroring how `FilamentWasmChessRenderer` injects its CDN `<script>`. Uses `gemma-4-E2B-it-web.litertlm` (~2 GB, the only model `@litert-lm/core` officially documents for web), streamed from HF by the LiteRT-LM `Engine.create()` call. Requires **WebGPU** (`navigator.gpu`); on Firefox/Safari `status()` returns `Unavailable` without any network fetch and the orchestrator falls back to `MoveCoachFallback`. Gated behind `?coach=1` on the page URL. Implemented by `LitertLmWasmTextGenerator` in `:onDeviceAi` wasmJsMain.
  - **JS target** (`js(IR){nodejs()}`): still `UnsupportedTextGenerator` — the React Native port has no WebGPU/workers.
  - **Cactus quirk (don't "clean up")**: `CactusTextGenerator` keeps one `CactusLM` warm across moves but calls `activeLm.reset()` in a `finally` after **every** completion. Upstream (cactus-compute/cactus#572) keeps session KV state on the same handle as the model, and reusing it across completions produced a real SIGSEGV in `GemmaModel::build_attention` a few moves into a game. `reset()` clears the native context cheaply (no weight reload) — do not remove it as redundant.
- **Game Summary (on-device):** End-of-game counterpart to the coach. `GameScreen`'s game-over popup renders the **Get Coach Summary** button only when `GameSummaryManager.uiState` is `GameSummaryUiState.Hidden` ("ready, idle") — `Unavailable` (the manager's initial state, and where `attachOrchestrator(null)` puts it) renders nothing. Pressing the button feeds `GameActions.toPgn(...)` to `GameSummaryManager.triggerSummary(pgn)` → `DefaultGameSummaryOrchestrator` (45 s `withTimeoutOrNull` for the whole PGN-sized generation). Reuses `moveCoachOffline` and the *same* `OnDeviceTextGeneratorFactory` instance the coach warmed up, so there's no second model load. Two intentional differences from the coach: **no response validator** (any non-blank text is accepted — there are no per-move tags to ground against), and it's **pull-based** (nothing runs until the button is pressed). Attached alongside the coach at every entry point (Android: same debug-build gate; iOS: same Foundation Models availability probe; desktop/web: same env var/URL param) — where the coach is gated off, `attachOrchestrator` is either called with `null` or never called at all, and both leave/put the manager in `Unavailable`, so the button never appears rather than appearing and silently no-opping. Note it does **not** share the coach's `AppSettings.aiCoachEnabled` Settings switch — that only guards the automatic per-move `MoveCoachManager` callback, so turning the coach off in Settings has no effect on this button. `GameSummaryEvent.Streaming` exists in the model and is rendered by the UI, but `DefaultGameSummaryOrchestrator` currently only emits `Complete`.
- **Rules Q&A (on-device, retrieval):** `RulesQaScreen` + `RulesQaStateHolder` over `DefaultRulesQaOrchestrator`. Corpus is the bundled `onDeviceAi/src/commonMain/resources/rulesCorpus/passages.tsv` (30 passages + header) looked up by BM25 (`BundledRuleLookupTool`). The answerer is an `expect fun defaultRulesQaAnswerer(lookupTool)`: **iOS** = native Foundation Models `Tool` conformance with `NLEmbedding` ranking (same iOS 26+/Apple Intelligence gate as the coach, no separate flag), **Android** = `StructuredOutputRulesQaAnswerer` (model emits `{"tool":"lookup_rule","query":"…"}`, Kotlin performs the lookup, a second turn cites the passage) — but `defaultRulesQaAnswerer` on Android returns `null` unless `isCactusInitialized()` is true, and that flag is only set from the coach's own debug-only `initializeCactus(this)` call, so **release Android never gets a live answerer either**, with no direct `FLAG_DEBUGGABLE` check in this feature's own code. **desktop / wasm / JS return `null`** unconditionally. No Settings switch exists for this feature; `RulesQaStateHolder` starts in `RulesQaUiState.Unavailable` whenever the answerer is `null`, and the screen reports itself unavailable rather than rendering a dead input box.
- **Opening Explainer (cloud):** `OpeningExplainerStateHolder.explain(gameState)` runs only for a finished game and posts FEN + first 20 SAN plies + ECO to `:server`. Client (`opening/KtorOpeningExplainerClient.kt`) is `null` unless a base URL is configured — the URL comes from `generateOpeningExplainerConfig` (`CHESS_COACH_BASE_URL` env → `coach.baseUrl` in `local.properties` → empty). Non-2xx/offline/no-URL all surface as a deterministic offline message, not an error state.
- **Position Chat (cloud, streaming):** `chat/ChatScreen` + `ChatViewModel` + `KtorStreamingChatClient` in `:app`, `DefaultPositionChat` in `:onDeviceAi`, `PositionChatService` + the `POST /v1/positions/chat/stream` route in `:server`. Chat is **cloud-only by design**: there is no on-device chat generator, and a `RunOnDevice` decision is treated as "no route" (fallback event). Shares the explainer's base URL and HTTP engine. Fences worth knowing before editing:
  - The server route writes SSE by hand over `respondBytesWriter`, **not** Ktor's `sse { }` plugin — the plugin's builder is GET-only and chat must POST a body. It also emits `: keep-alive` comment lines and needs Netty's `responseWriteTimeoutSeconds` (60) to stay **above** the heartbeat interval; the 10 s default force-closed chats before the first token.
  - The client's `withTimeout(45_000)` around the stream is deliberate belt-and-braces on top of `HttpTimeout`: a `bodyAsChannel()` read inside `execute{}` has not reliably honoured CIO's socket timeout, which hung the UI with no error.
  - `ChatViewModel` keeps a single `streamJob` (Stop cancels it, which must close the TCP connection) and sends the last 6 turns; the server independently caps history at 12 turns / 20 plies / 500 chars.
  - Validation is server-side on the *accumulated* text at stream end; a veto emits `fallback` with `TemplateChatComposer`'s grounded text. `DefaultPositionChat`'s own fallback event is a fixed offline sentence and is **not** retrieval-grounded — don't conflate the two layers.
- `docs/plans/on-device-coach-rag-unification.md` is a **proposal** — grounding the coach, summary, and chat in a persisted per-ply `MoveAssessment` record (cpLoss/motifs) instead of a reference corpus, plus habit aggregation, difficulty-aware advice, and chat re-scoping. **None of it is implemented.** Don't document or assume it as existing behaviour.
- `docs/plans/hybrid-inference-vendor-adoption-plan.md` outlines the vendor adoption plan for hybrid AI inference.
- `docs/plans/review-fixes-hybrid-inference.md` documents P0 blocking review fixes for the hybrid inference implementation (PR #106) and should be completed before further feature development.

## Build quirks (don't "clean up")

- `app/build.gradle.kts` contains reflection-based workarounds wiring compose resources into Android assets (`...ComposeResourcesToAndroidAssets` task config and the `mergeAndroidDeviceTestAssets` copy hack). These exist so the androidApp module and device tests can see shared compose resources.
- `androidApp/build.gradle.kts` registers `:app`'s generated compose-resource assets dir as its own assets source and adds task dependencies for it.
- `androidApp` uses `jniLibs.useLegacyPackaging = true` so the Stockfish binary is extracted to `nativeLibraryDir` and can be executed.
- iOS framework uses `baseName = "ChessApp"`; `embedAndSignAppleFrameworkForXcode` must stay the first build phase with `ENABLE_USER_SCRIPT_SANDBOXING=NO`; simulator device pinned via `iosSimulatorDeviceId` property. The iOS app target links Filament through `iosApp/iosApp/Filament/filament.xcconfig`, which expects the gitignored `iosApp/iosApp/Filament/filament/` xcframework payload from `tools/fetch_filament_ios.sh`.
- Desktop 3D uses the gitignored Filament desktop payload from `tools/fetch_filament_desktop.sh` plus the CMake-built `desktop_filament_bridge` JNI library. Desktop compile output is JVM 24, while `:app:run` and desktop tests use a scoped JDK 26 toolchain launcher.
- Wasm klib incremental compilation is intentionally disabled in `app/build.gradle.kts`; Kotlin 2.3.x otherwise crashes the klib export-name checker on incremental wasm recompiles.
