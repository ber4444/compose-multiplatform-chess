# Issue #32 — M7: vkChess-fidelity for iOS & Android

Goal: bring the **vkChess look** (papermill HDR environment + image-based lighting + filmic tonemapping)
to the **iOS (SceneKit)** and **Android (Filament)** 3D renderers, matching what
[M6](issue-32-3d-ui-m6-wgpu4k.md) (desktop) + [M4](issue-32-3d-ui-m4-wasm.md) (web) achieve on WebGPU.

> **Why the native engines, not wgpu4k?** The committed direction is one wgpu4k/WGSL backend
> ([overview](issue-32-3d-ui-overview.md)), but **wgpu4k's Android & iOS targets are WIP** (M6 §"Backend
> maturity"), so they can't ship fidelity today. iOS already renders via **SceneKit** and Android via
> **Filament** — both have first-class IBL + skybox support, so we get the vkChess look *now* by feeding
> them the same environment. This is **interim**: when wgpu4k Apple/Android mature, these renderers are
> replaced by the shared WGSL path and M7 is retired. The *visual target* (this env + tonemapping) is
> identical across all four backends, so it converges cleanly.

Reference look: `/Users/presence/AndroidStudioProjects/vkChess` (Sascha-Willems glTF PBR+IBL, papermill
env). The desktop port (M6) is the in-repo proof; iOS/Android just drive their engines to the same result.

## Shared prerequisite: the environment asset

Both platforms need the papermill environment. It currently lives at
`app/src/desktopMain/resources/papermill_hdr16f_cube.ktx` (desktop-only).

- **Place it where iOS & Android can load it.** Simplest: a commonMain compose resource
  (`app/src/commonMain/composeResources/files/env/…`) read via `Res.readBytes`, or per-platform bundling.
- **Per-engine format differs from the raw cube** — flag and resolve early:
  - **SceneKit** wants an environment image it can use for `lightingEnvironment`/`background`: a cube
    (6 faces, e.g. via `MDLTexture`/`SCNMaterialProperty`) or an equirectangular HDR. The raw
    `*_cube.ktx` may need converting to a SceneKit-friendly form (6 PNG/EXR faces or an equirect HDR).
  - **Filament** wants a **prefiltered IBL** (mip-chain + spherical-harmonics), produced by Filament's
    `cmgen` from an HDR/equirect, then loaded with `KTX1Loader.createIndirectLight` (+ a matching
    `Skybox` KTX). The raw cube is not directly a Filament IBL.
  - **Decision Resolved:** We will use offline-generated platform-specific assets (see [issue-32-3d-ui-unresolved-questions.md](issue-32-3d-ui-unresolved-questions.md)).

## iOS (SceneKit) — `IosSceneKitChessRenderer`

Current state (`app/src/iosMain/.../board3d/IosSceneKitChessRenderer.kt`): PBR materials
(`SCNLightingModelPhysicallyBased`), `cam.wantsHDR = true`, but **`lightingEnvironment.contents` and
`background.contents` are flat `UIColor`s** — so IBL/sky come from a solid colour, not a real environment.

Changes:
- Set `scene.lightingEnvironment.contents` = the papermill environment (cube/equirect) → real IBL on
  board + pieces. Tune `lightingEnvironment.intensity` to match.
- Set `scene.background.contents` = the same environment → the forest skybox (replaces the flat-colour
  background + `scnView.backgroundColor`).
- Tonemapping/exposure: `wantsHDR` is on; tune `cam.exposureOffset` / SceneKit's filmic curve toward
  vkChess's Uncharted2-ish exposure (~4.5 feel). Keep `metalness=0`, roughness ~0.45 wood / ~0.25 marble.
- Drop the separate grey floor (the env is the ground), mirroring desktop's `includeGround = false`.

## Android (Filament) — `AndroidVulkanChessRenderer`

Current state (`app/src/androidMain/.../board3d/AndroidVulkanChessRenderer.kt`): a directional light and a
**solid-colour `Skybox`**, and **no `IndirectLight`** — i.e. no IBL at all, so PBR materials look flat.

Changes:
- Add **`IndirectLight`** built from the papermill IBL (cmgen KTX): `KTX1Loader.createIndirectLight(...)`,
  set `scene.indirectLight`, with intensity tuned to match.
- Replace the solid `Skybox` with the environment **`Skybox`** (cmgen skybox KTX) → forest background.
- Tonemapping: Filament's `View`/`ColorGrading` (ACES/filmic) + exposure on the `Camera` to match vkChess.
- Keep the existing glTF material instances (white/black wood, marble); they'll now receive IBL.

## Verification

- iOS: run on simulator/device; screenshot, compare framing/lighting to vkChess and to the desktop
  `build/wgpu-frame.png`. `./gradlew :app:iosSimulatorArm64Test` stays green (toggle/fallback tests).
- Android: `./gradlew :androidApp:assembleDebug :androidApp:installDebug` on a device; screenshot/compare.
  Existing `:app:connectedAndroidDeviceTest` toggle tests stay green.
- Full CI matrix builds (overview "Execution rules").

## Decisions / risks
- **Interim by design** — replaced by the unified wgpu4k WGSL backend once wgpu4k Apple/Android mature.
- **Asset conversion is the main unknown** — Filament IBL (cmgen) and SceneKit env formats differ from the
  raw `*_cube.ktx`; resolve the asset pipeline first (above) before wiring the renderers.
- **No dynamic shadows** (match vkChess + the other backends); SceneKit/Filament soft shadows are an
  optional extra, out of scope for parity.
