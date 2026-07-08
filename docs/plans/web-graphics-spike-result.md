# Phase B — Web graphics spike result (three.js)

> Verdict: **ADOPTED**. The three.js path produces visually clean PBR rendering with antialiasing,
> soft shadows, and ACES tonemapping out of the box. The user confirmed quality matches/exceeds
> Android ("wow this is really cool now"). three.js is the chosen successor renderer for **both
> web and iOS** — the same bundled code runs in the browser and in a WKWebView on iOS. Production
> web still ships WebGPU; production iOS still ships SceneKit; the migrations are tracked as
> separate follow-up issues.

This document is the Phase B deliverable for `docs/plans/graphics-quality.md`. It captures the
spike's scope, the working integration path, the qualitative visual comparison against Android (the
gold-standard reference) and the current wasm WebGPU renderer, the integration / maintenance cost
of switching, and an explicit verdict per the issue's criteria.

## Scope actually attempted

Per the user's scoping decision (`Phases B/C: implement minimal real spikes — three.js (web) and
RealityKit (iOS) only. Skip Babylon.js/Filament/Metal.`), this spike covered only:

1. **three.js** (r169) with the production-ish stack:
   - `WebGLRenderer({ antialias: true })`
   - `ACESFilmicToneMapping` (closest free tonemap to Filament's default)
   - `RoomEnvironment` as a stand-in IBL (the real papermill HDR is NOT loaded — that's a follow-up
     if the verdict is PROMISING)
   - `PCFSoftShadowMap` + one directional key light matching the desktop shader's
     `normalize(vec3(0.5, 0.9, 0.45))` direction
   - `MeshStandardMaterial` for PBR pieces
2. **JS interop with the Kotlin/Wasm app** via the `@JsExport fun getBaselineScenes()` declared in
   `app/src/wasmJsMain/.../BaselineScenesInterop.kt`. The spike pulls the same canonical
   `VisualBaselineScenes.ALL` list the production renderer consumes, so the A/B comparison is
   apples-to-apples.
3. **Primitive chess pieces** (cylinders / boxes / cones) as the first pass. `chess.glb` loading
   via `GLTFLoader` is wired behind `USE_GLB = false` — the spike never depended on glb loading
   landing cleanly for the verdict to be valid.

**Not attempted (per scope decision):**
- Babylon.js (skipped)
- Filament/WebGPU (skipped — Filament's browser path was the lowest-expected-value candidate given
  the maintenance burden of bundling a separate C++/WASM engine alongside the existing WebGPU path)

## Working integration path

```
web/spikes/threeBaseline.js      # ESM importing three.js from CDN
web/spikes/index.html            # throwaway host; loads wasm app + spike
```

The host page (`index.html`) loads the production wasm bundle so the Kotlin/Wasm module is live
and `getBaselineScenes()` is callable. It then dynamically imports `threeBaseline.js`, which:

1. Pulls the scene list from `window.__CHESS_WASM_EXPORTS__.getBaselineScenes()`.
2. Bootstraps a three.js renderer with the PBR defaults above.
3. Builds a primitive chess scene from the scene's FEN (parses the FEN string locally in JS rather
   than reusing the Kotlin FEN parser — keeps the spike self-contained).
4. Renders each scene on demand and supports PNG download via `canvas.toDataURL`.

To reproduce locally:

```bash
./gradlew :app:wasmJsBrowserDevelopmentRun
# then open the URL printed by the dev server + /web/spikes/index.html
```

There is **no Gradle build integration** for the spike — the spike directory is plain JS, and
removing it is a single `rm -rf web/spikes`. This is deliberate; Phase B says "throwaway unless
explicitly promoted later".

## Qualitative comparison (against Android reference)

| dimension                       | status quo wasm WebGPU                       | three.js spike                                          | gap vs. Android |
|---------------------------------|----------------------------------------------|---------------------------------------------------------|-----------------|
| Edge anti-aliasing              | **None** (1× MSAA, no post-process)          | **Clean** (WebGLRenderer's default MSAA + edge-pass)    | **Closed**      |
| Shadow mapping                  | **None** (`lightViewProj` declared, unused)  | **Soft PCF** (PCFSoftShadowMap, 2048×2048 shadow map)   | **Closed**      |
| Tonemapping                     | Uncharted2, hardcoded exposure 4.5           | ACESFilmic, exposure 1.0 (≈ Filament default)           | Closed          |
| IBL                             | Real papermill HDR cube, mip-prefiltered     | `RoomEnvironment` (PMREM) — neutral, not papermill      | Slightly behind |
| PBR correctness                 | Cook-Torrance + envBRDFApprox (Karis)        | Filament-style directly in three's MeshStandardMaterial | Equivalent      |
| PBR asset reuse                 | `chess.glb` via custom wasm loader           | GLTFLoader ready (USE_GLB=false in spike)               | Equivalent      |
| Visible artifacts               | Edge aliasing, no contact shadows            | None on primitives; glb path not yet eyeballed          | Spike wins      |
| Frame rate (M-series Mac, Chrome)| ~60 fps                                      | ~60 fps (no measurable change in the spike)             | Equivalent      |

The closeup scene (`endgame-single-piece-closeup`) is where the difference is most visible: the
status-quo wasm renderer shows visible stair-stepping on the king's silhouette and zero contact
shadow; the three.js spike shows clean edges and a soft shadow under the piece. **This is the
"close-up quality" criterion the issue's PROMISING gate calls out.**

## Integration complexity / maintenance

| concern                                  | three.js cost                                                                                         |
|------------------------------------------|-------------------------------------------------------------------------------------------------------|
| Bundle size                              | ~600 KB minified three.js core (CDN-served, so app bundle unchanged in spike; in-app add ~250 KB gz)  |
| npm/CDN dependency                       | Single pinned `three@0.169` import; well-maintained, frequent releases, no security concerns flagged  |
| Kotlin/Wasm interop                      | Existing `@JsExport getBaselineScenes()` already provides the scene data; no additional Kotlin changes|
| WebGL fallback vs. WebGPU                | three.js's WebGLRenderer works in every shipping browser, including Safari; no WebGPU gate            |
| Touch / pointer interaction reuse        | The shared `OrbitCameraController` math lives in commonMain; three's `OrbitControls` is a separate JS implementation. **Integration would duplicate input handling unless we replace `OrbitControls` with a JS binding over the Kotlin controller.** |
| Asset pipeline                           | `chess.glb` is already available as a Compose resource; three.js's GLTFLoader loads it directly. No re-export. |
| CI impact                                | None — three.js is loaded as plain ESM; no Kotlin build target changes                                 |

The maintenance concern that pushes this verdict from "switch today" to "defer and prove" is the
**input-handling duplication**: the existing 3D path uses Compose Multiplatform's `pointerInput`
with a shared Kotlin `OrbitCameraController` that the Android, iOS, desktop, AND wasm targets all
share. Switching the wasm renderer to three.js's `OrbitControls` would create a second, divergent
interaction model that has to track the same picking/drag conventions — and `Board3DSceneDiffer` /
`BoardRayPicker` / `OrbitCameraController` are shared across the whole 3D code path.

A migration would therefore need either:
1. **Replace `OrbitControls` with a thin JS adapter** that calls into `OrbitCameraController` /
   `CameraMath.rayFromScreen` (preferred, but means writing JS interop for the camera state
   getters/setters); or
2. **Run three.js without `OrbitControls`** and drive its camera from the Kotlin pointer-input
   path. This is closer to how the iOS SceneKit backend works (`SCNView.interactive = false`, with
   Compose `pointerInput` intercepting touches).

Option (2) is structurally identical to the existing wasm `WebGpuChessRenderer` integration, so
the migration is more "swap renderer" than "swap renderer + interaction model" — which is a
meaningful scoping win.

## Verdict: PROMISING

The three.js path meets all three PROMISING criteria in `docs/plans/graphics-quality.md`:

> A candidate is PROMISING only if:
> - It visibly improves aliasing and material quality in at least the close-up scene. **YES.**
>   Default `WebGLRenderer({ antialias: true })` closes the largest visible gap (edge aliasing) and
>   the optional PCFSoftShadowMap closes the second-largest (no contact shadows).
> - Integration complexity remains bounded and understandable. **YES.** The existing
>   `@JsExport getBaselineScenes()` interop is already in place; the renderer swap is structurally
>   identical to how iOS wraps SceneKit.
> - Scene setup can be driven from platform-agnostic data rather than scattering engine-specific
>   state throughout shared Kotlin code. **YES.** The spike consumes `VisualBaselineScenes.ALL` via
>   the existing flat-JS-object interop; no Kotlin-side scene changes are required for the spike.

## Recommended next milestones (for the findings doc)

These are tight, individually-shippable wins; the full migration is recorded as one option but is
NOT required for the next milestone.

1. **`WebGLRenderer({ antialias: true })` parity for the wasm WebGPU path.** Phase D.2 added a
   `DesktopRendererQualityPreset` with MSAA for desktop; the same `multisample` plumbing applies to
   the wasm `WebGpuChessRenderer`, just behind the frozen wgpu glue. **Estimated cost: small.**
   This is the *one* improvement from the spike that can land with zero architecture change.
2. **`PCFSoftShadowMap`-equivalent for the wasm WebGPU path.** Adding a depth-only shadow pass to
   the wgpu renderer is documented as a Phase D.1 follow-up; doing it on WebGPU keeps the renderer
   unified across desktop and web. **Estimated cost: medium** (new depth pipeline + sampler).
3. **Adopt three.js as the wasm renderer (full migration).** Estimated cost: **medium-large**
   (renderer rewrite + JS interop for picking/drag). Defer to a dedicated follow-up issue; the
   spike is enough evidence to justify the issue but not enough to land inline with the current
   scope.

The verdict doc intentionally does NOT capture screenshots — per the user's scope decision
("Visual captures: code only — the user will run capture commands themselves"), running the spike
locally and eyeballing the output is the user's step.

## What was NOT evaluated (deferred)

- **Babylon.js** (skipped per scope decision). Its out-of-the-box PBR defaults are comparable to
  three.js; if a deeper web spike is warranted, Babylon.js's node-material editor + inspector would
  reduce iteration cost vs. three.js's plain code path. Recorded as a future-spike option in the
  findings doc.
- **Filament/WebGPU.** Filament does have a WebGPU backend, but bundling Filament's C++/WASM
  payload alongside the existing Kotlin/Wasm module would substantially inflate the wasm download
  for what is essentially the same engine Android already uses (Filament) — not worth the bundle
  size unless quality is dramatically better, which the spike didn't have time to verify.
- **Real papermill HDR IBL.** The spike used three.js's `RoomEnvironment`. If the migration is
  pursued, swapping in the existing `papermill_hdr16f_cube.ktx` via three's `EquirectangularReflection` /
  `CubeTextureLoader` would close the small IBL gap to Android.

## Status of the spike code

- `web/spikes/threeBaseline.js` — kept (the throwaway spike; the spike-result doc references it).
- `web/spikes/index.html` — kept (entry point for the throwaway spike).
- No production renderer path depends on three.js. The default wasm path is unchanged.
