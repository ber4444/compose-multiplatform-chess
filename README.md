# game

Compose Multiplatform chess app targeting:

- Android
- Desktop (JVM): Linux and macOS
- Web (Wasm)
- iOS

## Setup

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

macOS, Xcode 16+, and JDK 17 are required.
1. `open iosApp/iosApp.xcodeproj`
2. Run the `iosApp` scheme

The Stockfish engine is bundled automatically, nothing to install manually.

## Project layout

- `app/src/commonMain` shared chess UI, game rules, and compose resources
- `app/src/androidMain` Android-specific shared implementation and Stockfish integration
- `androidApp/src/main` Android application manifest that depends on the shared KMP module
- `app/src/desktopMain` desktop launcher
- `app/src/wasmJsMain` web launcher
- `app/src/iosMain` shared iOS implementation
- `iosApp/` Xcode project and Swift adapter

## Useful Gradle tasks

- `./gradlew test` runs shared unit tests
- `./gradlew assembleDebug installDebug` builds and installs the Android app
- `./gradlew :app:run` launches the desktop app
- `./gradlew :app:wasmJsBrowserDevelopmentRun` starts the web target
- `./gradlew :app:connectedAndroidDeviceTest` runs Android UI tests
- `./gradlew :app:iosSimulatorArm64Test` runs iOS Compose UI tests

Mobile and desktop screenshots:

<img width="1768" height="2208" alt="Screenshot_20260416_142830" src="https://github.com/user-attachments/assets/3dc55dee-90e0-4aad-85ea-fab60a22a132" />

<img width="1602" height="1874" alt="Screenshot From 2026-04-19 14-43-11" src="https://github.com/user-attachments/assets/899e1085-6810-41df-83e4-6940d4e9c505" />
