# Demo: how to run each piece of the graphics-quality work

This is the runbook for the work landed in branch `graphics-non-android-parity` against the plan
in `docs/plans/graphics-quality.md`. Every command below is copy-pasteable. Pick the section that
matches what you want to see.

> Conventions: all commands run from the repo root
> (`/private/tmp/compose-multiplatform-chess-worktrees/graphics-quality` unless you've moved the
> worktree). PNG outputs go under `app/build/baseline/<platform>/` with stable filenames of the form
> `scene-<id>-<platform>.png` so they can be eyeballed / diffed across platforms.

## 1. Smoke test — fastest possible verification (≤30 s)

```bash
./gradlew :app:desktopTest --tests "*VisualBaselineScenes*" --tests "*WgpuShaderRegression*"
```

Asserts:
- All three baseline scenes parse via `Board3DSceneMapper.fromFen(...)` (cross-platform sanity).
- The `DEFAULT` preset's WGSL source is byte-identical to the pre-preset literal (no regression).
- The centralized `WgpuMaterialDefaults` constants match the values the shader expects.

If this is green, the regression-safe spine of the work is intact.

## 2. Phase A — baseline captures (per platform)

### 2a. Desktop — full baseline dump (DEFAULT preset)

```bash
./gradlew :app:desktopTest --tests "*VisualBaselineDumpTest.renderAllBaselineScenes*"
```

- macOS-only (Metal/CAMetalLayer). Silently skipped on Linux CI.
- Writes one PNG per scene:
  - `app/build/baseline/desktop/scene-start-high-lighting-desktop.png`
  - `app/build/baseline/desktop/scene-midgame-shadows-desktop.png`
  - `app/build/baseline/desktop/scene-endgame-single-piece-closeup-desktop.png`
- Test log prints `BASELINE_DESKTOP_DIR=<absolute path>` for easy finder-reveal.

### 2b. Desktop — A/B frame-timing comparison (DEFAULT vs HIGH_QUALITY)

```bash
./gradlew :app:desktopTest --tests "*VisualBaselineDumpTest.compareFrameTimingsAcrossPresets*"
```

- Drives the closeup scene through a 4 s orbit per preset and prints:
  ```
  PRESET_FRAME_TIMING_DEFAULT=240 frames / 4012 ms (≈ 59.8 fps)
  PRESET_FRAME_TIMING_HIGH_QUALITY=222 frames / 4024 ms (≈ 55.2 fps)
  PRESET_FRAME_TIMING_SUMMARY=DEFAULT=...; HIGH_QUALITY=...
  ```
- Hard-asserts HIGH_QUALITY did not collapse below 15 fps (would indicate a misconfigured MSAA path).
- The relative ratio between presets is the durable signal; record it in
  `docs/graphics/desktop-renderer-notes.md` after any major wgpu glue change.

### 2c. Desktop — manually run the desktop app under each preset

```bash
# Default (1× MSAA, no shadows, exposure 4.5 — the original shipped path)
./gradlew :app:run

# High quality (4× MSAA + real-time shadow mapping pass + exposure 5.0)
CHESS_DESKTOP_QUALITY=HIGH_QUALITY ./gradlew :app:run
```

Toggle the "3D Board" switch in the settings row to see the rendered scene. The env var is read
once at `DesktopWgpuChessRenderer` construction. Unknown values fall back to `DEFAULT` so a typo
can't break the renderer.

**The HIGH_QUALITY preset is where the dominant visual gaps vs. Android Filament are addressed:**
- A 2048² depth shadow map rendered from the light's POV every frame (Phase D.5).
- 9-tap PCF via a `sampler_comparison` (hardware 2×2 per tap, ~36 effective samples).
- 4× MSAA via a multisampled color target with auto-resolve.
- Exposure bumped to 5.0 to match Android ACES more closely.

If HIGH_QUALITY still looks worse than you'd hope, the remaining gaps are: (a) piece textures have
no mip chain (so no anisotropic filtering on grazing angles), (b) IBL uses the Karis analytic
BRDF approximation rather than a real precomputed LUT, (c) no TAA. All three are documented as
follow-ups in `docs/graphics/non-android-quality-findings.md`.

### 2d. Web — `#baseline` capture flow

```bash
./gradlew :app:wasmJsBrowserDevelopmentRun
```

The dev server prints a URL (typically `http://localhost:8080/`). Open **two** tabs:

1. `http://localhost:8080/` — the normal chess app (verifies the wasm app boots cleanly).
2. `http://localhost:8080/#baseline` — Phase A.2's WebBaselineCapture UI. Click each scene button,
   then **Download all scenes** to grab one PNG per scene as a browser download.

The `#baseline` route is wired in `app/src/wasmJsMain/.../Main.kt:14` — same wasm binary, alternate
entry point. Capture-only; the normal chess UI is one hash-change away.

### 2e. iOS — headless baseline dump (needs Metal)

```bash
./gradlew :app:iosSimulatorArm64Test --tests "*IosBoard3DSnapshotTest*renderAllBaselineScenes*"
```

- Needs a **booted Metal-capable simulator** on a Mac. The headless `simctl spawn` test runner
  cannot provide Metal, so the test silently skips (prints `IOS_BASELINE skipped: …`) on CI.
- Writes one PNG per scene under `app/build/baseline/ios/scene-<id>-ios.png`.
- Test log prints `IOS_BASELINE_DIR=<absolute path>`.

### 2f. iOS — `tools/ios_3d_screenshot.sh` (real-device-or-sim visual check)

```bash
tools/ios_3d_screenshot.sh "iPhone 17"
# output: build/ios-3d-screenshot.png
```

Boots the named simulator, builds + installs the app, launches it directly on the 3D board
(`CHESS_START_3D=1`), waits 7 s for assets + first frame, then captures via `simctl io screenshot`.

### 2g. Android — reference captures

The Android baseline capture requires a laid-out Filament SurfaceView (`PixelCopy` is the only
Android API that can read its content back to CPU). Manual recipe (mirror of
`tools/ios_3d_screenshot.sh`):

```bash
./gradlew :androidApp:installDebug
adb shell am start -n com.example.myapplication/.MainActivity
# toggle the 3D switch in the app
adb exec-out screencap -p > docs/assets/baselines/android/scene-start-high-lighting-android.png
```

Android is the gold-standard reference; the curated PNGs under `docs/assets/baselines/android/`
are the visual reference every other platform is eyeballed against.

## 3. Phase B — three.js spike (web)

The spike is **self-contained** — it loads three.js from the jsDelivr CDN and inlines the canonical
scene data as JS, so it doesn't depend on the wasm app being served alongside it. Any static file
server works.

```bash
# From repo root, start any static file server, e.g.:
python3 -m http.server 8085

# In a browser, open:
#   http://localhost:8085/web/spikes/index.html
```

What the spike does:

1. Pulls three.js r169 (WebGLRenderer + OrbitControls + RoomEnvironment) from the CDN.
2. Bootstraps `WebGLRenderer({ antialias: true })` with `ACESFilmicToneMapping` +
   `PCFSoftShadowMap` + a 2048² directional light shadow map.
3. Inlines the three canonical scenes (matches `VisualBaselineScenes.kt` byte-for-byte at the
   FEN/camera level) so the spike has no Kotlin/Wasm dependency.
4. Builds primitive chess pieces (cylinders/boxes/cones) with `MeshStandardMaterial` PBR.
5. Buttons + "Download PNG" mirror the wasm capture UI; live FPS + triangle count shown.

This is what closes the largest visible gaps the status-quo wasm renderer has: edge aliasing (via
WebGLRenderer's default MSAA) and contact shadows (via PCFSoftShadowMap).

Verdict and recommended next milestones: `docs/plans/web-graphics-spike-result.md`.

## 4. Phase C — RealityKit spike (iOS, dev-only)

The RealityKit spike is **scaffold only** — the renderer is a stub because RealityKit's Obj-C
surface is too narrow for direct Kotlin/Native use (see `docs/plans/ios-graphics-spike-result.md`).
What you can verify:

```bash
# Verify the cinterop links cleanly (this is the spike's main deliverable).
./gradlew :app:cinteropRealitykitIosSimulatorArm64

# Verify the dev-flag gate compiles + the iOS framework still builds.
./gradlew :app:linkDebugFrameworkIosSimulatorArm64
```

To activate the dev gate (currently a no-op renderer):

```bash
SIMCTL_CHILD_CHESS_IOS_REALITYKIT_DEV=1 xcrun simctl launch "iPhone 17" com.example.myapplication
```

The `SIMCTL_CHILD_*` pattern is the same one `tools/ios_3d_screenshot.sh` uses for `CHESS_START_3D`.

## 4b. Open & run the iOS app from Xcode

The Xcode project is regenerated and ready to open:

```bash
open iosApp/iosApp.xcodeproj
```

Pick a target (e.g. "iPhone 17" simulator) and hit Cmd+R. The Xcode build phase calls
`./gradlew :app:embedAndSignAppleFrameworkForXcode` automatically (configured in
`iosApp/project.yml`), so the Kotlin framework is rebuilt when sources change.

To launch directly on the 3D board (skipping the 2D/3D toggle), edit the scheme's Run → Arguments
→ Environment Variables and add `CHESS_START_3D=1`. The same `tools/ios_3d_screenshot.sh`
script does this from the CLI:

```bash
tools/ios_3d_screenshot.sh "iPhone 17"
# output: build/ios-3d-screenshot.png
```

## 4c. iOS baseline scene-cycler demo (Phase A.2 — analog of the web three.js spike)

A dev-only mode that boots the app directly into a fullscreen 3D board cycling through every
`VisualBaselineScenes.ALL` entry on tap. Uses the **production** SceneKit renderer, so what you
see is the same pipeline users get — just driven from a tap cycler instead of the chess gameplay
state. Designed for eyeballing quality at the canonical scenes and capturing each via simulator
screenshot, exactly mirroring how `web/spikes/index.html` lets you do the same on the web target.

```bash
# CLI flow — boots the sim, builds + installs the app, launches it in baseline-demo mode.
tools/ios_baseline_demo.sh "iPhone 17"

# Then in another terminal (or via Xcode → Devices and Simulators → screenshot):
#   tap the board to cycle to the next scene
#   long-press to go back
#   capture the current scene:
xcrun simctl io "iPhone 17" screenshot app/build/baseline/ios/scene-start-high-lighting-ios.png
```

Equivalent via Xcode: open `iosApp/iosApp.xcodeproj`, edit the `iosApp` scheme → Run → Arguments
→ Environment Variables and add `CHESS_BASELINE_DEMO=1`, then Cmd+R. Tap the board to cycle
through `start-high-lighting` → `midgame-shadows` → `endgame-single-piece-closeup` → …

The gate is read in `MainViewController.kt`; the chess gameplay UI is bypassed entirely while
the env var is set, so captures aren't perturbed by dialogs, animations, or move highlights.

## 5. Full test sweep (run before commit)

```bash
# All common + desktop + wasm tests (fast).
./gradlew test

# Just the desktop graphics tests (Phase A + D).
./gradlew :app:desktopTest --tests "*VisualBaseline*" --tests "*WgpuShaderRegression*"

# iOS (needs Metal-capable sim host).
./gradlew :app:iosSimulatorArm64Test

# Full build matrix (matches CI in .github/workflows/android-tests.yml).
./gradlew :androidApp:assembleDebug :app:assembleAndroidDeviceTest :app:check :app:desktopJar :app:packageDistributionForCurrentOS :app:wasmJsBrowserDistribution
```

## 6. Where to look for what

| you want to see / read                          | path |
|-------------------------------------------------|------|
| Per-platform baseline observations + capture recipes | `docs/graphics/baseline-notes.md` |
| Desktop renderer audit (MSAA / shadows / tonemap state) | `docs/graphics/desktop-renderer-notes.md` |
| Cross-platform summary + recommended next milestones | `docs/graphics/non-android-quality-findings.md` |
| Web spike (three.js) verdict + integration cost | `docs/plans/web-graphics-spike-result.md` |
| iOS spike (RealityKit) verdict + Obj-C boundary finding | `docs/plans/ios-graphics-spike-result.md` |
| Canonical scene definitions                     | `app/src/commonMain/.../board3d/VisualBaselineScenes.kt` |
| Desktop quality preset (DEFAULT / HIGH_QUALITY) | `app/src/desktopMain/.../board3d/DesktopRendererQualityPreset.kt` |
| Shared PBR constants (roughness / exposure)     | `app/src/wgpuMain/.../board3d/WgpuMaterialDefaults.kt` |
| WGSL shader builder (exposure-parameterized)    | `app/src/wgpuMain/.../board3d/WgpuShaders.kt` |
| Web baseline capture UI                         | `app/src/wasmJsMain/.../board3d/WebBaselineCapture.kt` |
| iOS RealityKit spike scaffold                   | `app/src/iosMain/.../board3d/IosRealityKitChessRenderer.kt` |
| three.js spike (throwaway)                      | `web/spikes/threeBaseline.js` |
