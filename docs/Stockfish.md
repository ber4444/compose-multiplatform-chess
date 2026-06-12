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

