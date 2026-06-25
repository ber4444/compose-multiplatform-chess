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
- **3D Board View:** The app features a playable 3D board with shared camera, tap-to-move, ray picking, and move animation logic. Desktop, iOS, and web share `FilamentEncodedChessRenderer` for FEN-to-scene, camera, selection, and transition state; their platform peers only own the Filament surface. Android uses Filament through SceneView (the visual reference); iOS uses **Metal-native Filament** through a Swift/Obj-C++ `CAMetalLayer` bridge; desktop uses **native C++ Filament** with a headless swap chain and RGBA readback into Compose; web uses **Filament (Wasm)** loading the same `chess.glb` Android uses. See `docs/plans/web-graphics-spike-result.md` and `docs/plans/ios-filament-spike-result.md` for the spike verdicts.
- **Stockfish Engine Integrations:**
  - **Android:** Pinned to Stockfish 17, as the Stockfish 18 binary exceeds GitHub's 100 MB file limit.
  - **Desktop:** Relies on system-installed binaries (e.g., via `apt` or `brew`).
  - **Web (Wasm):** Uses a lightweight `stockfish-18-lite-single.js` running in a Web Worker.
  - **iOS:** Wraps `ChessKitEngine` using an async-sync bridge and utilizes NNUE via `EvalFileSmall`.

## Project layout

- `app/src/commonMain` shared chess UI, game rules, and compose resources
- `app/src/androidMain` Android-specific shared implementation and Stockfish integration
- `androidApp/src/main` Android application manifest that depends on the shared KMP module
- `app/src/desktopMain` desktop launcher
- `app/src/wasmJsMain` web launcher
- `app/src/iosMain` shared iOS implementation
- `iosApp/` Xcode project and Swift adapter

Third-party asset and dependency notices live in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

## Useful Gradle tasks

- `./gradlew test` runs shared unit tests
- `./gradlew :androidApp:assembleDebug :androidApp:installDebug` builds and installs the Android app
- `./gradlew :app:run` launches the desktop app
- `./gradlew :app:wasmJsBrowserDevelopmentRun` starts the web target
- `./gradlew :app:wasmJsBrowserDevelopmentWebpack` builds the web development bundle without starting the dev server
- `./gradlew :app:connectedAndroidDeviceTest` runs Android UI tests
- `./gradlew :app:iosSimulatorArm64Test` runs iOS Compose UI tests
- `./gradlew :app:desktopTest --tests "*board3d*"` runs the 3D desktop tests (DesktopRendererSmokeTest writes `build/chess3d-*.png` to eyeball the render)
- `tools/ios_3d_screenshot.sh` captures the real iOS 3D board from a booted simulator

[Article with screenshots](https://medium.com/p/f6a983db0e45)
