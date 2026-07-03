# Issue #54 — Filament on iOS via UIKitView (Metal-native 3D board) — spike result

> **Verdict: ADOPTED as the default iOS 3D backend.** A complete `FilamentIosChessRenderer` is wired
> through the existing `Chess3DBoardRenderer` contract and the normal `iosApp` Debug/Release configs
> now link the official Filament iOS xcframework (v1.72.0). The Filament Metal pipeline **renders the
> full board** — 64 marble tiles + frame, all 32 PBR pieces, and the papermill IBL environment —
> driven by a CADisplayLink at ~60 fps. The previous three.js/WKWebView renderer, generated bundle,
> and opt-in `Debug-Filament` scheme were removed when issue #54 was promoted. Screenshot evidence:
> `build/ios-filament-3d.png` (iPhone 17 simulator), visually matching the Android SceneView reference.
>
> Still needs a hands-on pass: 120 fps on a ProMotion **device** (the simulator caps at 60), pixel-level
> IBL/exposure tuning, and interactive gesture confirmation (orbit/zoom/tap-to-move are wired through
> the shared pipeline but were not exercised by hand here).

This is the deliverable for issue #54. It records the architecture, the linking setup (reproducible
for CI), what is verified vs. still open, and the recommendation.

## Why this was worth prototyping

Before issue #54 was adopted, the iOS 3D board used three.js r169 inside a `WKWebView`
(`WKWebViewChessRenderer`). It looked good and shared the renderer with the web (Wasm) target, but
WebGL inside WebKit is never
direct Metal — it goes `three.js GLSL → ANGLE → Metal`, behind a separate WKWebView process with its
own heap/GPU budget and an IPC boundary, and its render loop runs on WebKit's compositor thread, not
the app's `CADisplayLink`. Android sidesteps all of this with Filament via SceneView (direct
Vulkan/GLES). The iOS equivalent is **Filament targeting Metal directly through a `UIKitView`** — the
subject of this issue.

## Architecture (what was built)

The integration deliberately mirrors two patterns already in the codebase, so nothing about the
shared game/UI layer changes:

1. **Swift conforms to a Kotlin protocol and is injected at the entry point** — exactly how
   `StockfishChessEngine` (Swift) is injected as the Kotlin `ChessEngine` into `MainViewController`.
2. **All the smart logic stays in commonMain; the native view is "dumb"** — FEN→scene mapping, the
   move-arc hop, the selection bounce, frame pacing, the orbit camera, and ray picking stay in shared
   code, which only pushes the encoded `Board3DScene` wire string to the backend.

```
Compose / commonMain                         Kotlin (iosMain)                     Swift / Obj-C++
────────────────────                         ─────────────────                    ───────────────
Board3D (gestures, picker,                    FilamentIosChessRenderer             FilamentChessView : UIView
  OrbitCameraController)                        : Chess3DBoardRenderer               (CAMetalLayer + CADisplayLink)
        │  fen / selection / camera                  │  setScene(encoded)                  │  conforms to
        ▼                                            │  setCamera(encoded)                 ▼  FilamentChessNativeView
  Board3DAnimationDriver  ───── encode() ───────────►│  resize / shutdown  ──────────────► FilamentChessRenderer.mm
  (move arc + bounce, 60fps)                         │                                     (Filament Engine/Metal,
                                                     ▼                                      gltfio, KTX IBL)
                                          metalView() hosted in
                                          UIKitView(interactive=false)
```

### New / changed files

| file | role |
|---|---|
| `app/src/iosMain/.../board3d/FilamentIosChessRenderer.kt` | `Chess3DBoardRenderer` impl + the `FilamentChessViewFactory` / `FilamentChessNativeView` Kotlin protocols (exported as Obj-C protocols) + the `FilamentIosBoard3DSurface` composable that hosts the Metal `UIView`. |
| `app/src/iosMain/.../board3d/IosBoard3D.kt` | `iosBoard3DSupport(filamentFactory)` — routes the iOS board directly to Filament. |
| `app/src/iosMain/.../MainViewController.kt` | requires the Swift `filamentFactory` parameter, forwarded to `iosBoard3DSupport`. |
| `iosApp/iosApp/ContentView.swift` | injects `FilamentChessFactory()` into the Kotlin entry point. |
| `iosApp/iosApp/Filament/FilamentChessRenderer.h/.mm` | Obj-C++ facade + the actual Filament Metal pipeline. |
| `iosApp/iosApp/Filament/FilamentChessView.swift` | `UIView`/`CAMetalLayer`/`CADisplayLink` host conforming to the Kotlin protocol. |
| `iosApp/iosApp/Filament/FilamentChessFactory.swift` | conforms to `FilamentChessViewFactory`. |
| `iosApp/iosApp/Filament/filament.xcconfig` | Filament build settings (bridging header, search paths, link set), applied to normal Debug/Release configs. |
| `iosApp/iosApp/Filament/iosApp-Bridging-Header.h` | exposes the Obj-C++ facade to Swift. |
| `iosApp/project.yml` | wires the normal `iosApp` scheme/configs to the Filament xcconfig. |
| `tools/fetch_filament_ios.sh` | fetches the Filament iOS release + stages the KTX IBL assets. |

### Rendering pipeline (mirrors the Android SceneView backend)

`FilamentChessRenderer.mm` follows Filament's own iOS samples (`hellopbr`, `gltf-viewer`,
`image_based_lighting`) and replicates the Android choices from `AndroidBoard3D.kt`:

- **Engine/SwapChain**: `Engine::create(Backend::METAL)`, `createSwapChain(CAMetalLayer*)`.
- **Asset**: `chess.glb` loaded via `gltfio::AssetLoader::createInstancedAsset(…, MAX_PIECES + 1)` —
  instance 0 is the board (show tiles + frame, hide the 6 piece templates + `Plane`); instances 1..32
  are the piece pool, each showing exactly one template mesh (`king`/`queen`/…) and bound to the
  `white`/`black` material instance — the same scheme as `createInstancedModel(MAX_PIECES + 1)`.
- **Transforms**: every node scaled `0.5` (GLB uses 2-unit squares, the game uses 1-unit) and placed
  at `BoardGeometry.squareCenter`; the per-piece `y` from the wire drives the move-arc hop.
- **IBL**: `papermill_ibl.ktx` (KTX1 with embedded spherical harmonics) → `IndirectLight`, plus
  `papermill_skybox.ktx` → `Skybox` — the same assets Android bundles.
- **Camera**: identical portrait FOV boost to `CameraMath.effectiveFovYRad` / `AndroidBoard3D`, so the
  shared `BoardRayPicker` stays in sync (see `board3d-portrait-fov-picking`).
- **Frame pacing**: a `CADisplayLink` (`preferredFrameRateRange` up to 120) calls `render` →
  `Renderer::beginFrame/render/endFrame`. `Info.plist` already sets
  `CADisableMinimumFrameDurationOnPhone` for ProMotion.

## Build & link setup (reproducible for CI)

The normal `iosApp` Debug/Release builds now link Filament. The fetched libraries remain gitignored
dependency payloads, so a clean checkout needs the fetch script before Xcode builds.

```bash
# 1. Fetch the Filament iOS release (xcframeworks + headers) and stage the KTX IBL assets.
tools/fetch_filament_ios.sh            # defaults to v1.72.0; pass a version to override

# 2. Regenerate the Xcode project (project.yml is the source of truth).
cd iosApp && xcodegen generate

# 3. Build/run the normal iOS scheme (simulator works on Apple Silicon — see below).
xcodebuild -project iosApp.xcodeproj -scheme iosApp \
  -configuration Debug \
  -destination 'platform=iOS Simulator,name=iPhone 17' build
```

Key facts (all confirmed during verification against Filament **v1.72.0**):

- **The release ships per-library `.xcframework`s, not flat `lib/arm64`.** Each xcframework contains an
  `ios-arm64` (device) slice and an `ios-arm64_x86_64-simulator` slice — so the same download builds for
  the **Apple-Silicon simulator** and real devices. `filament.xcconfig` selects the slice per SDK via
  `FILAMENT_SLICE[sdk=...]` and lists one `LIBRARY_SEARCH_PATHS` entry per xcframework slice plus the
  `-l<name>` link set; ld64 resolves the archives order-independently. (This supersedes the original
  worry about a missing arm64-simulator slice — no longer an issue.)
- **`Filament/filament/**` is excluded from the xcodegen `sources` glob** (`project.yml`) — otherwise
  xcodegen tries to add the release's headers/READMEs as target sources ("multiple commands produce
  README.md").
- **KTX IBL must be in the app bundle.** The fetch script copies `papermill_ibl.ktx` /
  `papermill_skybox.ktx` from `app/src/commonMain/composeResources/files/env/` into
  `iosApp/iosApp/Resources/`; re-run `xcodegen generate` so they get a resource build-phase reference.

The fetched libs and staged KTX are gitignored (fetched on demand, like a dependency).

## Status against the acceptance criteria

- [x] **Prototype `FilamentIosChessRenderer` via a UIKitView-hosted Filament Metal surface** — done and
  running; board renders on the iPhone 17 simulator.
- [x] **Load `chess.glb` via gltfio + papermill KTX IBL matching the Android reference** — verified: the
  glb loads (2376 entities, 98 renderables in the scene), the papermill KTX IBL + skybox load, and the
  rendered board (tiles + frame + 32 PBR pieces + environment) visually matches the Android SceneView
  reference. Evidence: `build/ios-filament-3d.png`.
- [~] **Confirm CADisplayLink 60/120 fps without WKWebView overhead** — the CADisplayLink loop runs and
  paces frames at ~60 fps on the simulator (frame timestamps ~16 ms apart). **120 fps needs a ProMotion
  device** (the simulator is 60); not yet measured with Instruments.
- [x] **Document the xcframework linking setup reproducibly for CI** — this doc + `filament.xcconfig`
  + `tools/fetch_filament_ios.sh` + the normal `iosApp` scheme, all exercised end-to-end.
- [x] **Remove three.js/WKWebView from the iOS target** — the Kotlin WKWebView renderer, generated JS
  bundle, host HTML, and `iosApp-Filament` opt-in scheme were removed when Filament became default.

## Verification session (what was actually done)

Built the `iosApp` scheme for the iPhone 17 simulator against Filament v1.72.0 and ran it with
`CHESS_START_3D=1`. The board renders correctly (see screenshot). Bugs found and fixed along the way,
all captured in the committed code:

1. `Ktx1Reader::createTexture` callback is `void(*)(void*)` (one arg), not two.
2. Custom config name → `KOTLIN_FRAMEWORK_BUILD_TYPE=debug` required.
3. The fetched Filament tree under `iosApp/Filament/filament/` had to be excluded from the xcodegen
   sources glob.
4. KTX IBL assets had to be staged into the app bundle (and `xcodegen generate` re-run to reference them).
5. **Sizing:** Compose's `UIKitView` `update` closure did not deliver a non-zero bounds for the hosted
   (pre-created) Metal view, so `resize`/`createRenderer` never ran (black board). Fixed by driving
   drawable sizing from the view's own `layoutSubviews` and making the Kotlin `attach` size-independent.

## What still needs a human-in-the-loop pass

1. **Run on a ProMotion device** and measure 120 fps with Instruments; confirm CADisplayLink pacing.
2. **Look tuning — done against an Android reference.** Filament's default *photographic* camera
   exposure (sunny-16) rendered too dark for the 30000-lux IBL. Tuned to ACES tonemapping (Filament
   default, == three.js) + camera exposure **f/16 1/125 ISO 200** (+1 stop) + IBL **35000** lux, which
   matches the Android SceneView reference (light-gray marble, full tonal range on the pieces, soft
   green bokeh) — verified on the iPad Pro 13" simulator. The remaining `TUNE` markers in
   `FilamentChessRenderer.mm` (sun direction/intensity) are fine to leave but could be micro-adjusted on
   a device.
3. **Interactive gestures:** orbit/zoom/tap-to-move are wired through the shared
   `OrbitCameraController` / `BoardRayPicker` pipeline (the camera is correctly applied — the board
   shows the default white-side view), but exercise drag/pinch/tap by hand to confirm latency/picking.
4. **CI:** the simulator slice exists, so a simulator job can build/run the normal Filament-backed
   iOS scheme after fetching the ~32 MB release.

## Relationship to the earlier rejection

`docs/plans/ios-graphics-spike-result.md` recorded Filament as a *bounded* fallback ("static-library
xcframework, manual module-map / bridging-header / static-linking, no SPM"). This spike pays exactly
that bounded cost: a bridging header, a static-lib link set in an xcconfig, and a fetch script. The
cost is now part of the normal iOS target because Filament is the default backend.
