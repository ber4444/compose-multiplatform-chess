# Phase C — iOS graphics spike result (three.js via WKWebView adopted)

> Verdict: **SWITCH TO three.js via WKWebView**. RealityKit was evaluated and rejected after
> multiple attempts — it cannot load `.glb` natively, USDZ conversion loses materials, and even
> a native Sketchfab `.usdz` with embedded PBR materials rendered as a flat gray rectangle due to
> RealityKit's limited `UsdPreviewSurface` material support. SceneKit remains the shipping
> renderer. three.js via WKWebView is the chosen successor because it produces Android-quality
> rendering with the same `chess.glb` asset, as confirmed by visual captures.

This document is the final Phase C deliverable for `docs/plans/graphics-quality.md`. It records
every renderer evaluated for iOS, why each was rejected, and the evidence that drove the
three.js decision.

## Renderers evaluated

| renderer | result | reason |
|---|---|---|
| **SceneKit** (shipping) | Low quality | Hard shadows, ~5% warm tint, limited PBR fidelity vs Android Filament. |
| **RealityKit** (glb → USDZ via ModelIO) | **FAIL** | ModelIO cannot load `.glb` (binary glTF). Only `.gltf`/`.obj`/`.usdz`. |
| **RealityKit** (manual USDA writer) | **FAIL** | Geometry loaded after adding `faceVertexCounts`, but entities were plain `Entity` not `ModelEntity`. Fixed by using `Entity.loadAsync` (not `loadModelAsync`). Materials not embedded in the hand-written USDA → pieces rendered untextured. |
| **RealityKit** (Sketchfab `.usdz`) | **FAIL** | Model loaded natively via `Entity.loadModel(contentsOf:)`, all 68 meshes + 8 materials. But rendered as a **flat gray rectangle** — RealityKit's `UsdPreviewSurface` material support is too limited for the Sketchfab USDZ's material network. |
| **three.js via WKWebView** | **SUCCESS** | `bundle.js` (three.js r169 + OrbitControls + GLTFLoader + RoomEnvironment + chess code, bundled via esbuild) loaded via `WKWebView.loadHTMLString(_:baseURL:)`. Renders `chess.glb` with full PBR + shadows + ACES tonemapping. Screenshot: 1.37MB / 96.2% non-dark content — visually matches the web target and Android reference. |

## Why three.js wins

1. **Same asset as Android.** three.js's `GLTFLoader` loads `chess.glb` natively — the same file
   the Android Filament renderer uses. No conversion, no format mismatch, no material loss.
2. **Same PBR pipeline as the web target.** `WebGLRenderer` + `MeshStandardMaterial` +
   `ACESFilmicToneMapping` + `PCFSoftShadowMap` — the exact stack the web spike uses, confirmed
   visually by the user ("wow this is really cool now").
3. **WKWebView is a first-class rendering surface on iOS.** Apple's WebKit supports WebGL2, ES
   modules (when bundled), and runs at native frame rates. Many iOS apps use WKWebView for
   complex graphics (AR experiences, data viz, games).
4. **No format conversion.** Every native Apple renderer (SceneKit, RealityKit, ModelIO)
   required converting `chess.glb` to another format (OBJ, USDA, USDZ), and every conversion
   lost material/texture data. WKWebView + three.js loads the original `.glb` directly.

## Architecture (chosen path)

```
iosApp/iosApp/
├── ContentView.swift          # routes CHESS_BASELINE_DEMO=1 → ThreeJsChessView
├── ThreeJsChessView.swift     # UIViewRepresentable wrapping WKWebView
├── Resources/
│   ├── baseline.html          # minimal host page (loads bundle.js via <script src>)
│   ├── bundle.js              # esbuild bundle: three.js + addons + chess rendering (1.1 MB)
│   └── chess.glb              # same glTF asset Android/web/desktop use (5.7 MB)
```

The `bundle.js` is produced by esbuild from the same `threeBaseline.js` source that the web
spike uses, with three.js and its addons resolved from the npm package. This means the iOS and
web targets share the exact same rendering code — the only difference is the host (WKWebView vs
browser tab).

## Production migration (separate follow-up)

The current integration is a dev-mode spike (`CHESS_BASELINE_DEMO=1`). The production migration
would:

1. Replace `IosSceneKitChessRenderer` with a `WKWebViewChessRenderer` that implements
   `Chess3DBoardRenderer` via the existing `Chess3DSurface` / `Board3DInput` interfaces.
2. Wire the shared `OrbitCameraController` / `BoardRayPicker` camera + picking via JS-to-Kotlin
   bridges (WKScriptMessageHandler for Kotlin→JS, evaluateJavaScript for JS→Kotlin).
3. Bundle `bundle.js` + `chess.glb` in the production app (not just the dev spike).
4. Add camera drag / pinch zoom / tap-to-move via the same gesture pipeline that drives the
   SceneKit path today (the WKWebView's touch events are intercepted by a transparent overlay
   that carries the Compose `pointerInput`, mirroring how SceneKit's `UIKitView` is set up).

## What was definitively rejected

- **RealityKit** — cannot load `.glb`; USDZ conversion loses materials; native Sketchfab USDZ
  materials render as gray. Not viable for this project's asset pipeline.
- **Custom Metal** — engineering cost is an order of magnitude larger than three.js for the same
  visual payoff. SceneKit already uses Metal under the hood.
- **Filament on iOS** — Filament's iOS distribution is static-library-only (`.a` files inside
  xcframeworks), requires manual module-map / bridging-header / static-linking setup, and has no
  ready-to-use Swift Package Manager integration. The integration cost is real but bounded;
  recorded as a fallback option if three.js via WKWebView hits a blocking limitation in
  production (e.g., touch latency, WebGL2 feature gaps).
