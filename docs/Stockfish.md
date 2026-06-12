# Stockfish packaging

    This project vendors official prebuilt Stockfish Android executables as packaged native binaries so `StockfishEngine` can launch them from Android's native library directory at runtime.

## Included ABIs

- `app/src/androidMain/jniLibs/arm64-v8a/libstockfish.so`
- `app/src/androidMain/jniLibs/armeabi-v7a/libstockfish.so`

## Upstream source

Version: `sf_17`

Downloaded from the official Stockfish GitHub releases:

- `https://github.com/official-stockfish/Stockfish/releases/download/sf_17/stockfish-android-armv8.tar`
- `https://github.com/official-stockfish/Stockfish/releases/download/sf_17/stockfish-android-armv7-neon.tar`

This version was chosen because the official `sf_18` Android binaries are about 109-110 MB each,
which exceeds GitHub's 100 MB per-file limit. The `sf_17` Android binaries stay under that limit
while remaining real official Stockfish builds.

## License

Upstream license and documentation copied into:

- `docs/Stockfish-COPYING.txt`
- `docs/Stockfish-README.md`

## Runtime behavior

On Android devices reporting a supported ABI such as `arm64-v8a` or `armeabi-v7a`, `StockfishEngine` will prefer the packaged real executable from `nativeLibraryDir`.

If the app runs on an ABI without a bundled Stockfish binary, the existing embedded fallback path is still used.

## Why the earlier asset approach stayed off

The earlier packaging put Stockfish under `assets/` and extracted it into the app's writable files directory.
That works poorly on modern Android because writable app storage is often not executable.
So the engine could be found but still fail to launch, leaving the UI in the off state.

## Desktop (macOS / Linux) path

`DesktopStockfishEngine` executes the system-installed `stockfish` binary. For macOS, it also probes common Homebrew installation paths (`/opt/homebrew/bin/stockfish`, `/usr/local/bin/stockfish`) and common Linux paths because Finder-launched applications on macOS often do not inherit the shell's `PATH`.

## Web (Wasm) path

For the browser target, we vendor `stockfish-18-lite-single.js` in `app/src/wasmJsMain/resources/stockfish/`. This file is served as a static asset by the Gradle development server and packaged in the web distribution.

`WasmStockfishEngine` initializes a Web Worker with this script. The Worker communicates via `postMessage` (sending strings) and `onmessage` (receiving strings), which is wrapped by `WorkerUciTransport` to fit the common `UciTransport` interface.

## iOS path

For iOS, we use `ChessKitEngine` (version 0.6.0 via SPM) which compiles Stockfish 17 into the app (GPLv3 already covered by `docs/Stockfish-COPYING.txt`). 
Because iOS apps cannot spawn subprocesses, the Swift `StockfishChessEngine` acts as an adapter, starting the engine asynchronously and bridging calls synchronously back to Kotlin via a semaphore lock on `Dispatchers.Default`.

`ChessKitEngine` does not bundle NNUE networks, so Stockfish 17 defaults (`nn-1111cefa1111.nnue` and `nn-37f18f62d772.nnue`) are committed raw into `iosApp/iosApp/Resources/`. These download URLs and shasums must match official Stockfish test networks, as their filenames encode the first 12 characters of their sha256 hashes.
