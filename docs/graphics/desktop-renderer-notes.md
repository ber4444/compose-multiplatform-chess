# Phase D.1 — Desktop renderer audit (current state)

Audit of the production desktop renderer as of the `graphics-non-android-parity` branch, against
the Phase D.2 plan of landing a `DesktopRendererQualityPreset`. Every claim in this document is
backed by a `file_path:line_number` reference into the frozen `WgpuShaders.kt` /
`DesktopWgpuChessRenderer.kt` so any future agent can re-verify.

The desktop renderer is `DesktopWgpuChessRenderer` (`app/src/desktopMain/.../board3d/
DesktopWgpuChessRenderer.kt`) and its shared WGSL is `WGPU_SHADER` / `SKY_SHADER` in
`app/src/wgpuMain/.../board3d/WgpuShaders.kt`. Web reuses both via `wgpuMain`, so anything said here
about desktop also describes web unless explicitly noted.

## Quality-relevant configuration today

### MSAA (multi-sample anti-aliasing)
- `DesktopWgpuChessRenderer.runRenderLoop` creates the scene render pipeline via
  `device.createRenderPipeline(RenderPipelineDescriptor(...))` with **no `multisample` field**
  (`DesktopWgpuChessRenderer.kt:126`–`162`). The wgpu4k `RenderPipelineDescriptor` defaults
  `multisample.count` to 1, so the pipeline runs **1× MSAA**.
- The color attachment (`texture.createView()` at `DesktopWgpuChessRenderer.kt:268`) is also a
  single-sample texture — there is no separate MSAA resolve target.
- **Implication:** piece silhouettes show visible edge stair-stepping in the closeup baseline
  scene. This is the largest visual gap vs. Android (Filament 4× MSAA + post-processing) and vs.
  iOS (`SCNAntialiasingModeMultisampling4X`).
- **Phase D.2 fix scope:** parameterize `multisample.count`, allocate a multisampled color texture
  when `count > 1`, and resolve into the existing single-sample texture that the staging-buffer
  readback copies from. The `RenderPassColorAttachment` gains a `resolveTarget` view.

### Tonemapping and exposure
- Both shaders hard-code the Uncharted2 operator with `exposure = 4.5`, `gamma = 2.2`
  (`WgpuShaders.kt:136`–`141` in `fs_main`; `WgpuShaders.kt:181`–`189` in `fs_sky`).
- The selection-glow contribution (`WgpuShaders.kt:134`) also uses a fixed `1.6` magnitude.
- **Implication:** desktop reads ~⅓ stop darker than Android's Filament ACES path. Visual but small.
- **Phase D.2 fix scope:** move `exposure` out of the shader source and into the uniform set, so
  `HIGH_QUALITY` can dial it without recompiling the shader module. Stays as a shader-literal
  default in `DEFAULT` to keep the existing capture baseline byte-identical.

### Sampler / texture filtering
- The chess-piece sampler is created with `magFilter = Linear, minFilter = Linear` and **no
  `mipmapFilter` / no `maxAnisotropy`** (`DesktopWgpuChessRenderer.kt:167`–`172`).
- The environment cubemap sampler is created with `mipmapFilter = Linear` and `ClampToEdge` on all
  axes (`DesktopWgpuChessRenderer.kt:532`–`541`), so the skybox/IBL mip chain works correctly.
- The chess piece textures, however, are uploaded without a mip chain
  (`DesktopWgpuChessRenderer.kt:449`–`455`, `TextureDescriptor` has no `mipLevelCount`) — so even if
  the sampler requested mip filtering, there'd be nothing to sample. At the closeup scene's grazing
  angles this means there is **no anisotropic filtering** opportunity.
- **Implication:** the board surface shimmers slightly when the camera orbits because the marble
  albedo is sampled at grazing angles with bilinear filtering only.
- **Phase D.2 fix scope:** leave texture upload paths alone (touching them means re-cutting
  `chess.glb`'s textures, well outside this issue); `HIGH_QUALITY` sets
  `maxAnisotropy = 1` (no aniso without mips) but document the gap.

### Shadow mapping
- The UBO declares `lightViewProj: mat4x4<f32>` (`WgpuShaders.kt:5`) and the CPU builds it
  (`DesktopWgpuChessRenderer.kt:588`–`594`, `lightViewProj()`).
- **But** there is no `@group(...) var shadowMap: texture_depth_2d`, no shadow sampler, and no PCF
  sampling in `fs_main`. The directional light term in `WgpuShaders.kt:108`–`117` is purely analytic
  Cook-Torrance with **no shadow occlusion**.
- **Implication:** the closeup king and the midgame pieces are lit as if floating; there is no
  contact shadow on the board under any piece. This is the second-most-visible gap vs. Android.
- **Phase D fix scope:** out of scope. Adding a depth-only shadow pass + sampler binding requires
  another render pipeline, another uniform update, a depth texture the existing `releaseGpu()` must
  free, and careful threading through the frozen wgpu render loop. Recorded as a Phase E follow-up
  issue rather than risked here.

### Color space
- Albedo decode is `pow(albedoData.rgb, vec3(2.2))` (`WgpuShaders.kt:103`) — correct sRGB→linear.
- Frame encode is `pow(color, vec3(1.0 / gamma))` at `gamma = 2.2` (`WgpuShaders.kt:140`).
- The render target is `RGBA8Unorm` (linear), the staging buffer readback is straight to an Skia
  `Bitmap` with `ColorType.RGBA_8888, UNPREMUL` (`ImageBitmapChess3DSurface.kt:21`–`24`).
- **Implication:** color pipeline is correct; no gamma drift to fix here.

### Environment / IBL
- Papermill HDR cube loaded as `RGBA16Float` with all mips, sampled via `textureSampleLevel` for
  diffuse (N, mip 9) and prefiltered (R, `roughness * 9`) irradiance (`WgpuShaders.kt:122`–`125`).
- envBRDF uses the Karis analytic approximation (`WgpuShaders.kt:80`–`87`) instead of a 2D LUT;
  quality is acceptable but ~5% duller on low-roughness metals than Android's precomputed LUT.
- **Phase D.4 fix scope:** centralize the exposure/roughness constants in the shared `wgpuMain`
  code so `DEFAULT` and `HIGH_QUALITY` reference the same number; a real BRDF LUT is a future
  milestone (requires a precompute pass and another bind slot).

## What D.2 should land (concrete)

A new `DesktopRendererQualityPreset` enum/object in `app/src/desktopMain/.../board3d/` with two
values:

- `DEFAULT` — exactly the current shipped behavior (1× MSAA, exposure 4.5). Required so the existing
  capture baseline and any downstream test pixel counts don't drift.
- `HIGH_QUALITY` — 4× MSAA + a small exposure tweak to match Android more closely.

> **Phase D.5 update:** `HIGH_QUALITY` now also enables a real shadow mapping pass (depth-only
> pipeline into a 2048² Depth32Float texture, 9-tap PCF via `sampler_comparison`). This is the
> dominant visual win — see the audit history below. The shadow-pass work was originally recorded
> as out-of-scope but the user explicitly asked for it after seeing HIGH_QUALITY's first iteration
> still look bad.

Selected at renderer construction time via the `CHESS_DESKTOP_QUALITY` env var (`HIGH_QUALITY` /
`DEFAULT`, default `DEFAULT`), so it's a developer-only knob — no UI churn, no public-API change,
no shipping-default risk. The wgpu shader source gains templated `${msaaSamples}` and
`${tonemapExposure}` substitution points so the same `WGPU_SHADER` const can be re-emitted with the
preset's values; the existing `WGPU_SHADER` literal is preserved as the `DEFAULT` substitution so
the regression test in `WgpuShaderRegressionTest` stays green.

The render-loop changes are scoped to:
1. Pipeline `multisample.count` reads from the preset.
2. When `count > 1`, allocate a multisampled `textureMS` + view, set the color attachment's `view`
   to it, and set its `resolveTarget` to the existing single-sample view (so the staging-buffer
   readback path is unchanged).
3. **(Phase D.5)** When `preset.shadowsEnabled`, allocate a `Depth32Float` shadow texture + a
   `compare = Less` sampler + a depth-only render pipeline; render the scene into the shadow map
   before the main pass; add 2 extra bind group entries (`shadowMap` + `shadowSamp`); the WGSL
   `fs_main` does manual light-clip-space projection + perspective divide + 3×3 PCF taps.

## What D.2 / D.5 explicitly do NOT land

- Shadows (`lightViewProj` is still computed and unused — left in place so a future shadow-mapping
  issue inherits the math).
- Mipmapped piece textures (would touch glTF asset export).
- Anisotropic filtering (depends on mips).
- A real BRDF LUT (needs a precompute pass).
- Any change to the wasm `WebGpuChessRenderer` glue — `HIGH_QUALITY` is desktop-only in this issue.
  Web inherits the shared shader source so it gets exposure-parameterization for free, but its
  pipeline construction in `WasmWebGpuInterop` stays 1× MSAA because changing it requires touching
  the frozen wasm actual.

These are recorded as Phase E follow-ups.

## Shadow mapping (Phase D.5 — shipped)

Originally this section was the out-of-scope follow-up; the user explicitly asked for it after the
first iteration of HIGH_QUALITY still looked bad (MSAA + exposure alone wasn't the dominant gap).
What landed:

- **WGSL:** `wgpuShader(...)` now takes a `shadowsEnabled: Boolean = false`. When true, the shader
  gains `@group(0) @binding(6) var shadowMap: texture_depth_2d;` and
  `@group(0) @binding(7) var shadowSamp: sampler_comparison;`, plus a PCF block in `fs_main` that
  projects world pos into `ubo.lightViewProj`, does perspective divide, and runs a 3×3 PCF kernel
  of `textureSampleCompareLevel` taps. `let shadow = 1.0` on DEFAULT preserves the original pixel
  output (constant-folded).
- **Renderer:** `DesktopRendererQualityPreset` has a `shadowsEnabled` field; `HIGH_QUALITY` sets it
  to true. `DesktopWgpuChessRenderer` builds a `depthPipeline` (`vs_depth` entry point from the new
  `WGPU_DEPTH_SHADER` const), a 2048² `Depth32Float` shadow texture, and a `compare = Less` sampler
  at construction time. The render loop runs a depth-only pass first (rendering all geometry with
  the depthPipeline into the shadow map), then the main pass with the shadow bindings added.
- **Why Depth32Float and not Depth24Plus:** WebGPU's `Depth24Plus` is opaque/non-sampleable —
  sampleable depth requires `Depth16Unorm` or `Depth32Float`. Float wins on precision.
- **Why `compare = Less`:** that's what `sampler_comparison` requires; `textureSampleCompareLevel`
  then returns a [0,1] visibility per tap.
- **Bias:** `lightNDC.z - 0.0025` to kill acne on the board plane under pieces. Tuned empirically;
  smaller bias produced visible shimmer on the white king's base.

What did NOT land alongside (still recorded as follow-ups):
- Cascade shadow maps for very oblique light angles (the current single ortho frustum is fine for
  the canonical scenes but clips at extreme camera orbits).
- Hardware PCF via `GPUCompareFunction` extension (currently using `textureSampleCompareLevel`
  which is uniform-bound; `textureSampleCompare` is non-uniform but requires the `readonly_depth`
  feature).
- Real-time shadow blur (VSM) for softer edges. The 9-tap PCF is a good middle ground.
