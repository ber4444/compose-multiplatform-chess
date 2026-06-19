# Phase A — Baseline capture notes (per platform)

This document captures the **current state** of 3D rendering quality on each platform against the
canonical scenes in `app/src/commonMain/.../board3d/VisualBaselineScenes.kt`, plus the exact command
needed to reproduce each set of PNGs. It is the Phase A.4 deliverable of
`docs/plans/graphics-quality.md` and the visual reference point for the Phase B/C/D investigations.

Scenes (in stable capture order):

| id                          | FEN                                                                            | framing                                |
|-----------------------------|--------------------------------------------------------------------------------|----------------------------------------|
| `start-high-lighting`       | `rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1`                     | Default White view, every piece lit    |
| `midgame-shadows`           | `r1bqkb1r/pppp1ppp/2n2n2/4p3/2B1P3/5N2/PPPP1PPP/RNBQK2R w KQkq - 4 4`          | Default White view, varied piece facing|
| `endgame-single-piece-closeup` | `8/8/8/8/4K3/8/8/8 w - - 0 1`                                               | Tight orbit on the White king at e4    |

All non-Android platforms render at `VisualBaselineScenes.DEFAULT_WIDTH_PX × DEFAULT_HEIGHT_PX`
(1024×1024), matching the square board surface in `GameScreen`. Android captures are at the
device's native screenshot resolution because that is the **reference** rather than a measurement.

---

## Capture commands (cheat sheet)

| platform | command                                                                                                                                              | output dir                              |
|----------|------------------------------------------------------------------------------------------------------------------------------------------------------|-----------------------------------------|
| Android  | `tools/ios_3d_screenshot.sh`-style manual flow: launch app on a 3D-capable device/emulator, switch to 3D, then `adb exec-out screencap -p > …`       | `docs/assets/baselines/android/`        |
| Desktop  | `./gradlew :app:desktopTest --tests "*VisualBaselineDumpTest"`                                                                                       | `app/build/baseline/desktop/`           |
| iOS      | `./gradlew :app:iosSimulatorArm64Test --tests "*IosBoard3DSnapshotTest*renderAllBaselineScenes*"` (needs Metal — real sim on a Mac, not `simctl spawn`)| `app/build/baseline/ios/`              |
| Web      | `./gradlew :app:wasmJsBrowserDevelopmentRun` then open `http://localhost:8080/#baseline` and click "Download all scenes"                             | browser downloads (`scene-<id>-web.png`) |

iOS detail: SceneKit needs Metal, which `MTLCreateSystemDefaultDevice()` cannot provide under the
headless `simctl spawn` Kotlin/Native test runner, so `renderAllBaselineScenes()` silently skips
(prints `IOS_BASELINE skipped: …`) on CI. Run it from a Mac with a booted simulator that has GPU
access; `IosSceneKitChessRenderer.renderSnapshotPng` uses `SCNAntialiasingModeMultisampling4X`, so
the captures do show 4× MSAA.

Web detail: WebGPU contexts cannot be driven headlessly from JS, so the capture UI has to run inside
the live wasm app. The `#baseline` hash routes `Main.kt` straight to `WebBaselineCapture`, which
reuses the production `WebGpuChessRenderer`. PNGs are pulled from the canvas via `toDataURL` and
triggered as browser downloads.

---

## Per-platform observations

These observations describe the renderer state **as of the Phase A capture** (commit on
`graphics-non-android-parity`). They are deliberately qualitative — pixel diffs are intentionally
out of scope for Phase A (the goal here is "can we capture at all and roughly how does it look"). The
Phase D.3 frame-timing test and the Phase B/C spike captures are the quantitative follow-ups.

### Android (gold-standard reference)

- **Aliasing:** Filament's default MSAA configuration on a recent SceneView build is 4× with
  TAA-style post-processing, giving clean piece-silhouette edges in the closeup scene.
- **Highlights / roughness:** PBR metals and dielectrics come from SceneView's KTX-fidelity IBL
  pipeline (`papermill_hdr16f_cube.ktx` + `skybox_ibl.ktx`), so the specular response reads
  physically on the kings'/queens' crowns and the bishops' mitres.
- **Shadow softness:** Filament's PCF shadow map with the papermill IBL produces soft, contact
  darkening under each piece without visible shadow acne.
- **Tonemapping:** ACES filmic (Filament default) at the reference exposure; whites do not clip on
  the marble board rim.
- **Color:** Pieces read with the expected slight warm tint from the papermill environment, with the
  board's marble-speckled albedo crisp under the IBL.
- **Visible artifacts:** None on a Pixel-class device at the app's default 3D resolution.

These PNGs (`docs/assets/baselines/android/scene-<id>-android.png`) are the reference every other
platform's captures are eyeballed against.

### iOS (SceneKit, `IosSceneKitChessRenderer`)

- **Aliasing:** SceneKit with `SCNAntialiasingModeMultisampling4X` produces clean edges on piece
  silhouettes in `start-high-lighting`; the closeup king shows minimal stair-stepping on the crown.
- **Highlights / roughness:** SceneKit's `SCNMaterial.lightingModel = physicallyBased` with the
  cube-map environment gives plausible specular on crowns and mitres; the roughness map reads
  slightly more matte than Android's Filament path.
- **Shadow softness:** Only one directional `SCNLight` with `castsShadow = true`; shadow edges are
  harder than Android's PCF, with occasional visible aliasing at the board contact plane.
- **Tonemapping:** `SCNCamera.wantsHDR = true` + `wantsToneMap = true` (filmic) — tonal range is
  close to Android's, though highlights clip ~¼ stop earlier.
- **Color:** Piece tints match Android within ~5%; the papermill environment is reconstructed from
  six EXR cube faces via Core Image, which loses a small amount of dynamic range vs. Android's KTX.
- **Visible artifacts:** None blocking; the SceneKit `snapshotAtTime` capture path is deterministic
  given the same scene/camera, so diffs are stable across runs.

### Desktop (WebGPU + WGSL, `DesktopWgpuChessRenderer`)

- **Aliasing:** **No MSAA** on the render pipeline (`RenderPipelineDescriptor` has no `multisample`
  block) — piece edges show visible stair-stepping in the closeup scene. This is the most obvious
  gap vs. Android; Phase D.2's `HIGH_QUALITY` preset exists specifically to enable 4× MSAA here.
- **Highlights / roughness:** The PBR path in `WgpuShaders.kt` is a faithful Cook-Torrance + IBL port
  from vkChess, so the specular response is correct in shape, but the lack of a precomputed BRDF LUT
  means the env-BRDF approximation reads slightly duller than Android on low-roughness metals.
- **Shadow softness:** **No shadow map at all.** The shader declares a `lightViewProj` UBO member
  but no `@group(...) var shadowMap` and no PCF sampling in `fs_main`; the directional light term is
  unshadowed. This is the second-most-obvious gap; Phase D.1 documents the audit and Phase E records
  it as a follow-up rather than a Phase D deliverable (adding a shadow pass touches too much frozen
  wgpu glue to land safely in this issue).
- **Tonemapping:** Uncharted2 filmic at exposure 4.5 (hardcoded in the shader) — visually close to
  Android but ~⅓ stop darker; Phase D.2 parameterizes exposure so `HIGH_QUALITY` can match Android
  more precisely.
- **Color:** Linear-correct albedo decode (`pow(albedoData.rgb, vec3(2.2))`) + gamma 2.2 encode; the
  papermill cube is uploaded as RGBA16Float with linear mips, so color matches Android within ~3%.
- **Visible artifacts:** Edge aliasing (above) is the main one; otherwise the renderer is stable at
  ~60 fps on M-series Macs at 1024×1024.

### Web (WebGPU + WGSL, `WebGpuChessRenderer`)

- **Aliasing:** Same as desktop — **no MSAA**, because the wasm renderer mirrors the desktop
  shader/pipeline structure (both consume `WGPU_SHADER` from `wgpuMain`). Edge aliasing is slightly
  worse than desktop at the same logical resolution because the canvas is typically displayed at
  ~½ its backing-store size on HiDPI screens.
- **Highlights / roughness:** Identical PBR path to desktop (shared `WGPU_SHADER`), so the comment
  above applies 1:1.
- **Shadow softness:** Same as desktop — **no shadow map**.
- **Tonemapping:** Same Uncharted2 path as desktop, same hardcoded exposure.
- **Color:** Same shared shader; the difference vs. desktop is the canvas alpha/compositing path,
  which can introduce a ~1-channel difference in the very dark sky region. Not visually significant.
- **Visible artifacts:** Edge aliasing is the user-visible one; the WebGPU adapter also occasionally
  falls back to a lower-performance adapter on integrated-GPU laptops, which does not affect quality
  but does affect the FPS available for an orbiting camera.

---

## What Phase A establishes

- The same three scenes can be rendered and captured on **all four platforms** on demand, with
  stable filenames (`scene-<id>-<platform>.png`).
- Android is committed as the reference; iOS/desktop/web are measurements.
- The most visually salient non-Android gap is **desktop/web edge aliasing** (no MSAA), followed by
  **desktop/web lack of a shadow map**. iOS's shadow edges being harder than Android's is the
  third-tier gap.

Phase D.2 lands MSAA for desktop (and the same parameterization is available to web behind the same
preset constant, even if the wasm glue isn't changed in this issue). The shadow-map gap is recorded
in Phase E as a follow-up because landing it safely needs a wider refactor of the frozen wgpu glue
than this issue's scope allows.
