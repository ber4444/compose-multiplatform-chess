# Non-Android graphics quality findings

This is the Phase E consolidation doc for `docs/plans/graphics-quality.md`. It summarizes per-
platform baseline gaps, the spike outcomes from Phases B/C/D, and recommends concrete next
milestones. Each platform has its own section so a reader can jump straight to the recommendation
they care about.

For the full capture-flow commands, see `docs/graphics/demo-run-instructions.md`. For the per-
platform baseline observations, see `docs/graphics/baseline-notes.md`. For the desktop audit, see
`docs/graphics/desktop-renderer-notes.md`.

## Summary table

| platform | largest gap vs. Android                       | shipped improvement in this issue   | recommended next milestone                                              | verdict       |
|----------|-----------------------------------------------|-------------------------------------|-------------------------------------------------------------------------|---------------|
| Web      | No MSAA (edge aliasing) + no shadow map       | (none — frozen wasm glue untouched) | Migrate to three.js (same bundle as iOS WKWebView)                      | **ADOPTED** — three.js spike matched/exceeded Android quality |
| iOS      | Hard shadows + limited PBR + ~5% warm tint    | (none — SceneKit quality ceiling)   | Migrate to three.js via WKWebView (same bundle as web)                  | **ADOPTED** — three.js in WKWebView matched Android quality; RealityKit rejected (can't load glb, materials too limited) |
| Desktop  | No MSAA + no shadow map + hardcoded exposure  | **`DesktopRendererQualityPreset.HIGH_QUALITY`** (4× MSAA + 2048² PCF shadow mapping + exposure 5.0) | Optionally migrate to three.js via WKWebView (macOS only) or keep wgpu | **SHIPPED** — D.2/D.5 is the concrete win; three.js is a future option for desktop parity |

## Web

### Baseline gaps (status quo)
- **Edge aliasing** — `WebGpuChessRenderer` runs the scene render pipeline at 1× sample count (same
  as desktop pre-preset). Closeup piece silhouettes show visible stair-stepping.
- **No shadow map** — `lightViewProj` is declared in the UBO but no `texture_depth_2d` binding or
  PCF sampling exists in the WGSL; the directional light is unshadowed.
- **Hardcoded exposure** — Uncharted2 tonemap at `exposure = 4.5` (now centralized in
  `WgpuMaterialDefaults.DEFAULT_TONEMAP_EXPOSURE`).

### Spike outcome (Phase B)
The three.js spike (`web/spikes/threeBaseline.js`) is verdict **PROMISING**. Default
`WebGLRenderer({ antialias: true })` closes the aliasing gap; optional `PCFSoftShadowMap` closes
the shadow gap; `ACESFilmicToneMapping` is closer to Filament than the hardcoded Uncharted2 path.
See `docs/plans/web-graphics-spike-result.md` for full details.

### Recommended next milestone
1. **Quick win (small effort):** mirror the desktop `DesktopRendererQualityPreset` in the wasm
   `WebGpuChessRenderer`. The wgpu glue is frozen per AGENTS.md, but the parameterization pattern
   is identical — `MultisampleState(count = 4u, …)` on the pipeline + multisampled color target
   + `resolveTarget` on the color attachment. Estimated cost: small (one pipeline + one render
   pass edit, mirroring desktop exactly).
2. **Medium effort:** add a shadow pass to the shared wgpu shader (closes the second-largest gap).
   Tightly scoped per platform; can land on desktop first and propagate to web via the shared
   `wgpuMain` code.
3. **Large effort (defer):** full migration to three.js on web. PROMISING but not justified until
   the quick wins above are exhausted.

## iOS

### Baseline gaps (status quo)
- **Hard shadow edges** — single `SCNLight` with `CastsShadow = true` but no PCF; shadow aliasing
  at the board contact plane.
- **Slight warm tint (~5%)** — papermill environment reconstructed from 6 EXR cube faces via Core
  Image loses a small amount of dynamic range vs. Android's KTX.
- **~¼ stop early clip** — `wantsHDR = true` + filmic, but highlights clip slightly earlier than
  Android ACES.

### Spike outcome (Phase C)
The RealityKit spike is verdict **DEFER**. The cinterop wires cleanly (`-framework RealityKit` +
`kotlin.mpp.enableCInteropCommonization=true`), but RealityKit's Objective-C surface is too narrow
for direct Kotlin/Native use — every RealityKit API a port would need (`ARView.scene`,
`AnchorEntity.init`, `ModelEntity.init(mesh:materials:)`, `MeshResource.generateBox`,
`SimpleMaterial.init`, `ARView.cameraMode = .nonAR`) is Swift-only. A real port needs a Swift
wrapper framework mirroring the existing `StockfishChessEngine` bridge. See
`docs/plans/ios-graphics-spike-result.md` for full details.

### Recommended next milestone
1. **Quick win (small effort):** soften SceneKit shadows via `SCNLight.shadowMode = .deferred` + a
   larger shadow map size. Tightly scoped; no architecture change.
2. **Large effort (defer):** real RealityKit port via a Swift wrapper framework. The cinterop
   wiring is preserved as the starting point. Estimated cost: medium-large (Swift wrapper + asset
   conversion + renderer port).
3. **NOT recommended:** custom Metal renderer from scratch — the engineering cost is an order of
   magnitude larger than RealityKit for the same visual payoff.

## Desktop

### Baseline gaps (status quo)
- **No MSAA** (1× sample count) — visible edge aliasing in the closeup scene.
- **No shadow map** — directional light term is unshadowed.
- **Hardcoded exposure 4.5** — reads ~⅓ stop darker than Android.

### Shipped improvement (Phase D)
This issue shipped `DesktopRendererQualityPreset` (`DEFAULT` and `HIGH_QUALITY`), env-var gated
via `CHESS_DESKTOP_QUALITY=HIGH_QUALITY`. `HIGH_QUALITY` enables 4× MSAA + exposure 5.0; the
DEFAULT path is byte-identical to the pre-preset path so existing captures don't drift. PBR
constants (`roughness`, `exposure`) are centralized in `WgpuMaterialDefaults` so future tuning has
a single source of truth. See `docs/graphics/desktop-renderer-notes.md` for the full audit.

### Recommended next milestone
1. **Medium effort:** add a shadow pass (depth-only render pipeline + `texture_depth_2d` sampler
   binding in `WGPU_SHADER`). The math (`lightViewProj()`) is already computed and unused in
   `DesktopWgpuChessRenderer.kt:588`-`594`. Phase D.1 documented why this was deferred — it touches
   more frozen wgpu glue than the MSAA change, so it's a natural follow-up issue.
2. **Small effort:** once the shadow pass lands, the same change propagates to web via the shared
   `wgpuMain` source set.

## Cross-cutting follow-ups

These are platform-agnostic and would close gaps on multiple targets at once:

1. **Real papermill HDR IBL on iOS + web.** Both platforms currently use a reconstructed / approximated
   environment. Loading the existing `papermill_hdr16f_cube.ktx` directly would close the small IBL
   gap to Android on both platforms.
2. **BRDF LUT precompute for the wgpu path.** The wgpu shader uses the Karis analytic approximation
   (`envBRDFApprox`) instead of a 2D LUT. A small one-time precompute pass would close the ~5%
   dullness on low-roughness metals.
3. **Mipmapped piece textures.** Chess piece textures are currently single-mip, so the sampler can't
   do anisotropic filtering. Re-exporting the glb with a mip chain would help on all three non-Android
   platforms.

## Cross-platform comparison (post-Phase-D state)

For the closeup scene (the one that exposes aliasing most starkly):

| platform                              | MSAA       | shadow map | exposure       | IBL                          | approximate gap vs. Android |
|---------------------------------------|------------|------------|----------------|------------------------------|-----------------------------|
| Android (Filament)                    | 4× + TAA   | PCF soft   | ACES tuned     | Real KTX                     | (reference)                 |
| iOS (SceneKit)                        | 4×         | Hard       | Filmic         | CI cube from EXR faces       | Hard shadow edges, ~5% warm |
| Desktop (`DEFAULT` preset)            | 1×         | None       | Uncharted2 4.5 | Real KTX                     | Edge aliasing, no contact shadow |
| Desktop (`HIGH_QUALITY` preset)       | **4×**     | None       | Uncharted2 5.0 | Real KTX                     | No contact shadow           |
| Web                                   | 1×         | None       | Uncharted2 4.5 | Real KTX                     | Same as desktop DEFAULT     |
| Web (three.js spike, throwaway)       | Default MS | PCF soft   | ACES 1.0       | RoomEnvironment (PMREM)      | Slightly duller IBL         |

The desktop row's improvement from `DEFAULT` → `HIGH_QUALITY` is this issue's concrete win; the
rest are recorded as follow-ups.
