# Issue #XX — Non-Android Graphics Quality Parity (Web, iOS, Desktop)

> **Status: COMPLETED.** All phases (A–E) delivered. Three.js adopted for web + iOS (via WKWebView);
> desktop shipped a `HIGH_QUALITY` preset (4× MSAA + 2048² PCF shadow mapping). See verdicts in
> `docs/plans/web-graphics-spike-result.md` (ADOPTED), `docs/plans/ios-graphics-spike-result.md`
> (ADOPTED — RealityKit rejected), and `docs/graphics/non-android-quality-findings.md`.

> Self-contained plan for an AI coding agent. Repo: `compose-multiplatform-chess`. Paths relative to repo root. Suggested branch: `graphics-non-android-parity`.

## Context and decisions (final — do not revisit)

The planning prompt in the screenshot was: “Write me a plan for improving graphics quality on all platforms except android (which is by far the highest quality now). For web, spike into Babylon.js, three.js and Filament over WebGPU. On iOS, spike into RealityKit, Iray, Filament on iOS and custom Metal renderers.”

The project already uses Compose Multiplatform to share UI and logic across Android, iOS, desktop, and web, with Android currently acting as the visual reference platform.[1]

Decisions:

1. **Android is the gold-standard reference.**
   - Do not change the Android renderer in this issue beyond adding a reference preset for material and lighting values.
   - All non-Android work measures quality as how closely a platform matches Android under the same scene, materials, camera, and resolution.

2. **Renderer abstraction stays as-is.**
   - Keep the existing `Chess3DBoardRenderer` / `Board3DScene` / `Board3DSurface` abstraction unchanged across platforms.
   - New technology such as RealityKit, Metal, Babylon.js, three.js, or Filament must be wrapped behind that interface instead of leaking engine-specific types into `commonMain`.

3. **This issue is investigation plus targeted spikes, not a migration.**
   - The goal is to measure quality gaps, spike candidate technologies, pick a direction per platform, and land low-risk improvements that do not explode scope.
   - Any engine replacement such as adopting Babylon.js on web or rewriting iOS on Metal is deferred to future milestones.

4. **Priority order: Web, then iOS, then desktop.**
   - Web gets first-class treatment because the prompt explicitly calls out Babylon.js, three.js, and Filament, and because browser graphics constraints differ sharply from Android native rendering.
   - iOS comes next, focusing on RealityKit, Metal, and related native rendering options.
   - Desktop is last and receives polish passes unless a spike reveals a clear low-risk win.

5. **No new public APIs in `commonMain`.**
   - Do not change any public `commonMain` API signatures.
   - All new functionality belongs in platform-specific implementations or test-only helpers.

## Execution rules

- All work must be reproducible by CI on the existing matrix: Android, iOS simulator, desktop, and wasm/web.
- No step may require proprietary tools beyond the standard platform toolchains already required by the repo.
- Each spike is timeboxed; when time expires, record a verdict and move on.
- For each platform, default to keeping the current renderer unless a candidate clearly improves quality with acceptable integration and maintenance cost.

## Phase A — Baseline capture (all platforms)

Goal: establish visual and performance baselines for Android as reference, plus iOS, desktop, and web using the current renderer stacks.

### Tasks

1. **Create canonical visual test scenes.**
   - Add `board3d/VisualBaselineScenes.kt` under `app/src/commonMain/.../board3d/` with a compact set of labeled scenes:
     - `Scene.START_POSITION_HIGH_LIGHTING`
     - `Scene.MIDGAME_SHADOWS`
     - `Scene.ENDGAME_SINGLE_PIECE_CLOSEUP`
   - Express each scene only in terms of existing `Board3DScene` abstractions so the same scene definition can run everywhere.

2. **Add a baseline-capture debug entry point per platform.**
   - Android: reuse the existing 3D test harness and add a dev-menu action that renders each scene at fixed resolution and writes PNGs.
   - Desktop: add an offscreen smoke test that writes PNGs under `app/build/baseline/desktop/`.
   - iOS: add a test-only baseline renderer path in `iosSimulatorArm64Test` that captures fixed-size screenshots from the current iOS renderer.
   - Web: add a dev-only route such as `/#baseline` that renders each scene and exports canvas captures through JS interop.

3. **Capture Android as the golden reference.**
   - Commit a curated set of Android PNGs under `docs/assets/baselines/android/` for human inspection.
   - Use stable filenames such as `scene-start-high-lighting-android.png`.

4. **Record qualitative notes.**
   - Add `docs/graphics/baseline-notes.md` with per-platform observations covering aliasing, highlights, shadow softness, tonemapping, color differences, and visible artifacts.

### Definition of done

- All four platforms can render the canonical scenes at fixed resolution on demand.
- Android reference PNGs are committed.
- `baseline-notes.md` exists with at least one paragraph per platform.

## Phase B — Web graphics spike (Babylon.js, three.js, Filament/WebGPU)

Goal: determine whether a dedicated browser engine can materially improve visual quality and maintainability for the web target.

### Timebox

- **3 working days equivalent** for the entire spike.
- This phase is throwaway unless explicitly promoted later.

### Tasks

1. **Set up a JS interop playground.**
   - Under `web/` or a wasm-specific interop package, add a minimal page that hosts a canvas and boots the existing Compose wasm app in headless-board mode.
   - Expose a Kotlin `@JsExport` function such as `getBaselineScenes(): SceneDescription[]` so JS spike code can pull platform-agnostic scene descriptions.

2. **Spike Babylon.js and three.js.**
   - Add plain JS modules under `web/spikes/`:
     - `babylonBaseline.js`
     - `threeBaseline.js`
   - Each module should render at least one baseline scene using PBR materials and a fixed camera.
   - Start with primitive meshes if needed, then move to `chess.glb` only if the basic path succeeds quickly.
   - Measure visual quality against Android screenshots and record rough FPS in Chrome dev tools.

3. **Optional Filament mini-spike.**
   - Attempt Filament only if its browser path is clearly viable from available docs/examples.
   - If tooling friction exceeds about half a day, stop and document the reason.

4. **Document results.**
   - Create `docs/plans/web-graphics-spike-result.md` with a table covering status quo web renderer, Babylon.js, three.js, and Filament if attempted.
   - Compare build complexity, runtime size impact, visual quality relative to Android, Kotlin/wasm interop ergonomics, and a verdict: `KEEP`, `PROMISING`, or `NO-GO`.
   - Save representative captures under `docs/assets/spikes/web/`.

### Go / no-go criteria

A candidate is **PROMISING** only if:

- It visibly improves aliasing and material quality in at least the close-up scene.
- Integration complexity remains bounded and understandable.
- Scene setup can be driven from platform-agnostic data rather than scattering engine-specific state throughout shared Kotlin code.

### Definition of done

- Web spike code runs locally with the repo’s normal web workflow.
- `web-graphics-spike-result.md` exists with explicit verdicts.
- No production renderer path depends on Babylon.js, three.js, or Filament yet.

## Phase C — iOS graphics spike (RealityKit, Metal, Filament)

Goal: determine whether a higher-fidelity iOS renderer is worth adopting, given candidates such as RealityKit, custom Metal, and optionally Filament.

### Timebox

- **3 working days equivalent** for the whole spike.

### Tasks

1. **RealityKit proof of concept.**
   - Add an experiment-only `IosRealityKitChessRenderer` in `app/src/iosMain/.../board3d/` behind a dev flag.
   - It should render at least the start-position scene using a native iOS 3D view and fixed camera/material setup.
   - Wire a dev-only entry point so `Board3DSupport` can instantiate it without affecting production defaults.

2. **Custom Metal proof of concept.**
   - Add `IosMetalChessRenderer` backed by `CAMetalLayer` and Metal shaders.
   - Implement a minimal forward renderer with depth buffering, directional lighting, and enough PBR-like material control to compare against Android.
   - Reuse existing `Board3DSceneMapper` and shared math utilities rather than duplicating chess-scene logic.

3. **Optional Filament/iOS mini-spike.**
   - Attempt Filament integration only if the iOS path appears realistic from examples and does not derail the phase.
   - Abort quickly if build integration or Kotlin/Native interop cost is too high.

4. **Capture comparisons.**
   - For each renderer candidate, capture screenshots of all baseline scenes at the same resolution on the same simulator or device class.
   - Record differences versus Android in material richness, jagged edges, lighting, and any interaction or lifecycle issues.

5. **Document results.**
   - Create `docs/plans/ios-graphics-spike-result.md` with pros/cons, tooling complexity, maintenance risk, and an explicit verdict: `KEEP CURRENT`, `SWITCH TO X`, or `DEFER`.

### Go / no-go criteria

A follow-up milestone should only adopt a new iOS renderer if it:

- Moves visual quality noticeably closer to Android.
- Plays well with Compose iOS embedding, overlays, dialogs, touch handling, and lifecycle.
- Is stable enough to exercise in CI on a simulator without chronic flakiness.

### Definition of done

- `IosRealityKitChessRenderer` and `IosMetalChessRenderer` exist behind development flags and can draw at least one baseline scene.
- `ios-graphics-spike-result.md` is written with verdicts and saved screenshots.
- The default iOS shipping path remains unchanged.

## Phase D — Desktop renderer polish

Goal: identify low-risk improvements to the existing desktop renderer that move its visuals closer to Android without changing engines.

### Tasks

1. **Audit current desktop renderer configuration.**
   - Document current MSAA, anisotropic filtering, tone mapping or gamma behavior, shadow quality, and texture usage in `docs/graphics/desktop-renderer-notes.md`.

2. **Introduce a high-quality preset.**
   - Add a config object such as `DesktopRendererQualityPreset` with at least `DEFAULT` and `HIGH_QUALITY`.
   - Tune `HIGH_QUALITY` for improved filtering, antialiasing, and shadow fidelity on capable hardware.
   - Expose the preset through a developer-only flag or environment variable.

3. **Measure performance impact.**
   - Extend the desktop baseline smoke test to record approximate frame times for a continuous orbit under both presets.
   - Document the cost and set a practical guideline for acceptable regression.

4. **Optional shared PBR tuning pass.**
   - If easy, centralize roughness, metalness, ambient intensity, and related material constants so desktop and Android can converge visually without changing architecture.

### Definition of done

- Desktop supports at least two documented quality presets.
- Baseline captures and frame-timing notes exist for both presets.
- The default desktop path remains stable and shipping-safe.

## Phase E — Findings and follow-up issues

Goal: consolidate findings, choose next steps, and split any real migrations into separate issues.

### Tasks

1. **Write a findings doc.**
   - Create `docs/graphics/non-android-quality-findings.md` with one section per platform summarizing baseline gaps and spike outcomes.
   - Include a table mapping each platform to its recommended next milestone.

2. **Open follow-up GitHub issues.**
   - Create one issue per platform recommendation, each linking back to this plan and the relevant spike-result document.
   - Keep every follow-up tightly scoped to one platform and one engine decision.

### Global definition of done

- Baseline capture flows exist for Android, iOS, desktop, and web. ✅
- Web and iOS spikes are completed and documented. ✅
- Desktop has a documented quality-preset path. ✅
- A findings document summarizes the investigation and points to concrete follow-up issues. ✅

## Prior work (issue-32 M7 — folded in from `issue-32-3d-ui-m7-apple-android-fidelity.md`)

M7's goal was to bring the "vkChess look" (papermill HDR environment + image-based lighting +
filmic tonemapping) to the iOS (SceneKit) and Android (Filament) 3D renderers, matching what
M6 (desktop) + M4 (web) achieved on WebGPU.

### Android (Filament) — completed, became the reference

Android's SceneView/Filament path got the papermill IBL + skybox assets generated offline via
Filament's `cmgen` and bundled as `files/env/papermill_ibl.ktx` + `files/env/papermill_skybox.ktx`.
`AndroidBoard3DSurface` validates those Compose resources before reporting support, then passes
their Android asset paths to SceneView's `EnvironmentLoader.createKTX1Environment`. This is the
gold-standard reference renderer that the graphics-quality plan measures every other platform
against.

### iOS (SceneKit) — implemented but quality was insufficient

M7 wired the papermill cube-map environment into `IosSceneKitChessRenderer`:
`scene.lightingEnvironment.contents` = 6 EXR cube faces via Core Image,
`scene.background.contents` = same cube, `cam.wantsHDR = true`, PBR materials with
roughness/metalness tuned per piece kind. This was an improvement over the pre-M7 flat-color
lighting environment but still fell short of Android Filament's visual quality — hard shadow
edges, ~5% warm tint from the Core Image cube decode, and ~¼ stop early highlight clipping.

That quality gap is what triggered this graphics-quality plan. After spiking RealityKit (rejected
— can't load `.glb`, USDZ conversion loses materials, Sketchfab USDZ materials render as flat
gray) and Filament-on-iOS (rejected — static-lib-only distribution, no SPM), the three.js via
WKWebView path was adopted as the iOS successor renderer (see verdict in
`docs/plans/ios-graphics-spike-result.md`).

### Decisions from M7 (still in effect)

- **Native mobile renderers are the product path** — SceneKit and Filament stay behind the shared
  renderer contract unless a separate JNI/surface-ownership effort is explicitly approved.
- **Asset conversion was the main unknown** — resolved by generating platform-specific assets
  (Filament IBL via cmgen, SceneKit environment via Core Image cube decode from EXR faces).
- **No dynamic shadows on mobile** (match vkChess + the other backends); SceneKit/Filament soft
  shadows are an optional extra, out of scope for parity. (Desktop's `HIGH_QUALITY` preset added
  shadows in Phase D.5; iOS/web will inherit shadows via three.js's `PCFSoftShadowMap`.)
