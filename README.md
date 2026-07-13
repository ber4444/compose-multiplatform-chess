# game

Compose Multiplatform chess app with full support for all standard chess rules and a 3D view mode, targeting:

- Android
- Desktop (JVM): Linux and macOS
- Web (Wasm)
- iOS

## Setup

For the desktop target on Linux and macOS, **JDK 26 is recommended**. The desktop 3D renderer uses a native C++ Filament bridge; after a clean checkout run `tools/fetch_filament_desktop.sh` to fetch the gitignored Filament desktop payload before desktop builds.

For the desktop target on Linux, install stockfish first:

```bash
sudo apt install stockfish  # For Ubuntu/Debian
sudo pacman -S stockfish    # For Arch
sudo dnf install stockfish  # For Fedora
```

For the desktop target on macOS:
```bash
brew install stockfish
```

### iOS Setup

macOS, Xcode 16+, and a working project JDK are required.
1. `tools/fetch_filament_ios.sh`
2. `open iosApp/iosApp.xcodeproj`
3. Run the `iosApp` scheme

The Stockfish engine is bundled automatically. The Filament iOS xcframeworks are fetched separately
because they are large generated dependencies and are intentionally gitignored.

## Architecture & Features

### Modules

The project is split into three Gradle modules:

- **`:chess-core`** — the Compose-free, platform-agnostic chess engine core (all game rules,
  FEN/UCI/SAN/PGN converters, `GameViewModel`, and the pure-Kotlin 3D-board math/scene mapping). It
  has **no Compose, no russhwolf/Settings, no `java.lang.Process`**. Targets: Android, JVM (desktop),
  iOS (arm64/simulator), **JS (IR)**, and Wasm. Published to GitHub Packages as
  **`io.github.ber4444:chess-core`** (tag-driven: push `chess-core-v*` → `publish-chess-core.yml`
  publishes that version). Consumed by `:app` (below) and by the
  [React Native port](https://github.com/ber4444/react-native-kotlin-multiplatform-chess), so there
  is **no duplicated Kotlin** across the two repos — bump `chessCoreVersion` to pick up core changes.
  Also home to the [perft verification rig](docs/perft.md) — the move generator is proven correct
  against arithmetic ground truth (canonical perft counts + Stockfish cross-check).
- **`:app`** — the Compose Multiplatform app: all UI screens, platform glue, Stockfish bridges, and
  the 3D renderers. Depends on `:chess-core` via `api(project(":chess-core"))`.
- **`:androidApp`** — thin Android application wrapper (manifest, launcher icons) that depends on `:app`.
- **`:perft-mcp`** — a thin stdio [MCP server](docs/perft.md#the-mcp-server-perft-mcp) exposing the
  perft rig as three tools (`run_perft_gate`, `stockfish_divide`, `read_divergence`) for agent-driven
  verification loops. JVM-only; no dependency on `:app` or `:chess-core` (it shells out to gradle +
  stockfish as an adapter, not an engine).

The core↔app boundary is enforced by three seams (don't re-couple them):
  - `Piece` has no `asset` field; `:app` resolves drawables via `PieceAssets.asset()`.
  - Core types have no `@Immutable` (Compose stability hints); `:app`'s compiler re-infers stability.
  - `GameViewModel` takes a `GameSnapshotSink` (core interface), not the russhwolf `CurrentGameStore`;
    `:app` adapts via `CurrentGameStore.asSnapshotSink()`.

### 3D Rendering Pipeline

```mermaid
graph TD
    subgraph commonMain ["commonMain (Shared)"]
        GS["Game State (GameUiState)"] --> GameScreen["GameScreen (UI)"]
        GS --> FC["FenConverter"]
        
        FC --> FEN["FEN String"]
        FEN --> BM["Board3DSceneMapper"]
        BM --> B3S["Board3DScene (Renderer-Agnostic)"]
        
        GameScreen -. "Toggles 3D view" .-> Renderer
        
        Assets["Assets (chess.glb, IBL .ktx)"] --> Renderer
        B3S --> Renderer
        
        Renderer[["Chess3DBoardRenderer Contract"]]
    end
    
    subgraph Platforms ["Platform-Specific Renderers"]
        Renderer --> Android["Android<br>AndroidSceneViewChessRenderer<br>(Filament / SceneView)"]
        Renderer --> iOS["iOS<br>FilamentIosChessRenderer<br>(Filament / Metal)"]
        Renderer --> Desktop["Desktop<br>DesktopFilamentChessRenderer<br>(Filament / native C++)"]
        Renderer --> Web["Web (Wasm)<br>FilamentWasmChessRenderer<br>(Filament / WebGL)"]
    end

    %% Styling
    classDef common fill:#e3f2fd,stroke:#1e88e5,stroke-width:2px,color:#000;
    classDef state fill:#ffffff,stroke:#1e88e5,stroke-width:1px,color:#000;
    classDef contract fill:#fff8e1,stroke:#ffb300,stroke-width:2px,color:#000;
    classDef platform fill:#fafafa,stroke:#9e9e9e,stroke-width:2px,color:#000;
    
    class commonMain common;
    class GS,GameScreen,FC,FEN,BM,B3S,Assets state;
    class Renderer contract;
    class Android,iOS,Desktop,Web platform;
```

- **Full Chess Rules:** The application covers all standard chess rules and includes an explicit draw-by-agreement flow where the Stockfish engine evaluates whether to accept or decline draw offers.
- **Game Lifecycle & Persistence:** The in-progress game is auto-saved on every move and restored on next launch (board, turn, move list). On game end, the user can **Save game** (to a persisted Game History) and **Share PGN** (platform share sheet / file dialog / download). PGN export is full Standard Algebraic Notation with the Seven Tag Roster; paste a saved PGN into lichess.org "Import game" to validate. A **History** screen lists saved games with a detail view and delete.
- **Engine Difficulty:** A persisted Easy / Medium / Hard / Max setting (in **Settings**) weakens or strengthens Stockfish play via the UCI `Skill Level` option and a per-move think-time budget. Applies to the Stockfish engine on every platform.
- **Settings & Navigation:** A minimal multiplatform navigation host (`AppRoot`) switches between the game, **History**, and **Settings** screens, with a persisted 3D-board toggle (default on) and the engine-difficulty selector in Settings.
- **3D Board View:** The app features a playable 3D board with shared camera, tap-to-move, ray picking, and move animation logic. Desktop, iOS, and web share `FilamentEncodedChessRenderer` for FEN-to-scene, camera, selection, and transition state; their platform peers only own the Filament surface. Android uses Filament through SceneView (the visual reference); iOS uses **Metal-native Filament** through a Swift/Obj-C++ `CAMetalLayer` bridge; desktop uses **native C++ Filament** with a headless swap chain and RGBA readback into Compose; web uses **Filament (Wasm)** loading the same `chess.glb` Android uses. See `docs/plans/web-graphics-spike-result.md` and `docs/plans/ios-filament-spike-result.md` for the spike verdicts.
- **Stockfish Engine Integrations:**
  - **Android:** Pinned to Stockfish 17, as the Stockfish 18 binary exceeds GitHub's 100 MB file limit.
  - **Desktop:** Relies on system-installed binaries (e.g., via `apt` or `brew`).
  - **Web (Wasm):** Uses a lightweight `stockfish-18-lite-single.js` running in a Web Worker.
  - **iOS:** Wraps `ChessKitEngine` using an async-sync bridge and utilizes NNUE via `EvalFileSmall`.
- **Perft Verification Rig:** The move generator is proven correct against arithmetic ground truth — [canonical perft counts](https://www.chessprogramming.org/Perft_Results) for six standard positions, plus a Stockfish `go perft` divide-diff cross-check over a seeded random walk of arbitrary midgame/endgame positions. The rig runs in CI on every PR and nightly (deep tier). A pure top-level `applyMove` (extracted from `GameViewModel.deriveNewGameState`) makes the generator testable to perft depth without ViewModel side effects. An optional MCP server (`:perft-mcp`) exposes the rig as tools for agent-driven verification loops. See **[docs/perft.md](docs/perft.md)** for the full walkthrough.

## Project layout

- `chess-core/src/commonMain` the Compose-free chess engine core (rules, FEN/UCI/SAN/PGN, `GameViewModel`, board3d math) — published as `io.github.ber4444:chess-core`
- `chess-core/src/commonTest` + `chess-core/src/desktopTest` the [perft verification rig](docs/perft.md) (canonical gate + Stockfish oracle + deep tier)
- `app/src/commonMain` the Compose UI, persistence, share, and 3D renderer glue (depends on `:chess-core`)
- `app/src/androidMain` Android-specific shared implementation and Stockfish integration
- `androidApp/src/main` Android application manifest that depends on the shared KMP module
- `app/src/desktopMain` desktop launcher
- `app/src/wasmJsMain` web launcher
- `app/src/iosMain` shared iOS implementation
- `iosApp/` Xcode project and Swift adapter
- `perft-mcp/` the [perft MCP server](docs/perft.md#the-mcp-server-perft-mcp) — a thin stdio adapter exposing the rig as agent tools

Third-party asset and dependency notices live in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

## `:chess-core` — public API & dependency boundary

`:chess-core` is the platform-agnostic chess engine core. It is consumed by `:app` for the UI/platform glue, and published as `io.github.ber4444:chess-core` for the React Native repository.

- **Dependency split (`api` vs `implementation`)**: `kotlinx-coroutines-core` is an `api` dependency because `StateFlow` is exposed on `GameViewModel`. `kermit` and `kotlinx-serialization-json` are `implementation` details.
- **Public API surface**: The core intentionally exposes engine entry points (`GameViewModel`, `ChessEngine`), state types (`GameUiState`, `WinState`), the piece model, FEN/PGN converters, the persistence seam (`GameSnapshotSink`), and `board3d` scene types.
- **Internal implementation**: Raw move-generation rules, draw-condition internals, and 3D math helpers are deliberately marked `internal` and are completely unreachable by consumers. If a symbol isn't listed above, assume it is internal.

## Useful Gradle tasks

- `./gradlew :chess-core:check` runs the chess-core test suite across all targets (desktop + iOS sim + JS)
- `./gradlew :chess-core:jsNodeTest` runs just the Kotlin/JS tests (the target the React Native port consumes)
- `./gradlew test` runs shared unit tests
- `./gradlew :androidApp:assembleDebug :androidApp:installDebug` builds and installs the Android app
- `./gradlew :app:run` launches the desktop app
- `./gradlew :app:wasmJsBrowserDevelopmentRun` starts the web target
- `./gradlew :app:wasmJsBrowserDevelopmentWebpack` builds the web development bundle without starting the dev server
- `./gradlew :app:connectedAndroidDeviceTest` runs Android UI tests
- `./gradlew :app:iosSimulatorArm64Test` runs iOS Compose UI tests
- `./gradlew :app:desktopTest --tests "*board3d*"` runs the 3D desktop tests (DesktopRendererSmokeTest writes `build/chess3d-*.png` to eyeball the render)
- `./gradlew :chess-core:publishToMavenLocal` publishes `io.github.ber4444:chess-core` to the local Maven cache (for local cross-repo iteration)
- `./gradlew :chess-core:desktopTest --tests "*Perft*"` runs the [perft gate](docs/perft.md) (canonical counts + Stockfish cross-check)
- `./gradlew :chess-core:desktopTest --tests "*PerftDeepTest*" -Dperft.deep=true` runs the deep perft tier (nightly-only depths; slow)
- `./gradlew :perft-mcp:test :perft-mcp:installDist` builds the [perft MCP server](docs/perft.md#the-mcp-server-perft-mcp)
- `tools/ios_3d_screenshot.sh` captures the real iOS 3D board from a booted simulator

### Publishing `chess-core`

The core is published to GitHub Packages from CI (`.github/workflows/publish-chess-core.yml`), driven
by a tag. To publish a new version:

```bash
git tag -a chess-core-v0.2.0 -m "Publish io.github.ber4444:chess-core:0.2.0"
git push origin chess-core-v0.2.0
```

The version is the tag with the `chess-core-v` prefix stripped. Consumers (the RN port, or any other
Kotlin project) add the GitHub Packages Maven repo (auth: PAT with `read:packages`) and depend on
`io.github.ber4444:chess-core:<version>`.

[Article with screenshots](https://medium.com/p/f6a983db0e45)
