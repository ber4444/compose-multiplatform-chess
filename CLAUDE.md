# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project overview

Compose Multiplatform chess app (Kotlin 2.3.x, Compose Multiplatform 1.10.x) targeting Android, Linux desktop (JVM), and Web (Wasm). The player plays White; Black is played by Stockfish where available, otherwise by a simple built-in CPU algorithm.

## Commands

```bash
./gradlew test                                  # shared unit tests across targets
./gradlew :app:desktopTest --tests "com.example.myapplication.MoveTest"   # single test class (fastest iteration)
./gradlew :androidApp:assembleDebug :androidApp:installDebug              # build + install Android app
./gradlew :app:desktopRun                       # launch desktop app (needs system stockfish installed)
./gradlew :app:wasmJsBrowserDevelopmentRun      # run web target
./gradlew :app:connectedAndroidDeviceTest       # Android UI tests (needs device/emulator)
```

CI (`.github/workflows/android-tests.yml`) builds every target with:

```bash
./gradlew :androidApp:assembleDebug :app:assembleAndroidDeviceTest :app:check :app:desktopJar :app:packageDistributionForCurrentOS :app:wasmJsBrowserDistribution
```

then runs `:app:connectedAndroidDeviceTest` on an API 35 emulator. A change isn't done until those build for all three targets.

## Module and source-set structure

Two Gradle modules:

- `:app` — KMP library holding all UI, game rules, and resources. Targets: `android` (via `com.android.kotlin.multiplatform.library` plugin), `jvm("desktop")`, `wasmJs`.
- `:androidApp` — thin Android application wrapper (manifest, launcher icons) that depends on `:app`.

`gradle.properties` sets `kotlin.mpp.applyDefaultHierarchyTemplate=false`, so the source-set hierarchy is manual. A custom intermediate source set `jvmCommonMain` sits between `commonMain` and the two JVM-backed targets (`androidMain`, `desktopMain`); it holds process/IO code that can't live in commonMain (Wasm has no `java.lang.Process`).

All code uses package `com.example.myapplication` even though the project is named `game`. Generated compose resources class is `game.app.generated.resources`.

## Engine architecture

The chess-AI path is the part that spans the most files:

- `ChessEngine` (commonMain) — minimal interface: `getBestMove(fen)` / `close()`.
- `BaseStockfishEngine` (jvmCommonMain) — all UCI protocol logic over a spawned process; subclasses only implement `resolveExecutablePath()`. Returning `null` means "no binary, use embedded fallback".
- `StockfishEngine` (androidMain) — launches the vendored `libstockfish.so` from the app's `nativeLibraryDir`.
- `DesktopStockfishEngine` (desktopMain) — uses the system-installed `stockfish` binary.
- Wasm — no engine; `GameViewModel` is created without one.

Black's move flows through `pickMoveStockfish` (Move.kt): game state → FEN (`FenConverter`) → engine → UCI move → app move (`UciMoveConverter`) → `SelectedMove` validated against `getAllLegalMoves`. On any failure (null engine, illegal/unconvertible move) it falls back to `pickMoveCPU` (capture-preferring random, defaults to Queen for promotions). Engines are injected at platform entry points (`MainActivity`, desktop/wasm `Main.kt`) via `viewModel.attachEngine(...)` after an async `start()`.

Stockfish binaries are vendored at `app/src/androidMain/jniLibs/{arm64-v8a,armeabi-v7a}/libstockfish.so` — official `sf_17` builds, pinned because `sf_18` exceeds GitHub's 100 MB file limit. See `docs/Stockfish.md` for packaging rationale (must be in jniLibs, not assets, because app storage isn't executable on modern Android).

## State and UI

`GameViewModel` (commonMain) is a plain class, **not** an androidx ViewModel — it owns its own `CoroutineScope` and exposes `StateFlow`s (`gameState`, `animState`, `viewState`, `stockfishEnabled`); callers must call `close()`. Game rules are top-level functions in `Move.kt` and `Piece.kt`. Board state in `GameUiState` is parallel lists (`piecesWhite`/`positionsWhite`, etc.) indexed together, along with a `castlingRights` field tracking availability for both colors. Turn alternation is driven by animation completion: `animationEnd()` triggers Black's move after White's animation finishes.

**Recent Features:**
- **Castling:** King moves of 2 squares automatically update the corresponding rook's position and castling rights. `PieceAnimationState` supports a `secondaryPiece` to animate the Rook alongside the King.
- **Pawn Promotion:** Reaching the back rank transitions `gameState` to a `pendingPromotion` state (which displays a `PromotionDialog` UI). Normal moves are blocked until the user selects a piece (or the CPU picks one), which then replaces the Pawn and completes the turn. `SelectedMove` encapsulates both the move coordinates and the optional `PromotionType`.

## Build quirks (don't "clean up")

- `app/build.gradle.kts` contains reflection-based workarounds wiring compose resources into Android assets (`...ComposeResourcesToAndroidAssets` task config and the `mergeAndroidDeviceTestAssets` copy hack). These exist so the androidApp module and device tests can see shared compose resources.
- `androidApp/build.gradle.kts` registers `:app`'s generated compose-resource assets dir as its own assets source and adds task dependencies for it.
- `androidApp` uses `jniLibs.useLegacyPackaging = true` so the Stockfish binary is extracted to `nativeLibraryDir` and can be executed.
