# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project overview

Compose Multiplatform chess app (Kotlin 2.3.x, Compose Multiplatform 1.10.x) targeting Android, iOS, Linux desktop, macOS desktop, and Web (Wasm). The player picks a side in Settings (`GameViewModel.playerSide`, default White); the other side — `engineSide` — is played by Stockfish where available, otherwise by a simple built-in CPU algorithm. Beyond the game there are two other large subsystems: the 3D board (per-platform Filament backends) and five AI coaching surfaces (three on-device, two cloud-routed through the `:server` module) — see [AI features](#ai-features).

## Commands

```bash
./gradlew test                                  # shared unit tests across targets
./gradlew :app:desktopTest --tests "com.example.myapplication.MoveTest"   # single test class (fastest iteration)
./gradlew :chess-core:check :ondeviceai:check :coachapi:check              # core + AI module suites (all targets)
./gradlew :server:test                          # cloud-AI service tests (Testcontainers Postgres; skips without Docker)
./gradlew :server:verifyCorpus                  # validates the JSON book theory against structural rules
./gradlew :evals:run                            # AI eval gate (grounding gates; routing/fluency scored); rewrites evals/scorecard.md
EVAL_CALIBRATION=1 ./gradlew :evals:run         # + per-route reading-grade distributions (recalibrating FluencySurface bounds)
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
tools/verify_opening_retrieval.sh               # smoke-test the DEPLOYED opening explainer: 8 openings, asserts returned ECO matches
```

When an Android SDK path is needed, use the Android CLI first: `android info sdk`.
It reports `/Users/presence/Library/Android/sdk` on the macOS host and `/media/presence/SECOND/Android/Sdk` on the Linux one; prefer the CLI result over guessing or hard-coding `ANDROID_HOME`.
Gradle reads `sdk.dir` from the gitignored `local.properties`; if an Android task fails with "SDK location not found", that key is missing or stale (e.g. carrying the other host's path) — fix it there rather than exporting `ANDROID_HOME` per invocation.

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
`.github/workflows/ai-coach-evals.yml`, which runs `:server:test` (Testcontainers Postgres) and
`:evals:run`, and fails on any grounding violation. **`:server:test` runs only there** — the main
workflow never references `:server:`, so a server change that skips this workflow's path filter is
untested. The job also fails if `OpeningRetrievalGroundingTest` *skipped* rather than ran: it is
`@Testcontainers(disabledWithoutDocker = true)`, so a runner without Docker would otherwise go
green with the retrieval gate never executing.

## Module and source-set structure

Ten Gradle projects. The five KMP ones carry the app; the rest are JVM-only tooling/services, plus one Android test-only module (`:macrobenchmark`):

> **Gradle path gotcha** — `settings.gradle.kts` includes the two AI modules as **lowercase**
> `:ondeviceai` / `:coachapi` (with `projectDir` overrides to the camelCase directories). CI uses the
> lowercase paths; camelCase (`:onDeviceAi:…`) only works via Gradle's case-insensitive name
> matching. Prefer the lowercase form in scripts and workflows.

- `:chess-core` — the Compose-free, platform-agnostic chess engine core: all game rules, FEN/UCI/SAN/PGN converters, `GameViewModel`, the per-move assessment layer (`MoveAssessment`/`MoveAssessor`/`MotifDetector` + `movecoach/DeterministicCoach`), and the pure-Kotlin 3D-board math/scene mapping. Targets: `android`, `jvm("desktop")`, `iosArm64`, `iosSimulatorArm64`, `js(IR)`, `wasmJs`. Published to GitHub Packages as `io.github.ber4444:chess-core` (see `.github/workflows/publish-chess-core.yml`) so the React Native repo `ber4444/react-native-kotlin-multiplatform-chess` can consume it with zero Kotlin duplication. Boundary rules — **no Compose** (no `androidx.compose.*`, no `DrawableResource`, no `@Composable`, no `@Immutable`), **no russhwolf/Settings**, **no `java.lang.Process`**, no platform glue. This is the single source of truth for chess logic.
- `:app` — KMP library holding all UI, platform glue, and resources. Depends on `:chess-core` via `api(project(":chess-core"))`. Targets: `android` (via `com.android.kotlin.multiplatform.library` plugin), `jvm("desktop")`, `iosArm64`, `iosSimulatorArm64`, `wasmJs`.
- `:androidApp` — thin Android application wrapper (manifest, launcher icons) that depends on `:app`.
- `:onDeviceAi` — AI orchestration for all five surfaces (move coach, game summary, rules Q&A, opening explainer, position chat) plus the route policy. On-device generation lives behind its `OnDeviceTextGenerator` seam; the two cloud surfaces keep only their orchestrators here and take an injected client. Targets: `android`, `jvm("desktop")`, `iosArm64`, `iosSimulatorArm64`, `js(IR)`, `wasmJs`. Published to GitHub Packages as `io.github.ber4444:onDeviceAi` alongside `:coachApi` (see `.github/workflows/publish-on-device-ai.yml`) so the React Native repo can consume it. Has `api(project(":coachApi"))` — coachApi types leak into `OpeningExplainer.kt`'s public signatures, so both artifacts move in lockstep under one `on-device-ai-v*` tag.
- `:coachApi` — serialization-only KMP wire models (opening-explain request/response, `PositionChatRequest`, `ChatTurn`, `ChatStreamEvent`) shared by `:onDeviceAi`, `:app`, and the JVM `:server`. Published as `io.github.ber4444:coachApi`. Targets mirror `:onDeviceAi`.
- `:server` — JVM-only Ktor cloud-AI service: `POST /v1/openings/explain` (one-shot) and `POST /v1/positions/chat/stream` (SSE), both over one in-process corpus with ONNX MiniLM embeddings. **There is no database in the request path.** Deterministic template composers by default; the provider-LLM composers are opt-in via `COACH_LLM_*` env vars and always validated + fallback-guarded. Deployed to Fly.io (see README). **`server/openapi.yaml` is hand-written on purpose** — a spec generated from the routing tree cannot detect server drift, because it *is* the server. `OpenApiContractTest` validates real responses against it; keep the spec independent of the code it checks. **Retrieval is book-first, not embedding-first** — see [Cloud retrieval](#cloud-retrieval).
  - **Chat Diagnostics:** The chat stream emits a client-visible `Diagnostics` event (carrying `CloudDiagnostics`) immediately before terminating with `Done` or `Fallback`. For debugging, `COACH_DIAGNOSTICS_RAW=1` includes the full unparsed model output (capped at 4000 chars) in this event. The `finishReason` differentiates success (`completed`), validation vetoes (`validation_rejected`, or `provider_empty` if the model produced no text), and HTTP errors (`provider_failed`).
  - **Lazy Retrieval:** The chat route's `retrieve()` runs lazily within the SSE flow; a failure here terminates the stream by emitting a terminal `error` event rather than failing the initial POST. (The cause used to be a database timeout; retrieval is in-process now, so what remains is an embedder failure.)
- `:evals` — JVM-only rule-based eval harness; regenerates `evals/scorecard.md` and fails on grounding regression. Also scores two non-gating columns: `FluencyScorer` (Flesch-Kincaid + three tone rules, bounds calibrated **per surface** against that surface's own deterministic composer — see `docs/benchmarks/on-device-ai/fluency-calibration.md`; the grades are ordinal, not real US grade levels) and `RouterEvalSuite` (the `route-selection` row: four named invariants swept over the full context grid, with a mutation test that injects a broken decider and asserts the sweep goes red). `DeviceRunScorer` (`./gradlew :evals:scoreDeviceRun -Pfile=…`) ingests an `AndroidBenchRunner` `results.jsonl` so a phone run is scored by *this* scorer rather than a second one written next to the data; it rebuilds each row's `MoveCoachRequest` from the facts the row recorded and **cross-checks every verdict against the device's own validator result** — a non-empty `disagreements` list means the JSONL schema and the runner have drifted. Deliberately **not** a `scorecard.md` row: every row there re-runs in CI, and this one needs a specific phone and ~13 minutes. **Routing eval scaffolding lives here, never in `:onDeviceAi`** — that module is published to the RN consumer, so eval types there become public API. Gated in CI by `.github/workflows/ai-coach-evals.yml` on changes to `:onDeviceAi`, `:coachApi`, `:server`, `:evals`, or `app/src/**/{opening,chat}/**`.
- `:litert-eval` — JVM-only driver that runs the desktop `LitertLmTextGenerator` from the CLI. Depends on `:ondeviceai` + `:coachapi` only, deliberately **not** `:server` (keeps Ktor from pinning this module's coroutines version — see the `force` block in its `build.gradle.kts`).
- `:perft-mcp` — JVM-only stdio MCP server exposing the perft rig as agent tools. No dependency on `:app`/`:chess-core` (shells out to gradle + stockfish).
- `:macrobenchmark` — `com.android.test` module holding `IdlePowerBenchmark`, which counts `Choreographer#doFrame` slices (plus power rails where the device has ODPM channels) over a 10 s window with the 3D board visible and untouched. It targets `:androidApp`'s **`benchmark`** build type — non-debuggable and profileable, so the numbers come from a release-shaped build rather than a debug one — and is **not** in any CI workflow: it needs a physical device, and an emulator's frame rate measures the host. Run it with `./gradlew :macrobenchmark:connectedBenchmarkAndroidTest`; `tools/fetch_perfetto.sh` fetches `trace_processor_shell` for querying the resulting traces. `FrameTimingMetric` and `startActivityAndWait` do **not** work against this app (SceneView draws through Filament's own threads into a `SurfaceView`, so there are no HWUI RenderThread frames at all) — see the class KDoc before adding either back.

`gradle.properties` sets `kotlin.mpp.applyDefaultHierarchyTemplate=false`, so the source-set hierarchy is manual. The KMP module graph is organized as follows:

```text
chess-core commonMain            (pure Kotlin; published as io.github.ber4444:chess-core)

coachApi commonMain              (serialization-only wire models; published as io.github.ber4444:coachApi)

onDeviceAi commonMain            (on-device AI orchestration; published as io.github.ber4444:onDeviceAi)
 ├── androidMain                 (ML Kit/AICore — Gemini Nano available on Pixel 10-class devices, but not yet attached in the app)
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

A custom intermediate source set `jvmCommonMain` sits between `commonMain` and the two JVM-backed targets (`androidMain`, `desktopMain`); it holds process/IO code that can't live in commonMain (Wasm has no `java.lang.Process`). A second one, `storeMain`, sits between `commonMain` and the two *store* targets (`androidMain`, `iosMain`) and holds anything depending on the RevenueCat SDK — see [Monetization seam](#monetization-seam). `iosMain` dependsOn commonMain holding `MainViewController`; `iosSimulatorArm64Test` holds Compose UI tests; `iosApp/` Xcode project is generated using XcodeGen (`project.yml` as source of truth — regenerate with `xcodegen generate`).

Application ID is `io.github.ber4444.chess` (renamed for store eligibility in §0.3). All Kotlin source code uses package `com.example.myapplication` even though the project is named `game`. Generated compose resources class is `game.app.generated.resources`.

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
- **`VendorRouteExecutor` / `probeAvailableLocalVendors()` / `defaultRulesQaAnswerer` / `defaultNowMs` (`:onDeviceAi`)**: the AI runtime seam. Each target wires a different LLM stack (ML Kit/AICore on Android — reaches Gemini Nano on Pixel 10-class devices, but no app entry point calls `probeAvailableLocalVendors()` yet, so it is live only under the bench — Foundation Models via a Swift bridge registry on iOS, `litertlm-jvm` on desktop, `@litert-lm/core` in a module Web Worker on wasm, nothing on JS). These are not interchangeable and their init/warmup/reset sequences are load-bearing (see the Cactus `reset()` note under AI features). Don't unify them or "promote" one to commonMain. **The two `actual`s have strictly separated jobs and must keep them** — `probeAvailableLocalVendors()` reports *what this device can run* as data, and `VendorRouteExecutor.execute(route)` builds *the one generator it is handed*. Neither reads an `AiRoutePolicy`. All routing lives in `AiRoutePolicyDecider` (commonMain) — see [AI routing](#ai-routing).
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

The engine's move (`engineSide` — Black unless the player switched sides) flows through `pickMoveStockfish` (Move.kt): game state → FEN (`FenConverter`) → engine → UCI move → app move (`UciMoveConverter`) → `SelectedMove` validated against `getAllLegalMoves`. On any failure (null engine, illegal/unconvertible move) it falls back to `pickMoveCPU` (capture-preferring random, defaults to Queen for promotions). Engines are injected at platform entry points (`MainActivity`, desktop/wasm `Main.kt`) via `viewModel.attachEngine(...)` after an async `start()`.

Stockfish binaries are vendored at `app/src/androidMain/jniLibs/{arm64-v8a,armeabi-v7a}/libstockfish.so` — official `sf_17` builds, pinned because `sf_18` exceeds GitHub's 100 MB file limit. See `docs/Stockfish.md` for packaging rationale (must be in jniLibs, not assets, because app storage isn't executable on modern Android).

## Persistence & settings

The app persists three things via `multiplatform-settings` (russhwolf) + `kotlinx-serialization`, all constructed over one `createSettings("chess")` backend per entry point:

- **`AppSettings`** (`commonMain/.../persistence/AppSettings.kt`) — typed, observable view over `Settings`. Plain class (mirrors `GameViewModel` — not androidx ViewModel), constructed at the entry point and threaded into `AppRoot` via `LocalAppSettings` (`staticCompositionLocalOf<AppSettings?>`, nullable so `GameScreen` renders in tests without `AppRoot`). Holds `MutableStateFlow`s seeded from settings + write-through setters. Surface: `board3DEnabled` (default true; drives the 3D surface mount/teardown via a `GameScreen` `LaunchedEffect`), `engineDifficulty` (default `MEDIUM`; bridged to `viewModel.setEngineDifficulty` by an `AppRoot` collector), `aiCoachEnabled` (default true; bridged to `viewModel.aiCoachEnabled` — the user-facing half of the Move Coach gate, on top of the per-platform build gate), and `playerSide` (`StateFlow<String>`, read by `SettingsScreen`). Plus one plain (non-flow) value: `proUnlocked` — a **storeless-targets-only** seed/sink for `NoOpEntitlements` on desktop and wasm. Never read it on Android/iOS: entitlement state there comes from RevenueCat, and a device-writable settings key would be a trivial paywall bypass. The persisted theme override was removed — theme always follows system dark mode.
- **`CurrentGameStore`** (`commonMain/.../persistence/CurrentGameStore.kt`) — autosave/resume-later. The in-progress game is serialized as a `GameSnapshot` (FEN + small `@Serializable` DTOs for `moveHistory`/`positionHistory`/win/draw fields) under a versioned key `current_game.v1`. Saved on every completed move/draw resolution (explicit `autosave()` calls in `deriveNewGameState`/draw handlers — **not** on transient `selectedSquare` updates); restored at construction via `CurrentGameStoreSupport.loadInitialState` (a finished game starts fresh + clears the stale snapshot). `resetGame()` clears it.
- **`GameHistoryRepository`** (`commonMain/.../persistence/GameHistory.kt`) — the list of finished games (`SavedGame` DTOs: id/result/players/moveCount/pgn), persisted as one JSON blob under `game_history.v1`, exposed as a `StateFlow<List<SavedGame>>` (newest first, capped at 200). `GameActions` builds the PGN (`PgnSerializer`/`PgnTags`) + `SavedGame` from a `GameUiState` at game end.

`PgnSharer` (see the expect/actual section above) is injected alongside, mirroring `Board3DSupport`; the Share button hides when `null`. On Android the `AndroidGameViewModel` holder owns `CurrentGameStore` + `GameHistoryRepository` (survives config changes); `PgnSharer` is built in `onCreate` (needs the host `Activity`).

> **Serialize DTOs, never `GameUiState` directly** — `GameUiState` holds `Piece` objects and `Pair`s that are awkward to serialize and easy to desync from rules. The snapshot round-trips board/clocks/castling/ep/turn through FEN (lossless) + small DTOs for the rest. `GameUiState`'s auto-generated `equals` is identity-based on `Piece` instances (they're plain `class`, not `data class`), so round-trip tests compare via FEN + SAN list, not `equals`.

## Navigation

`AppRoot` (`commonMain/.../AppRoot.kt`) is the single navigation host and the single home for `MyApplicationTheme` (always `darkTheme = isSystemInDarkTheme()` — the per-entry-point theme duplication was removed). It owns a `Screen` enum (`GAME`, `HISTORY`, `SETTINGS`, `RULES`, `CHAT`, `PAYWALL`) in `rememberSaveable` state and a multiplatform `BackHandler` (`androidx.compose.ui.backhandler.BackHandler`, CMP 1.10) that pops to `GAME`. Entry points render `AppRoot(viewModel, settings, board3D, gameHistory, pgnSharer, moveCoachManager, gameSummaryManager, entitlements, switchTopPadding)` instead of `ChessApp` directly. `entitlements` defaults to `UnconfiguredEntitlements()` — see [Monetization seam](#monetization-seam); `PAYWALL` hosts `PaywallScreen` and is reached from any upsell card's *See Pro* button.

`AppRoot` is also the wiring point for the AI surfaces:
- Two `LaunchedEffect` collectors bridge settings → VM: `AppSettings.engineDifficulty` → `viewModel.setEngineDifficulty`, and `AppSettings.aiCoachEnabled` → `viewModel.aiCoachEnabled`.
- It `remember`s the cloud/on-device holders it owns — `OpeningExplainerStateHolder(createOpeningExplainer())`, `ChatViewModel(createPositionChat())`, and `RulesQaStateHolder(...)` over `defaultRulesQaAnswerer(createBundledRuleLookupTool())` — and closes the first two in `DisposableEffect`. The move-coach and game-summary managers are *not* created here: entry points own them (they need the platform runtime + lifecycle) and pass them in, then `AppRoot` publishes them via `LocalMoveCoachManager` / `LocalGameSummaryManager` / `LocalOpeningExplainerStateHolder` so `GameScreen` can read them.

## Monetization seam

Entitlement gating lives in `:app`'s `monetization/` package and is **injected like `PgnSharer` /
`Board3DSupport`**, never resolved statically. `:chess-core` must stay free of any billing
dependency — it's the artifact the React Native repo compiles against.

- **`Entitlements`** — interface: `isProUnlocked: StateFlow<Boolean>`, `availablePlans()`,
  `purchase(planId)`, `restorePurchases()`. Published to the UI through `LocalEntitlements`
  (`staticCompositionLocalOf<Entitlements?>`, nullable, mirroring `LocalAppSettings`). `ProPlan` /
  `PurchaseOutcome` are store-agnostic on purpose — a leaked RevenueCat type in `commonMain` breaks
  desktop and wasm at dependency resolution.
- **Three implementations, and the split is load-bearing. All three start locked** — every target
  shows the paywall, so its layout is checkable at phone, desktop *and* browser window sizes:
  - `NoOpEntitlements` — desktop and wasm. No store on those targets (`purchases-kmp-core` has no
    JVM/wasm variant), so it offers **one synthetic plan priced "Free"** and `purchase()` unlocks
    locally. Non-empty plans are deliberate: an empty list drives the paywall's "purchases aren't
    available" copy, which would lock a storeless user out permanently. Entry points seed it from
    `AppSettings.proUnlocked` and pass `AppSettings::setProUnlocked` as `onUnlockChanged`, so the
    free unlock survives a restart instead of re-showing the paywall every launch.
  - `UnconfiguredEntitlements` — the `AppRoot` **default**. Locked, `purchase()` returns
    `Unavailable`. Keep it as the default: previews, Compose UI tests, and any caller that omits the
    argument must not configure a billing SDK or hit the network. Do **not** "simplify" it to
    `NoOpEntitlements` — that one's `purchase()` flips the flag and returns `Purchased`, handing out
    Pro for a tap on exactly the two targets where money is meant to change hands.
  - `RevenueCatEntitlements` — Android and iOS, injected by `MainActivity` / `MainViewController`
    via `createOrNull(apiKey, debugLogging)`, which returns `null` on a blank key so an unkeyed
    clone lands on the locked default instead of failing. Call `refresh()` from the entry point;
    it is deliberately not fired from `init {}` (that ran a network call on the composition thread).
- **Gating is live, and the `available` flag is the part that's easy to get wrong.** `ProGate`
  (`monetization/ProGate.kt`) wraps Game Summary and Opening Explainer in `GameScreen`; `AppRoot`
  branches on `isProUnlocked()` for the Rules and Chat screens (they own their own
  `SubScreenScaffold`, so nesting would double the title bar); `MoveCoachManager.proUnlocked`
  renders the deterministic line for free users rather than an upsell mid-game.
  - `ProGate(available = false)` renders **nothing — not even the upsell**. Selling a feature that
    stays dead after payment is the paywall bug that earns a refund and a one-star review, so every
    call site passes a real availability signal: `summaryState !is GameSummaryUiState.Unavailable`
    (no coach orchestrator attached), `cloudCoachConfigured` (no `coach.baseUrl` → both cloud
    surfaces can only emit their offline sentence), and `rulesQaAnswerer != null` (desktop/wasm/JS
    return `null` unconditionally). Availability is a property of the **build**, not the
    entitlement — `LocalEntitlements` cannot know it, so it must stay an argument.
  - `isProUnlocked()` treats a `null` `LocalEntitlements` as **unrestricted**, which is right for
    previews but means **no Compose UI test can catch a paywall regression** — they never install a
    provider, so `:app:connectedAndroidDeviceTest` always sees everything unlocked. Paywall
    behaviour is hand-test-only; see `EntitlementsTest` for the parts that are unit-testable.
  - The locked branch is `ProUpsellCard` (the standalone half of `ProGate`, for the screens that own
    their own scaffold); its *See Pro* button routes to `Screen.PAYWALL` → `PaywallScreen`, which
    lists the Pro surfaces, renders one row per `ProPlan` (pre-selecting `isBestValue`), and offers
    *Unlock Pro* + *Restore purchases*. It keeps **loading**, **empty plans** ("purchases aren't
    available on this device right now") and **already-active** as distinct states, and reads
    `LocalEntitlements` directly rather than through `isProUnlocked()` — that helper's null-means-
    unrestricted rule would have the paywall claim Pro is active in a preview.
  - **The feature list obeys the same `available` rule as `ProGate`, line by line.**
    `PaywallScreen.proFeatures()` takes each of the five bullets from the signal that gates its
    surface — `MoveCoachManager.hasOrchestrator`, `GameSummaryManager.uiState !is Unavailable`,
    `cloudCoachConfigured` (twice), and a `rulesQaAvailable` argument `AppRoot` fills from its own
    `rulesQaAnswerer != null`. Only the coach line used to be keyed, so a plain desktop run
    advertised four features and unlocked none, and an iOS build without Apple Intelligence sold a
    Game Summary the game-over popup then hid. When **every** line drops out, the purchase button
    goes with it (Restore stays — it moves no money, and an existing subscriber may be on that
    build), which is why exercising the desktop/wasm purchase layout needs `coach.baseUrl` or
    `CHESS_ENABLE_COACH=1`.
  - **`PaywallScreen` is its own `Surface` and applies `WindowInsets.safeDrawing` itself.** It is the
    one destination with no `SubScreenScaffold`, so nothing else supplies a background, a content
    colour, or a status-bar inset: without them the title sat under the iOS clock and the dark
    scheme's pale-lavender button rendered on the platform's white window, which reads as a
    washed-out button rather than as a missing background.
- **The RevenueCat coordinate and source set are both non-obvious:**
  - The artifact is `com.revenuecat.purchases:purchases-kmp-core`. A bare `purchases-kmp` is **not
    published at any version** — that coordinate 404s on Maven Central.
  - It publishes only `android` / `iosArm64` / `iosSimulatorArm64` / `iosX64` variants, so it lives
    in `:app`'s **`storeMain`** intermediate source set (`commonMain` ← `storeMain` ← `androidMain`
    + `iosMain`), never `commonMain`. In `commonMain` it breaks `:app:compileKotlinDesktop` at
    dependency resolution, before any Kotlin is compiled.
  - The iOS **test** binary needs an explicit `-L` to this host's Swift toolchain (see the
    `linkerOpts` on `iosSimulatorArm64`). `purchases-kmp-core` ships RevenueCat's *prebuilt* Swift
    objects whose embedded `LC_LINKER_OPTION` points at the Xcode path on RevenueCat's build
    machine; that path doesn't exist locally, so `ld` skips it and `swiftCompatibility56` /
    `swiftCompatibilityConcurrency` go undefined. The app *framework* link does not catch this —
    it defers symbols to Xcode — so the failure only appears in `:app:iosSimulatorArm64Test`.
  - Keys come from `REVENUECAT_ANDROID_KEY` / `REVENUECAT_IOS_KEY` env → `revenuecat.androidKey` /
    `revenuecat.iosKey` in `local.properties` → empty, generated into `storeMain` by
    `generateRevenueCatConfig` (mirrors `generateOpeningExplainerConfig`). No key is committed.
  - **Four keys, not two.** `REVENUECAT_ANDROID_TEST_KEY` / `REVENUECAT_IOS_TEST_KEY` (→
    `revenuecat.androidTestKey` / `revenuecat.iosTestKey`) hold the dashboard's `test_…` Test Store
    keys. `revenueCatApiKey(debug)` — an `expect fun`, not a val — picks them: **debug** prefers the
    test key and falls back to the production key when none is set (so a single-key setup keeps
    working); **release never resolves to a test key at all**, because shipping one would give every
    user a free unverifiable "purchase". The debug signal is `FLAG_DEBUGGABLE` on Android and
    `Platform.isDebugBinary` on iOS, and it drives `debugLogging` in the same call.
  - **A Test Store purchase cannot be restored after a reinstall, and that is not a bug.** Restore
    re-reads the *store account's* transactions (App Store / Play) and syncs them onto this install's
    App User ID; the Test Store has no Apple/Google account behind it. Nothing calls
    `Purchases.logIn`, and `PurchasesConfiguration(apiKey)` takes no `appUserID`, so every install is
    a fresh anonymous `$RCAnonymousID:…` and the store account is the only link back to the previous
    one — which the `test_…` key does not have. Debug builds prefer the test key, so this is the
    default state when testing restore on a phone. Validate restore in the Play/App Store sandbox, or
    give the app a stable app user id. `restorePurchases()` returns a **`RestoreOutcome`**, not a
    `Boolean`, for this reason: `NothingToRestore` / `Unavailable` / `Failed(message)` used to
    collapse into one "No previous purchase found." on the paywall, which is what made an expected
    limitation read as a broken button. `Failed` also leaves `isProUnlocked` alone — a failed lookup
    is not a signal that the user lost their subscription, the same rule `refresh()` follows. A
    `test_` key is detected at construction (`isTestStore`), so an empty Test Store restore explains
    itself rather than returning `NothingToRestore` and blaming a store account that isn't involved;
    the paywall names that account ("Google Play account" / "App Store account" via
    `isAndroidPlatform`) because **the app has no accounts of its own** and "this store account" sent
    the reader hunting for an app login that doesn't exist. Do not "fix" restore by deriving an
    `appUserId` from a device identifier: it makes restore device-scoped (no new phone, no second
    device), `identifierForVendor` resets on exactly the reinstall being tested, and `ANDROID_ID`
    differs between the debug and release signing keys.
- Tiering (§0.4 of the Shipaton plan): free keeps unlimited play, both boards, full engine
  difficulty, the coach (deterministic on every platform), PGN export and history; Pro adds
  Game Summary, Position Chat, Opening Explainer and Rules Q&A.

## Cloud retrieval

**The corpus lives in the image, not in Postgres.** `BuildCorpusIndexMain` embeds the checked-in
TSVs at Docker build time into `corpus-index.bin` (3,807 rows, ~7.5 MB); `InMemoryPassageRepository`
serves every request from it. `PostgresPassageRepository` is still here and still the reference
implementation — `SeedMain`, `schema.sql`, `verifyCorpus` and the Testcontainers suites all remain —
but nothing at runtime opens a connection, and `DATABASE_URL` is no longer a runtime variable.

Three things follow, and the first is the one that bites:

- **The two repositories must return identical results, and only one gate proves it.**
  `OpeningRetrievalGroundingTest`'s parity case runs both over the same corpus and probes and
  compares resolved ECO *and passage order* (the composers quote the first passage's first
  sentence, so order is meaning). That test is `disabledWithoutDocker`, so it contributes nothing on
  a runner without Docker — which is why `InMemoryRetrievalGroundingTest` exists to pin the shipping
  repository's eight golden openings everywhere. Both draw their probes from `RetrievalProbes`;
  don't inline a copy into either.
- **Embeddings are computed at build time on purpose.** Doing it at boot would add ~3,800 MiniLM
  forward passes to every cold start, and `fly.toml` sets `min_machines_running = 0`. If you change
  what `SeedMain` embeds, change `BuildCorpusIndexMain` identically or the index and the database
  stop being interchangeable and the parity gate goes red.
- **`BOOK_LIMIT` is shared** (`CorpusIndex.kt`) rather than duplicated per repository, for the same
  no-drift reason.

Retrieval itself is **book-first**, in both implementations. Three fences:

- **An opening is identified by its move prefix, not by vector similarity.**
  `PostgresPassageRepository.retrieve` runs three tiers: exact longest-prefix match on the
  normalized SAN sequence (`MoveSequence` + the `moves` column), then ECO-scoped vector neighbours,
  then plain vector. Embedding-only retrieval was measured returning the wrong opening on **8 of 8**
  real openings against the live deployment (Sicilian → C43 Urusov Gambit, French → E06 Catalan,
  Ruy Lopez → D05 Rubinstein) — and every wrong answer was fluent, cited, and validator-approved, so
  nothing downstream could notice. Book-first retrieval scores 8/8 correct on the same probe.
  `OpeningRetrievalGroundingTest` pins those openings with all-zero embeddings, so it can only pass
  through the book tier.
- **ECO is a filter argument, never query prose.** `OpeningQueryBuilder`/`PositionChatQueryBuilder`
  deliberately do **not** concatenate the ECO code into the embedded text: one token in a 384-d
  MiniLM vector is outvoted, and measurably harmful (a request carrying `eco = "C00"` retrieved
  E00/E06). Both clients send `eco = null` regardless, so the *server's* `RetrievalResult.resolvedEco`
  is the only ECO that ever reaches a composer — don't drop it when editing either route.
- **Passage text must lead with a claim, not a restatement.** Both composers quote the top passage's
  *first sentence*, so `SeedMain` prefixes each lichess row with `EcoNarrator.characterize(eco)`.
  Without it the leading sentence is "X is classified as ECO Y", and even perfect retrieval returns
  a tautology. `EcoNarratorTest` fails if any ECO in the corpus lacks a characterization or if one
  exceeds the 125-char window `TemplateComposer.sentence` truncates at.

The `eco`/`moves` columns are added by `schema.sql` but stay `NULL` until `SeedMain` reseeds; tier 3
covers that case, so applying the schema without reseeding degrades to exactly the old behaviour
rather than returning nothing. **A schema change here is not live until the corpus is reseeded.**
That caveat is seed-path only — the baked index always carries both keys, and a corpus change is not
live in production until the *image* is rebuilt.

**Hosting.** The service is stateless, pinned to `shared-cpu-1x`/512 MB in `fly.toml`'s `[[vm]]`, and
scales to zero (`auto_stop_machines = "suspend"`). 512 MB is measured — peak RSS 304 MB over 120
requests, only ~23 MB of it heap, the rest ONNX Runtime holding MiniLM natively — so `JAVA_OPTS` and
the `[[vm]]` memory in `fly.toml` have to move together. `main` builds **one** `RetrievalRuntime`
shared by the opening and chat surfaces; they used to construct an `OnnxMiniLmEmbedder` each, which
meant two `OrtSession`s and two copies of the same ~90 MB model. See the README's *Running costs*.

### Why the provider LLM "failed" (four causes, none of them the model)

`LlmComposer` fell back on 100% of requests, which read as a quality verdict and was not one. Each
cause is now pinned by a test; all four are worth knowing before touching this path again:

1. **Move numbers counted as sentence boundaries.** `OpeningExplanationValidator` requires 2–3
   sentences, and split on `(?<=[.!?])\s+` — so `2. Ke2` and `1...c5` each read as a sentence end
   and a compliant three-sentence answer was rejected as five or seven. On a chess corpus this
   rejected *every* well-formed answer. `splitSentences` now masks a period preceded by a digit;
   `SentenceCountingTest` replays the verbatim live outputs that were wrongly rejected.
2. **`ChatMessage.content` was non-null.** A reasoning model that spends its budget deliberating
   returns `{"role":"assistant"}` with no `content` key — `MissingFieldException`, swallowed by
   `runCatching{}.getOrNull()` and reported as an ordinary fallback.
3. **`MAX_OUTPUT_TOKENS` was 90**, derived from the 300-*character* output cap — the wrong quantity.
   `LlmChatComposer` had already learned this (2048, with a comment) and the opening route never got
   the fix. Both now share `DEFAULT_MAX_OUTPUT_TOKENS`, pinned equal by a test.
4. **Nothing distinguished the causes.** `ComposeAttempt` now reports budget-rejected /
   provider-error / provider-empty / validator-rejected / accepted, `rejectionReason` names the
   failing rule, and both are logged in production (`opening-provider-failed`,
   `opening-validation-rejected`) and summarized into the `local-llm-compose` scorecard row.

**A bare fallback *rate* is not a finding.** It conflates "never called", "called and errored", and
"wrote something the validator refused" — and here the true cause was in the validator all along.

## Citation sanitization

`ui/CitationSanitizer` strips internal retrieval ids (`[lichess-…]` from `:server`'s corpus,
`[board-goal]`-style ids from the bundled rules corpus) at **every `:app` display path**: Move
Coach, Game Summary, Opening Explainer, and Position Chat — the last both streamed and final.

Three things to know before editing it:

- **`[move-N]` is preserved on purpose.** Those are RAG-2's evaluative-summary citations, and B16
  turns them into tappable board jumps. Widening the regex to eat them deletes the affordance.
- **Streaming needs `sanitizeStreaming`, not `sanitize`.** It additionally drops a trailing
  unterminated `[…`, because mid-stream the closing bracket hasn't arrived and the tag would
  otherwise render character-by-character before vanishing on `done`.
- **Game Summary is the highest-risk consumer** — it runs with no response validator at all, so the
  sanitizer is the only thing between raw model output and the user.

## State and UI

`GameViewModel` (commonMain) is a plain class, **not** an androidx ViewModel — it owns its own `CoroutineScope` and exposes `StateFlow`s (`gameState`, `animState`, `viewState`, `hintText`); callers must call `close()`. Game rules are top-level functions in `Move.kt` and `Piece.kt`. Board state in `GameUiState` is parallel lists (`piecesWhite`/`positionsWhite`, etc.) indexed together, along with a `castlingRights` field tracking availability for both colors. Turn alternation is driven by animation completion: `animationEnd()` triggers the engine's move after the player's animation finishes, and starts `runIdleAnalysis` when control returns to the player.

**Recent Features:**
- **Castling:** King moves of 2 squares automatically update the corresponding rook's position and castling rights. `PieceAnimationState` supports a `secondaryPiece` to animate the Rook alongside the King.
- **Pawn Promotion:** Reaching the back rank transitions `gameState` to a `pendingPromotion` state (which displays a `PromotionDialog` UI). Normal moves are blocked until the user selects a piece (or the CPU picks one), which then replaces the Pawn and completes the turn. `SelectedMove` encapsulates both the move coordinates and the optional `PromotionType`.
- **En Passant:** Captured pawns are removed from their original square (not the destination) in `deriveNewGameState`. The `enPassantTarget` state field tracks double pushes, and `FenConverter` correctly emits/parses the en passant FEN field.
- **Draw detection:** Threefold repetition (`positionHistory` of FEN position keys, cleared on irreversible moves), fifty-move rule (real `halfmoveClock`/`fullmoveNumber`, now emitted/parsed by `FenConverter` and sent to Stockfish), and insufficient material — all evaluated in `deriveNewGameState` via `applyDrawConditions` (`DrawConditions.kt`), setting `WinState.DRAW`.
- **Draw agreements:** The engine proactively offers draws in drawish positions (`tryEngineDrawOffer`, judged by positional evaluation in `UciEvaluation.kt` or a material fallback); `DrawOfferDialog` lets the player accept or decline. Supported via the `drawOffer` fields in `GameUiState`. **There is no player-initiated draw offer in the UI** — the *Offer Draw* button was removed. `GameViewModel.requestDrawOffer`/`offerDraw`/`canOfferDraw` are deliberately kept: `:chess-core` is published to the React Native consumer, so removing them is a source break, and the accept/decline half of the flow shares the same state fields.
- **Per-move assessment:** every ply carries a `MoveAssessment` (`cpBefore`/`cpPlayed`/`cpBest`, `cpLoss`, a `MoveClass` from BEST/EXCELLENT/GOOD/INACCURACY/MISTAKE/BLUNDER, and `motifs`) on its `MoveRecord`, computed by `MoveAssessor` + `MotifDetector` in `:chess-core`. `animationEnd()` starts `runIdleAnalysis` on a cancellable `analysisJob` once the engine's reply has animated and control is back with the player. It finds the most recent unassessed **player** move, evaluates the position before it (`evaluatePositionCp` under `engineStrengthMutex`), detects motifs, rewrites the record, autosaves, and then fires `onMoveCoached`. This is what grounds the coach and the summary — **code detects, the model only narrates**; `DeterministicCoach` turns the record into the headline/explanation floor. Two consequences: the `MoveClass` thresholds (10/30/60/100/300 cp) are calibrated to *Stockfish's* scale, so swapping engines invalidates stored assessments, and no engine means no assessments at all. `GameHistoryBackfiller` (constructed at every entry point once an engine attaches) fills them in for games saved before the feature existed.
- **Hint:** `GameViewModel.requestHint()` / `hintText` / `clearHint()` — asks the attached engine for the best move and formats it as SAN ("Hint: Try Nf3"). No LLM, no network. The button is hidden with no engine attached (the CPU fallback's capture-preferring random move would be confidently wrong), and the query always runs at `EngineDifficulty.HARD`. **Two fences:** the difficulty restore runs in `withContext(NonCancellable)` — `requestHint()` cancels the previous `hintJob`, and `ChessEngine.configure` is suspending, so a plain `finally` threw at its first suspension point and left the opponent at Skill Level 20 for the rest of the game; and `engineStrengthMutex` serializes hint vs. `runIdleAnalysis`, since Skill Level is process-wide UCI state that `BaseStockfishEngine`'s per-exchange mutex does not cover.
- **Game lifecycle & persistence (issue #39):**
  - **Move history + SAN + PGN** — `deriveNewGameState` appends a `MoveRecord` (UCI + SAN + `fenAfter`) per ply; `SanConverter` builds SAN (with disambiguation/castling/promotion/`+`/`#`); `PgnSerializer` emits the Seven Tag Roster + movetext.
  - **Autosave + resume-later** — the in-progress game is serialized as a `GameSnapshot` (FEN + DTOs) on every move and restored on next launch (`CurrentGameStore`/`CurrentGameStoreSupport`); a finished game clears the snapshot so the app starts fresh.
  - **Game history + save/share** — at game end the popup offers **Save game** (writes a `SavedGame` to `GameHistoryRepository`, double-save guarded) and **Share PGN** (platform `PgnSharer`); the **History** screen lists saved games with a detail view + delete.
  - **Engine difficulty** — a persisted `EngineDifficulty` (Easy/Medium/Hard/Max) weakens/strengthens Stockfish via `setoption name Skill Level` + a per-move `movetime` budget, applied through the additive `ChessEngine.configure`. Selected in **Settings**.
  - **Settings & navigation** — `AppRoot` hosts a `Screen` enum (GAME/HISTORY/SETTINGS/RULES/CHAT/PAYWALL) + multiplatform `BackHandler`; `SettingsScreen` holds four persisted controls: the engine-difficulty selector, the 3D toggle (default on), the AI Move Coach toggle (default on), and the player-side selector (`AppSettings.playerSide` — picking Black makes the engine open). The persisted theme override was removed (theme follows system dark mode).
- **3D Board View:** The 3D toggle lives in **Settings** (`AppSettings.board3DEnabled`, default on); `GameScreen` observes it and re-runs its entry/teardown frame choreography when it flips. Exactly one board (2D `chess_board` or 3D `board_3d`) is visible at a time. The 3D view is defined by the `Chess3DBoardRenderer` interface in `commonMain`, injected mirroring `ChessEngine`. `Board3DSceneMapper.fromFen` turns a FEN into a renderer-agnostic `Board3DScene`. Interaction (camera drag, pinch zoom, ray-picked tap-to-move, and piece transitions) uses shared commonMain logic (`OrbitCameraController`, `CameraMath.rayFromScreen`, `BoardRayPicker`, `Board3DSceneDiffer`) so backend cameras stay in sync with the picker.
  - **Desktop backend:** **Native C++ Filament**. `DesktopFilamentChessRenderer` delegates shared FEN-to-scene, camera, selection, and transition logic to `FilamentEncodedChessRenderer`, then drives a JNI/CMake bridge (`desktop_filament_bridge`) that renders `chess.glb` with the same fixed Filament instance-pool convention as iOS/web. The native bridge uses a headless Filament swap chain, papermill IBL/blurred skybox KTX assets from common Compose resources, and `Renderer::readPixels` to hand RGBA frames to `ImageBitmapChess3DSurface`. Run `tools/fetch_filament_desktop.sh` after a clean checkout to fetch the gitignored Filament desktop release used by the bridge.
  - **Web backend:** **Filament (Wasm)**. `FilamentWasmChessRenderer` delegates lifecycle, camera, scene, selection, and animation state to `FilamentEncodedChessRenderer`, then its Wasm peer dynamically loads `filament.js` from `unpkg` and renders through WebGL into an absolutely positioned overlay `<canvas>`. The peer queues encoded scene/camera state until the JS renderer is ready. Filament replaced the previous three.js implementation for better rendering consistency across targets.
    - **The idle board does not draw here either.** The glue's `render()` used to call `requestAnimationFrame` unconditionally, so an untouched board redrew at the display refresh rate for as long as the page was open. It now schedules a frame only while `chess3dFrameLoopGate.shouldRender` holds, fed from Kotlin by the same `FilamentChessPeer.setRenderingActive` the iOS backend uses — **"a frame was published recently"**, never "an animation is running", for the reason the Android note gives. Measured on an M4 with the board idle and the same forced-paint sequence in both arms: **0 draws with the gate against 16 with it pinned open.**
      - **The gate's second term is what makes the board appear at all, and it is a count, not iOS's boolean.** Async init (`Filament.init`, then `loadResources`) routinely outlives the driver's ~48 ms dirty window — the Kotlin peer polls readiness every 100 ms — so the queued scene reaches the glue with `wantsRender` already false; parking on that alone renders nothing, ever. iOS clears the equivalent flag when `beginFrame` reports a frame landed, and **that is not available on the web**: `filament.js` exposes `render(swapChain, view)` as `void`, and substituting the `beginFrame`/`renderView`/`endFrame` triple would drop the `Engine::execute()` that the binding's `render()` also performs and that is not exported at all (`jsbindings.cpp`). So the loop owes `DRAW_SETTLE_FRAMES` attempts after each push instead of one confirmed frame.
      - **`!assetReady` is not a texture-upload fence here.** Android's is, because SceneView finalizes uploads inside its frame loop; `filament.js` decodes on its own `setInterval`, so parking mid-load is safe. The term earns its place only by keeping the loop alive across init so the first frame after the asset lands needs no further push.
      - `dispose()` now stops the loop and `loopActive` keeps it stopped until the next `init()`. The old `render()` rescheduled unconditionally, so **every 2D↔3D toggle left another live rAF loop drawing against a destroyed Engine** for the life of the page.
      - `FrameLoopGateTest` (wasmJsTest) injects the real glue into the karma browser and pins the decision plus its wiring. It never calls `init()`: software WebGL cannot bring Filament up (`Engine.create` crashes), which is exactly why the decision is a separate pure `chess3dFrameLoopGate`. A frame actually landing needs a real GPU — verify with `./gradlew :app:wasmJsBrowserDevelopmentRun`.
  - **iOS backend:** **Metal-native Filament**. `FilamentIosChessRenderer` delegates the same shared encoded renderer lifecycle to `FilamentEncodedChessRenderer`, then hosts a Swift `FilamentChessView` (`CAMetalLayer` + `CADisplayLink`) through `UIKitView(interactive = false)` so Compose `pointerInput` intercepts touches and the shared `OrbitCameraController` / `BoardRayPicker` pipeline drives the camera. Swift/Obj-C++ owns the Filament C++ renderer (`FilamentChessRenderer.mm`), while Kotlin owns FEN-to-scene mapping, move arcs, selection bounce, and camera state. The normal `iosApp` Debug/Release configs link Filament through `iosApp/iosApp/Filament/filament.xcconfig`; run `tools/fetch_filament_ios.sh` after a clean checkout to fetch Filament v1.72.0 xcframeworks and stage the KTX IBL assets. The previous iOS three.js/WKWebView path was removed after issue #54; see `docs/plans/ios-filament-spike-result.md`.
    - **The idle board does not draw here either.** `FilamentChessView` parks its `CADisplayLink` (`isPaused`) once the board is settled; without it an untouched board re-rendered at up to 120 Hz for as long as it was on screen — measured on an iPhone 17 simulator at **1.96 s of process CPU per 10 s idle window, against 0.02 s with the gate**. `CADisableMinimumFrameDurationOnPhone` in `Info.plist` and the 30–120 Hz `preferredFrameRateRange` stay: they are right for the animating case, and what was wrong was running the loop at all with nothing to draw.
      - The signal comes from Kotlin, not from the view: `Board3DAnimationDriver`'s dirty flag → `FilamentChessPeer.setRenderingActive` → `FilamentChessNativeView.setRenderingActive`. It is **"a frame was published recently"**, never "an animation is running", for the reason the Android note gives — and `FilamentEncodedChessRenderer` additionally calls `driver.markDirty()` on `SetCamera`/`Resize`, since a camera drag publishes no scene and a loop parked on the scene signal alone would sit out the whole drag.
      - `FrameLoopGate` (in `FilamentChessView.swift`, pinned by `FrameLoopGateTests`) ORs that signal with two backstops, and both are load-bearing. The view is created before it has a size, so the renderer — and the display link — is built later from `layoutSubviews`, by which time the driver's dirty window may already have closed on a scene queued in `pendingScene`; starting the link parked there never draws the board at all. And `Renderer::beginFrame` declines frames, so `-render` returns `BOOL` and only a frame that actually landed clears the "undrawn state" flag. (The web backend needs the first backstop for the same reason and cannot have the second — see its note above.)
      - The asset-load fence is satisfied by construction rather than by polling: `-loadGlb` uses gltfio's **synchronous** `ResourceLoader::loadResources` ("blocks until all textures have been decoded") and `-initWithMetalLayer:` returns nil if it fails, so a renderer that exists can draw a fully textured board. `-isAssetReady` is still a real query in the gate because switching to `asyncBeginLoad` would make it briefly false — and would then also need `asyncUpdateLoad()` driven from `-render`, which is exactly the shape Android needs because SceneView finalizes those uploads inside its own frame loop.
  - **Android backend:** A Filament renderer via **SceneView** (`io.github.sceneview:sceneview`), the Jetpack-Compose-native Filament wrapper (`AndroidSceneViewChessRenderer`). The renderer is a Compose-observable state holder behind `Chess3DBoardRenderer`; `AndroidBoard3DSurface` hosts SceneView with `SurfaceType.Surface`, papermill IBL/skybox KTX assets, and a transparent Compose overlay that receives the shared gestures because SceneView consumes touches. SceneView's model loader loads the `chess.glb` asset and fixed `ModelNode` pools render the board, pieces, and selected-square highlight. This replaced the original hand-written Filament `Engine`/`Renderer`/`SwapChain` + `SurfaceHolder.Callback` plumbing; Materia and a raw NDK Vulkan renderer were evaluated and rejected (see `docs/plans/issue-32-3d-ui-m3-android.md` and `docs/plans/issue-32-3d-ui-unresolved-questions.md`).
    - **The idle board does not draw.** `SceneView(isRendering = …)` (sceneview **>= 4.30.0**, the release carrying sceneview/sceneview#3109) parks the frame loop; SceneView otherwise awaits `withFrameNanos` unconditionally and an untouched 3D board redraws at the panel refresh rate forever — 120 fps on a Galaxy Z Fold3, 60 fps and ~1.76 cores on a Pixel 7a, GPU rail 10.36 J per 10 s idle window. Three fences: the signal is **"was a frame published recently"**, not "is an animation running" — `Board3DAnimationDriver` publishes without animating on mount, on a new game, on a coach highlight landing on an idle board, and after async init, and the narrower signal strands all four undrawn (at mount, the board never draws at all); both operands must be **Compose state in both directions**, since a deadline compared against a clock latches true and invalidates nothing; and the gate is held off until `ModelLoader.progress == 1f`, because SceneView finalizes the GLB's async texture uploads *inside* the frame loop and a board that parks mid-upload renders untextured. `Board3DAnimationDriverTest` pins the four non-looping publish paths; `:macrobenchmark`'s `IdlePowerBenchmark` measures the frame count over a 10 s idle window.
    - **The camera is applied in SceneView's `onFrame`, not only in a `SideEffect` — and that is a consequence of the gate above.** SceneView's own body carries an unkeyed `SideEffect { … cameraNode.setView(view) … }`, and `CameraNode.setView()` calls `updateProjection()`, which recomputes the projection from its **28 mm lens default (vertical FOV 46.4°)**. `AndroidBoard3DSurface`'s own projection is recorded *first* (a parent composes before its child) and side effects run in record order, so SceneView's reset wins on every recomposition of its body. That is not most frames — a published scene only invalidates the content lambda — it is exactly the frames where the **`isRendering` argument flips**, i.e. every wake of a parked board. A move survives it (its animation publishes ~60 more frames, each re-running our SideEffect); a **one-shot publish onto an idle board — the Hint button's highlight squares, or a coach highlight — draws its whole dirty window at 46.4° and then parks there**. Measured on a portrait phone, where the boosted FOV is ~104°: pressing Hint changed 80.8% of the screen (board cropped to ~4 files) before the fix and 0.3% after. `onFrame` runs inside `SceneRenderer.renderFrame`'s `onBeforeRender`, after every side effect and immediately before `beginFrame`, so it is ordering-proof; keep its lambda `remember`ed and reading `cameraParams` at frame time, since a fresh lambda per recomposition re-recomposes SceneView's body (and re-triggers the reset) every frame. Not covered by CI — the one device test that brings up a real Filament surface is `@Ignore`d because it takes the swiftshader emulator offline.

## AI features

**Five** AI surfaces exist. Before changing any of them, know which one you're in — they share the
`:onDeviceAi` seam but differ in privacy class, runtime, and gating:

| Surface | UI entry | Policy | Orchestrator (`:onDeviceAi`) | `:app` holder |
|---|---|---|---|---|
| Move Coach | panel under the board, on **your** last move once it is assessed | `moveCoachOffline` (LOCAL_ONLY, 0¢) | `DefaultAiCoachOrchestrator` | `movecoach/MoveCoachManager` |
| Game Summary | *Get Coach Summary* in the game-over popup | reuses `moveCoachOffline` | `DefaultGameSummaryOrchestrator` | `movecoach/GameSummaryManager` |
| Rules Q&A | **Rules** screen | `rulesQaOffline` (LOCAL_ONLY, 0¢) | `DefaultRulesQaOrchestrator` + `BundledRuleLookupTool` | `rules/RulesQaStateHolder` |
| Opening Explainer | post-game panel | `openingExplainer` (PUBLIC_OR_SYNTHETIC, 0.2¢) | `DefaultOpeningExplainer` | `opening/OpeningExplainerStateHolder` |
| Position Chat | **Chat** screen | `positionChat` (PUBLIC_OR_SYNTHETIC, 0.2¢) | `DefaultPositionChat` | `chat/ChatViewModel` |

Shared plumbing: `AiRoutePolicies` + `AiRoutePolicyDecider` (see [AI routing](#ai-routing)),
`OnDeviceTextGenerator`/`VendorRouteExecutor` (the `expect` seam every local runtime implements),
`MoveCoachPromptBuilder`/`MoveCoachResponseValidator` (300-char bound, forbidden phrases, grounding),
`MoveCoachFallback` (deterministic text). `:coachApi` holds the cloud wire models; `:server` holds
the two cloud routes; `:evals` gates grounding regressions in CI (routing and fluency are scored but do **not** yet fail the build).

> **No validation retry.** The retry loop and `MoveCoachPromptBuilder.buildRetry` were completely removed; a
> validation failure emits `MoveCoachFallback` immediately. `DefaultAiCoachOrchestratorTest` pins
> `generateCount == 1`.
>
> **Structured output: there isn't any, on any surface.** KSP schema generation (`genai-schema-compiler`) was dropped deliberately, and the `Generable`/`Guide` expect/actual annotations that replaced it in `:onDeviceAi` have **zero annotation sites** — nothing in the repo is annotated with either. The move coach asks for prose and gets prose: `MoveCoachPromptBuilder` requests no schema, and `DefaultAiCoachOrchestrator.runGeneration` is `stripCodeFence(rawText).trim()` handed straight to `MoveCoachResponseValidator`. No JSON is decoded anywhere on that path — the `kotlinx.serialization.json.Json` and `decodeFromString` imports in that file are dead, and `stripCodeFence`'s own KDoc records the history ("named for JSON originally, and kept when the prompt stopped asking for JSON at all").
>
> One consequence is live and easy to miss: because the raw text goes to the validator unparsed, a model that *did* emit `{"headline": …, "explanation": …}` would have the whole blob accepted and rendered verbatim in the panel. `DefaultAiCoachOrchestratorTest`'s "success path" case currently pins exactly that — it feeds a JSON payload, asserts `Success`, and never asserts the resulting text.

### Fallback states (B17)

`AiRoutePolicyDecider.FallbackReason` is a **sealed** class — the routing/validation/quota/timeout
taxonomy, with `Other(message)` for free-form causes. It reaches the UI *typed*; do not flatten it
to `reason.description` at the manager boundary, which is what previously made every fallback render
identically. `:app`'s `movecoach/FallbackPresentation` is the single place it becomes a product
state, and there are only three:

- **`Silent`** — no-model, offline, no-route, backgrounded, validator veto, and **CRITICAL thermal**.
  The deterministic line is the product, so it renders as ordinary coach text with no error chrome.
  This is the "never render a dead panel on CRITICAL thermal" guarantee; it holds only because the
  orchestrator's fallback text is always non-blank (pinned by `DefaultAiCoachOrchestratorTest`).
- **`Labeled`** — quota only. Waiting changes the outcome, so the user is told.
- **`Retryable`** — timeout only. `MoveCoachManager.retry()` / `GameSummaryManager.retry()` replay
  the last request.

Two things follow. Adding a `FallbackReason` case is a compile error in `FallbackPresentation.of`
until someone decides which state it maps to — that is the point of B17. And a validator's `Invalid`
reason stays a plain `String`: it names *which rule broke* for the log line, and every rejection maps
to the one product state `FallbackReason.Validation`. Don't type it.

Note the bench runners (`AndroidBenchRunner`, `IosBenchRunner`) serialize `reason.description`, whose
strings are unchanged from the old constants — the JSONL that `docs/benchmarks/on-device-ai/` consumes
must stay stable.

### AI routing

`AiRoutePolicyDecider.decide(policy, context)` in commonMain is the **single** routing authority.
Three fences, all load-bearing:

1. **The decision carries its route.** `Decision.RunOnDevice(route: VendorRoute)` — there is no
   second step in which a route can be chosen, so no platform code can disagree with the policy.
   `decide()` is called **once** per request, by the orchestrator; the route it returns is what gets
   handed to `VendorRouteExecutor.execute(route)`. Don't reintroduce a `resolveVendorRoute`, and
   don't let an `actual` read an `AiRoutePolicy`.
2. **Capability is data, not an `expect`.** `AiContextSnapshot.availableLocalVendors` is a
   `List<VendorRoute>` filled by `probeAvailableLocalVendors()` at the entry point,
   most-preferred-first (Android now offers ML Kit alone, and it reports `Unavailable` on every
   device tested, so the list is normally empty and the coach runs deterministically). `isDeviceModelAvailable` is a derived getter over it — **do not** add it back as a
   stored field, or the two can drift.
3. **Cloud-capable vendors are filtered by policy.** `VendorRoute.isCloudCapable` plus
   `AiRoutePolicy.permitsCloud()` (the one cloud predicate; `decide()` is its only caller) mean a
   `LOCAL_ONLY` policy can never be handed a cloud vendor even if a probe offers one.

Because capability is an argument, the whole grid is asserted from `commonTest` on every target:
`AiRoutePolicyDeciderTest` sweeps 3 vendor lists × 2 network × 3 user-setting × 5 thermal and
asserts concrete routes, plus the invariant that a `LOCAL_ONLY` `RunOnDevice` route is never
`isCloudCapable`. **Do not add `allowCloud = true` to a LOCAL_ONLY policy or route local prompts
through `:server`.**

The `allowLocal = false` flag on `openingExplainer` and `positionChat` policies is **load-bearing**: it ensures cloud-first policies never route to an on-device generator even when a capable local model exists. This provides a structural, type-safe guarantee for the cloud route that is checked by `AiRoutePolicyDecider` and pinned by the 90-context invariant test.

- **On-device AI Move Coach:** A Compose panel (`MoveCoachPanel`) that explains **the player's own move**, grounded in the `MoveAssessment` recorded for that ply. Wiring: `GameViewModel.runIdleAnalysis` fires `onMoveCoached(fenBefore, moveRecord)` once it has assessed that move (skipped when the game is over) and the VM exposes an `aiCoachEnabled` flag; `MoveCoachManager` (in `:app`) registers that callback in its `init` and owns the private `triggerCoach(...)` — cancellable, never blocks the move. There is **no** `GameViewModel.triggerCoach`; the VM stays coach-agnostic apart from the callback + flag. The request carries `deterministicHeadline`/`deterministicExplanation` from `DeterministicCoach` plus the ply's code-detected facts (`moveClassName`, `centipawnLoss`, `motifs`), and the headline is never model-authored — `DefaultAiCoachOrchestrator.success` uses `deterministicHeadline` whatever the model returns. **The prompt is no longer a rewrite task and the two halves must keep agreeing on that:** a prompt holding one finished sentence can only produce a reworded copy of it, so `userPrompt` states the facts and the task while `SYSTEM_PROMPT` states the no-invention constraint. `MoveCoachPromptBuilder` also spells motif slugs out (`discovered-attack` → "discovered attack") — a 270M model copies the nearest token, and a raw id in the prompt is a raw id in the panel. `MoveCoachManager.proUnlocked = false` short-circuits to exactly that deterministic text as a `Ready` state (not a `Fallback` — the free tier is a complete answer, not a degraded one). **A model is optional, and on Android none is attached.** Not the same claim as before: a Pixel 10-class device *can* run Gemini Nano through ML Kit (measured — see the Android backend note below), and the wiring now exists — `MainActivity.attachOnDeviceAi()` holds probe → `VendorRouteExecutor` → both orchestrators behind one `ATTACH_ON_DEVICE_AI` constant, which is `false`. **The constant sits inside the probe call, not outside it**: on a device reporting `DOWNLOADABLE`, `probeAvailableLocalVendors()` calls `warmup()` and awaits an AICore feature fetch, so "inactive" has to mean no network and no provisioning, not merely no visible model. Two other details there are load-bearing: `isAppForegrounded` is read per request from `holder.isForeground` (AICore refuses to generate in the background with `ErrorCode 30`, and the decider must see that as "not foregrounded" rather than the model returning an error), and the vendor list is probed once and reused (thermal and quota, which do change per request, are the decider's own inputs). `MoveCoachManager` renders `DeterministicCoach` directly when no orchestrator is attached, so the panel answers instantly on every platform whether or not a model exists; a model, where one exists, only rephrases. **Two gates used to suppress the panel entirely in that case and either one alone renders a blank panel** — the `orchestrator != null` condition on the `onMoveCoached` callback, and an early return at the top of `launchCoach`. Both are gone and both are pinned by `MoveCoachToneTest`; do not reintroduce either. `AppSettings.aiCoachEnabled` (Settings switch, default on) still gates the whole thing, because off means off. Backed by a shared KMP module `:onDeviceAi` holding the routing policy (`AiRoutePolicies.moveCoachOffline`), prompt builder, validator, and deterministic text in `commonMain`; platform runtimes are injected at the entry points (desktop `CHESS_ENABLE_COACH=1`, wasm `?coach=1`) — **no phone attaches one**. Android never did; iOS stopped on 2026-08-15, when Foundation Models was measured against the same 100 golden positions as ML Kit and came back faster, more fluent and less truthful (75/100 grounded against 91). Both phone platforms therefore render `DeterministicCoach`, and the coach is **not a Pro surface** — `PaywallScreen.proFeatures()` keys that line off `MoveCoachManager.hasOrchestrator` (as it now does for all five), so it drops out on its own rather than selling something no one can receive.
  - **Android backend:** **none attached — the coach runs deterministically, for two different reasons.** Cactus is ruled out by measurement; ML Kit is unattached by decision, and the two must not be conflated. Cactus was removed after every model in its catalog was benchmarked on a Galaxy Z Fold 3 and all of them lost to the deterministic text: `gemma3-270m` echoed its own prompt, `qwen3-0.6` needed 20–36 s (every `qwen3-*` is a reasoning model and `/no_think` is inert through Cactus), `gemma3-1b` spent 5–20 s on generic waffle, and `lfm2-700m` was fluent and false — "Nh3 … immediately controls the center" about a knight on the rim, passing every validator gate. On **Game Summary**, which has no response validator at all, `gemma3-1b` invented a bishop sacrifice on move 1 and once answered in German; all three runs were accepted. So no Cactus model ships, nothing downloads, and `GameSummaryManager.enableDeterministic()` composes the turning points instead. **ML Kit/AICore reaches a real model, and the earlier "no AICore on this device" verdict was a client-config bug.** A Pixel 10 Pro XL reports `AVAILABLE` with `baseModelName = nano-v3` under the Google sample's default config *and* under `preference = FULL`; only `preference = FAST` — the one variant this codebase ever constructed — reports `FEATURE_NOT_FOUND: Feature 645`. Measured at 444–734 ms init, 488–574 ms to first token, 3.0–3.9 s complete. **Its output quality has now been measured over the full golden set, and the answer is "fluent, fast, and not verifiably truthful."** 100 cases on a Pixel 10 Pro XL (2026-08-15): 95/100 pass `MoveCoachResponseValidator` against 72/100 for `DeterministicCoach` — but 17 of the deterministic column's 28 rejections are `isEchoedPrompt` firing on the text that *is* the prompt's baseline line, so the honest comparison is ~95 vs ~89, and 79/100 model answers fail `FluencyScorer` on conversational filler. A hand read finds invention the validator cannot catch: a motif belonging to the played move reattached to `betterMoveDisplay` ("e4 … because it develops a piece"), and an invented "opens up the h-file". `validateReasonFaithfulness` covers mate/check/capture claims and `validatePieceType` covers piece nouns; **neither covers motif attribution or file/diagonal claims**, and that rule is the gate for attaching this model. Every older `aicore-nano-fast` and `cactus-android` row on that page predates the fixture fix and measured the harness: `AndroidBenchRunner` used to build every `MoveCoachRequest` with a placeholder `deterministicExplanation` ("This was a strong move.") and no facts at all. It costs no download, and emulators still report `Unavailable` and fall through to deterministic text. **AICore refuses to generate in the background** (`ErrorCode 30`), and the screen timeout is not the whole story — the *keyguard* is what stops the bench activity being resumed, so `svc power stayon usb` does not prevent it. `MainActivity.keepBenchInForeground()` (bench-only) holds `FLAG_KEEP_SCREEN_ON` + `setShowWhenLocked`; without it a locked device produces a full JSONL of sub-second fallbacks that measure nothing. **But it is not attached in the app yet** — see the wiring note in the Move Coach bullet above. On **Game Summary** the verdict is much closer than on the coach: 12/12 at ~12 s citing exactly the code-chosen turning points, once three of *our* bugs were fixed (see `docs/benchmarks/on-device-ai/game-summary-2026-08.md`). The "AICore repetition loop" in `evals/scorecard.md` was ours, not the model's (`AiTokenOrFinal.Final` carried the accumulated text on top of the streamed tokens). See `docs/benchmarks/on-device-ai/android-model-latency-2026-08.md`.
  - **iOS backend:** **Foundation Models** (Apple Intelligence) via `FoundationMoveCoachBridge` registered into `FoundationModelsBridgeRegistry` from `iOSApp.swift`. Requires **iOS 26.0+** (every Foundation Models call is `@available(iOS 26.0, *)`-gated; the app's own deployment target is 16.0) plus `SystemLanguageModel.default.availability == .available`. Unlike Android/desktop/wasm there is **no build flag or debug/release distinction** — `MainViewController` probes availability at launch on every build and falls back to rule-based text when unavailable (old OS, ineligible device, or Apple Intelligence off in Settings — all surface identically). **It is no longer attached to the Move Coach**, and the claim it used to carry — that iOS was the one platform where an on-device model earned its place, measured at 1.8 s for *"e4 is a good move because it controls the center."* — was one sentence about one position. Measured against the same 100 golden positions as ML Kit on identical prompts (2026-08-15): **650 ms** median against 4.4 s and 39 fluency violations against 79, but **75/100 grounded against 91**, an LLM judge preferring the deterministic line **54–46** where it preferred nano-v3 52–48, and a hand read finding **5 of 8** sampled flags real — it calls an inaccuracy a mistake, calls a best move bad, and offers the player's own hanging pawn as the reason the move was good. Where it does not contradict the facts it usually repeats the deterministic sentence verbatim. `MainViewController` still probes availability, but only Game Summary and Rules Q&A consume it. The **Simulator supports it** on an Apple Silicon host with Apple Intelligence enabled, so benchmarking needs no physical iPhone; it does need the real app under `BENCHMARK_MODE`, since the bridge is registered from `iOSApp.swift` and the Kotlin/Native test runner sees no vendors.
  - **Desktop backend:** **LiteRT-LM** (`com.google.ai.edge.litertlm:litertlm-jvm`, Google AI Edge) — the Kotlin/JVM API over LiteRT-LM, with native libs bundled inside the jar (linux-x86_64 / linux-aarch64 / darwin-aarch64 / win-x86_64; **no Intel Mac** — those hosts fall back). The Qwen3-0.6B-int4 model (~347 MB `.litertlm`) is downloaded from Hugging Face on first launch by `LitertLmModelStore` and cached under `~/.chess-coach-models/`. Gated behind `CHESS_ENABLE_COACH=1` (env var) in `Main.kt` — without it the coach panel stays `Hidden` (the previous default). Android no longer has an equivalent gate; desktop and wasm are now the only gated targets. Implemented by `LitertLmTextGenerator` in `:onDeviceAi` desktopMain, wired via the same `OnDeviceTextGenerator` seam as Foundation Models; the entire `DefaultAiCoachOrchestrator` → `MoveCoachManager` pipeline is reused unchanged. (The literal "LiteRT.js"/prebuilt LiteRT C++ SDK are not LLM runtimes — see `docs/benchmarks/on-device-ai/desktop-wasm-litert-lm.md` for why LiteRT-LM was chosen.)
  - **Wasm backend:** **LiteRT-LM for Web** (`@litert-lm/core`, loaded from the jsdelivr CDN at runtime) running in a **module Web Worker** so inference is off the main thread. The worker script is embedded as a Kotlin string and spawned from a Blob URL (`LitertLmWasmInterop.kt`) — no webpack/resource-packaging changes, mirroring how `FilamentWasmChessRenderer` injects its CDN `<script>`. Uses `gemma-4-E2B-it-web.litertlm` (~2 GB, the only model `@litert-lm/core` officially documents for web), streamed from HF by the LiteRT-LM `Engine.create()` call. Requires **WebGPU** (`navigator.gpu`); on Firefox/Safari `status()` returns `Unavailable` without any network fetch and the orchestrator falls back to `MoveCoachFallback`. Gated behind `?coach=1` on the page URL. Implemented by `LitertLmWasmTextGenerator` in `:onDeviceAi` wasmJsMain.
  - **JS target** (`js(IR){nodejs()}`): still `UnsupportedTextGenerator` — the React Native port has no WebGPU/workers.
- **Game Summary (on-device):** End-of-game counterpart to the coach. `GameScreen`'s game-over popup renders the **Get Coach Summary** button only when `GameSummaryManager.uiState` is `GameSummaryUiState.Hidden` ("ready, idle") — `Unavailable` (the manager's initial state, and where `attachOrchestrator(null)` puts it) renders nothing. Pressing the button feeds `GameActions.toPgn(...)` to `GameSummaryManager.triggerSummary(pgn)` → `DefaultGameSummaryOrchestrator` (45 s `withTimeoutOrNull` for the whole PGN-sized generation). Reuses `moveCoachOffline` and the *same* `VendorRouteExecutor` instance the coach warmed up (the executors cache their generator), so there's no second model load. Two intentional differences from the coach: **no response validator** (any non-blank text is accepted — there are no per-move tags to ground against), and it's **pull-based** (nothing runs until the button is pressed). Because nothing validates it, two guards in the generation path are the only protection the text has, and both were added after they were measured failing: `trimIncompleteSummaryTail` drops a ragged trailing fragment, and `noRepeatNgramSize` is widened to 8 — B15's default of 4 cut real answers at the start of the second *"so this was another small inaccuracy"*, because a summary of three turning points is a parallel list and a parallel list repeats its connectives by construction. **The prompt deliberately does not contain the PGN**, despite `GameSummaryRequest` carrying one: the turning points hold every fact the summary may state, and the raw movetext both tripped Apple's language guardrail (8/12 prompts rejected with *"An unsupported language or locale was used"*, in 15–20 ms) and was the sole source of Android's invented narrative ("in the endgame", "contributed to the loss" on a game with no result). Removing it took iOS from 4/12 to 12/12 and Android's unsourced-claim rows from 6/12 to 0/12. Attached alongside the coach at every entry point (Android: attached on all builds; iOS: same Foundation Models availability probe; desktop/web: same env var/URL param) — where the coach is gated off, `attachOrchestrator` is either called with `null` or never called at all, and both leave/put the manager in `Unavailable`, so the button never appears rather than appearing and silently no-opping. Note it does **not** share the coach's `AppSettings.aiCoachEnabled` Settings switch — that only guards the automatic per-move `MoveCoachManager` callback, so turning the coach off in Settings has no effect on this button. `GameSummaryEvent.Streaming` exists in the model and is rendered by the UI, but `DefaultGameSummaryOrchestrator` currently only emits `Complete`.
- **Rules Q&A (on-device, retrieval):** `RulesQaScreen` + `RulesQaStateHolder` over `DefaultRulesQaOrchestrator`. Corpus is the bundled `onDeviceAi/src/commonMain/resources/rulesCorpus/passages.tsv` (30 passages + header) looked up by BM25 (`BundledRuleLookupTool`). The answerer is an `expect fun defaultRulesQaAnswerer(lookupTool)`: **iOS** = native Foundation Models `Tool` conformance with `NLEmbedding` ranking (same iOS 26+/Apple Intelligence gate as the coach, no separate flag), **Android** = `OnDeviceRulesQaAnswerer` (Kotlin looks up the user's question, the model emits a tool call (native if `supportsTools` is true, otherwise structured JSON output `{"tool":"lookup_rule","query":"…"}`) to *refine* that lookup, and a second turn phrases the result) — the answerer is returned unconditionally, with no initialization probe, so Rules Q&A is active on every Android build. The retrieval floor sits *above* the routing decision, not inside the answerer: `DefaultRulesQaOrchestrator.groundedOrFallback` re-runs `lookupTool.lookup(question)` on **every** give-up path — no route, timeout, generation error, validator veto — so a first launch mid-download answers from the corpus instead of `RulesQaFallback.TEXT`. The orchestrator also keeps the `rulesQaOffline` 20 s `completeMs` budget as a `withTimeoutOrNull` around the answerer: the Android answerer deadlines its own two model turns, but **iOS Foundation Models has no timeout of its own**, so removing the outer one strands the Rules screen in `Loading` forever. The timeout was originally removed because expiring it discarded a completed lookup — the fix is the grounded fallback, not dropping the bound. **desktop / wasm / JS return `null`** unconditionally.

> **Retrieval is never gated on the model, and a retrieval that succeeds is never downgraded to
> `RulesQaFallback`.** Both halves were violated at once and the feature was dead on device while
> `RuleLookupToolTest` stayed green. The lookup used to run *only* on the model's `lookup_rule`
> query, so a 270M model fumbling its JSON meant the corpus was never searched; and the second turn
> had to reproduce an exact `[passage-id]` or `RulesQaResponseValidator` rejected it, discarding a
> correct retrieval. `RulesQaGrounding` (commonMain) is the floor: the retrieved passage rendered as
> a cited answer, used whenever the model's wording fails validation. Only an *empty* retrieval may
> reach the fallback text. `RulesQaGroundingTest` pins this; a retrieval-only test cannot, which is why the original break was invisible. No Settings switch exists for this feature; `RulesQaStateHolder` starts in `RulesQaUiState.Unavailable` whenever the answerer is `null`, and the screen reports itself unavailable rather than rendering a dead input box.
>
> **Before touching the corpus or the scorer, prove retrieval is the failing step.** While the
> feature was dead on device, BM25 already ranked the reported question's correct passage
> first by a wide margin (`draw-dead-position` 9.079 vs `draw-agreement` 5.979 for *"Game is a
> draw when only kings remain?"*) — four commits were spent tuning a step that already worked.
> Reproduce that probe first; the bug is almost always above retrieval.
>
> **Two questions are deliberately open here.** The answer turn still asks the model to
> reproduce `[id]` in prose rather than emitting a structured answer envelope; and nothing yet
> records whether Rules Q&A should prefer ML Kit now that Cactus is gone and retrieval answers on its own.
- **Opening Explainer (cloud):** `OpeningExplainerStateHolder.explain(gameState)` runs only for a finished game and posts FEN + first 20 SAN plies + ECO to `:server`. Client (`opening/KtorOpeningExplainerClient.kt`) is `null` unless a base URL is configured — the URL comes from `generateOpeningExplainerConfig` (`CHESS_COACH_BASE_URL` env → `coach.baseUrl` in `local.properties` → empty). Non-2xx/offline/no-URL all surface as a deterministic offline message, not an error state.
- **Position Chat (cloud, streaming):** `chat/ChatScreen` + `ChatViewModel` + `KtorStreamingChatClient` in `:app`, `DefaultPositionChat` in `:onDeviceAi`, `PositionChatService` + the `POST /v1/positions/chat/stream` route in `:server`. Chat is **cloud-only by design**: there is no on-device chat generator, and a `RunOnDevice` decision is treated as "no route" (fallback event). Shares the explainer's base URL and HTTP engine. Fences worth knowing before editing:
  - The server route writes SSE by hand over `respondBytesWriter`, **not** Ktor's `sse { }` plugin — the plugin's builder is GET-only and chat must POST a body. It also emits `: keep-alive` comment lines and needs Netty's `responseWriteTimeoutSeconds` (60) to stay **above** the heartbeat interval; the 10 s default force-closed chats before the first token.
  - The client's `withTimeout(45_000)` around the stream is deliberate belt-and-braces on top of `HttpTimeout`: a `bodyAsChannel()` read inside `execute{}` has not reliably honoured CIO's socket timeout, which hung the UI with no error.
  - `ChatViewModel` keeps a single `streamJob` (Stop cancels it, which must close the TCP connection) and sends the last 6 turns; the server independently caps history at 12 turns / 20 plies / 500 chars.
  - Validation is server-side on the *accumulated* text at stream end; a veto emits `fallback` with `TemplateChatComposer`'s grounded text. `DefaultPositionChat`'s own fallback event is a fixed offline sentence and is **not** retrieval-grounded — don't conflate the two layers.
- `docs/plans/on-device-coach-rag-unification.md` — **partially implemented**; check before assuming either way. Landed: **RAG-1** (per-ply `MoveAssessment` + motifs, persisted on `MoveRecord`, coach subject switched to the player's moves), **RAG-2** (summary ranks turning points by `cpLoss`/`MoveClass` and cites `[move-N]`), **RAG-3** (the per-move line is grounded in the assessment and the headline is computed in code by `DeterministicCoach`), and the Hint-button split-out from **RAG-4**. Landed since: the **counterfactual** half of RAG-4 — `MoveAssessment.bestMoveSan` (resolved by `SanConverter.sanForUci` at analysis time, where the pre-move position is in scope) drives `DeterministicCoach`'s "Nf3 was stronger." tail and reaches the model as `MoveCoachRequest.betterMoveDisplay`. Silent for BEST/BOOK: inside 10cp the gap is engine noise. Not implemented: the rest of RAG-4 (assessment-record retrieval in chat, deterministic-feature query construction), **RAG-5** (habit aggregation across games — `GameHistoryBackfiller` backfills assessments but nothing aggregates them), and **RAG-6** (offline chat). Don't document the unimplemented phases as existing behaviour.

- Four conclusions worth knowing before touching the eval system, all measured against gemini-3.6-flash on 100 golden cases:
  - **The provider composer is accepted on 99 of 100 cases.** The old "42% ungrounded" was
    `EvalScorer.scoreOpening` requiring the *literal* string `"development"`/`"center"`, so it scored
    verbatim copying — `TemplateComposer` quotes its passage and scored 0% by construction, while
    every correct paraphrase failed. Grounding is now concept coverage (`ConceptVocabulary`, an
    auditable synonym table) **plus** passage anchoring.
  - **Chat does not stream.** Live: TTFT 10.9 s, then the whole answer in one SSE `token` event. Our
    writer and `LlmChatComposer` are both ruled out in tests; the provider batches. Don't describe
    the shipped experience as token-by-token.
  - **`ProviderCostBudget` prices measured expected output (1400 tokens), not the 2048 ceiling**, and
    the default cap is 1.5¢. A thinking model bills ~13× its visible answer, and Gemini reports
    reasoning tokens only in `total_tokens`.
  - **`book-retrieval` is an AUTOMATED eval row** — 100 golden cases must resolve to their ECO from
    the move prefix offline. `CorpusBookIndex` duplicates the SQL book tier and is pinned to it by a
    cross-check in `OpeningRetrievalGroundingTest`; don't let them drift.
- `docs/plans/hybrid-inference-vendor-adoption-plan.md` outlines the vendor adoption plan for hybrid AI inference.
- `docs/plans/review-fixes-hybrid-inference.md` documents P0 blocking review fixes for the hybrid inference implementation (PR #106) and should be completed before further feature development.

## Hybrid inference & AI

- **App Check is skipped**: The server relies on rate limiting rather than Firebase App Check for abuse prevention.

## Build quirks (don't "clean up")

- `app/build.gradle.kts` contains reflection-based workarounds wiring compose resources into Android assets (`...ComposeResourcesToAndroidAssets` task config and the `mergeAndroidDeviceTestAssets` copy hack). These exist so the androidApp module and device tests can see shared compose resources.
- `androidApp/build.gradle.kts` registers `:app`'s generated compose-resource assets dir as its own assets source and forces every task with "lint" in its name (both `lint*` and `generate*LintModel` — a `startsWith` match would miss the latter) to depend on `:app:copyAndroidMainComposeResourcesToAndroidAssets`, preventing AGP task dependency validation errors during release builds.
- `androidApp` uses `jniLibs.useLegacyPackaging = true` so the Stockfish binary is extracted to `nativeLibraryDir` and can be executed.
- iOS framework uses `baseName = "ChessApp"`; `embedAndSignAppleFrameworkForXcode` must stay the first build phase with `ENABLE_USER_SCRIPT_SANDBOXING=NO`; simulator device pinned via `iosSimulatorDeviceId` property. The iOS app target links Filament through `iosApp/iosApp/Filament/filament.xcconfig`, which expects the gitignored `iosApp/iosApp/Filament/filament/` xcframework payload from `tools/fetch_filament_ios.sh`.
- Desktop 3D uses the gitignored Filament desktop payload from `tools/fetch_filament_desktop.sh` plus the CMake-built `desktop_filament_bridge` JNI library. Desktop compile output is JVM 24, while `:app:run` and desktop tests use a scoped JDK 26 toolchain launcher.
- Wasm klib incremental compilation is intentionally disabled in `app/build.gradle.kts`; Kotlin 2.3.x otherwise crashes the klib export-name checker on incremental wasm recompiles.
