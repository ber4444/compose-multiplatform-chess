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
./gradlew :app:connectedAndroidDeviceTest       # Android UI tests (needs device/emulator)
./gradlew :app:iosSimulatorArm64Test            # iOS Compose UI tests
xcodebuild -project iosApp/iosApp.xcodeproj -scheme iosApp -configuration Debug -destination "platform=iOS Simulator,name=iPhone 17" CODE_SIGNING_ALLOWED=NO test # iOS Swift tests
./gradlew :app:desktopTest --tests "*board3d*" -Dchess3d.smoke=true # run 3D smoke tests
```

CI (`.github/workflows/android-tests.yml`) builds every target with:

```bash
./gradlew :androidApp:assembleDebug :app:assembleAndroidDeviceTest :app:check :app:desktopJar :app:packageDistributionForCurrentOS :app:wasmJsBrowserDistribution
```

then runs `:app:connectedAndroidDeviceTest` on an API 35 emulator. A change isn't done until those build for all three targets. A second job (`apple`) builds iOS/macOS targets and runs simulator tests.

## Module and source-set structure

Two Gradle modules:

- `:app` — KMP library holding all UI, game rules, and resources. Targets: `android` (via `com.android.kotlin.multiplatform.library` plugin), `jvm("desktop")`, `wasmJs`.
- `:androidApp` — thin Android application wrapper (manifest, launcher icons) that depends on `:app`.

`gradle.properties` sets `kotlin.mpp.applyDefaultHierarchyTemplate=false`, so the source-set hierarchy is manual. A custom intermediate source set `jvmCommonMain` sits between `commonMain` and the two JVM-backed targets (`androidMain`, `desktopMain`); it holds process/IO code that can't live in commonMain (Wasm has no `java.lang.Process`). `iosMain` dependsOn commonMain holding `MainViewController`; `iosSimulatorArm64Test` holds Compose UI tests; `iosApp/` Xcode project is generated using XcodeGen (`project.yml` as source of truth — regenerate with `xcodegen generate`).

All code uses package `com.example.myapplication` even though the project is named `game`. Generated compose resources class is `game.app.generated.resources`.

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
- **3D Board View:** The `GameScreen` settings row shows a 3D toggle (`viewState.show3D`), but only when a `Board3DSupport` is injected (desktop and iOS entry points pass their respective `Board3DSupport`; other platforms pass `null` until their backend milestones, hiding the toggle). Toggling **swaps** the board: exactly one board (2D `chess_board` or 3D `board_3d`) is visible at a time. The 3D view is defined by the `Chess3DBoardRenderer` interface in `commonMain`, injected mirroring `ChessEngine`. `Board3DSceneMapper.fromFen` turns a FEN into a renderer-agnostic `Board3DScene`. Interaction (camera drag, ray-picked tap-to-move) uses a shared `OrbitCameraController` and `CameraMath.rayFromScreen` in commonMain, mapped cleanly to backend cameras by strictly syncing FOV and aspect ratios.
  - **Desktop backend:** A working LWJGL headless Vulkan renderer (`VulkanChessRenderer`). It bakes the scene into world-space, per-pixel-lit triangle groups split by texture (marble board / white-wood / black-wood), each drawn with its own sampler (descriptor set) and a single `viewProj`+`camPos` push constant, into an offscreen `VkImage` copied into a Compose `ImageBitmap`. `GltfChessMeshes` extracts piece geometry + UVs from `chess.glb`; `GltfChessTextures` decodes the embedded marble/wood textures; `ChessSceneGeometry` assembles the groups. Materia was evaluated and rejected (see `issue-32-3d-ui-m1-foundation.md`).
  - **iOS backend:** A native SceneKit renderer (`IosSceneKitChessRenderer`). It wraps an `SCNView` inside a `UIKitView` with `interactive = false`, allowing Compose `pointerInput` to intercept touches and seamlessly reuse the common raycast math. It loads geometries via OBJ files exported seamlessly from the Java/Desktop models. (Materia/MoltenVK were evaluated and rejected; see `issue-32-3d-ui-m2-apple.md`).
  - **Android backend:** An NDK-native Vulkan renderer using Google Filament (`AndroidVulkanChessRenderer`). It wraps Filament's `Engine`/`Renderer` inside a `SurfaceView` and natively parses the `chess.glb` asset using `gltfio.AssetLoader`. Like iOS and Desktop, Materia was evaluated and rejected (see `issue-32-3d-ui-m3-android.md`).

## Build quirks (don't "clean up")

- `app/build.gradle.kts` contains reflection-based workarounds wiring compose resources into Android assets (`...ComposeResourcesToAndroidAssets` task config and the `mergeAndroidDeviceTestAssets` copy hack). These exist so the androidApp module and device tests can see shared compose resources.
- `androidApp/build.gradle.kts` registers `:app`'s generated compose-resource assets dir as its own assets source and adds task dependencies for it.
- `androidApp` uses `jniLibs.useLegacyPackaging = true` so the Stockfish binary is extracted to `nativeLibraryDir` and can be executed.
- iOS framework uses `baseName = "ChessApp"`; `embedAndSignAppleFrameworkForXcode` must stay the first build phase with `ENABLE_USER_SCRIPT_SANDBOXING=NO`; simulator device pinned via `iosSimulatorDeviceId` property.
