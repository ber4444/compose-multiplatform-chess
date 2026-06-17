# Issue #32 - M8: wgpu4k mobile target spike

**Status:** completed — no-go for replacing mobile native renderers in this branch.
**Scope:** validate whether Android and iOS can move from native renderers to the shared WebGPU/WGSL renderer without taking on new platform surface glue.
**Why now:** the article draft frames this branch as a practical golden path for KMP/CMP 3D. That claim should be based on evidence about mobile WebGPU feasibility rather than assuming the native mobile backends are temporary.

---

## 1. Current facts

The branch currently ships:

- desktop: `DesktopWgpuChessRenderer` using wgpu4k/WebGPU plus offscreen readback to Compose `ImageBitmap`
- web/wasm: `WebGpuChessRenderer` using the same shared WGSL/geometry into an overlay canvas
- iOS: `IosSceneKitChessRenderer`
- Android: `AndroidSceneViewChessRenderer` over SceneView/Filament

The result of this spike is that SceneKit and Filament remain the mobile product backends. Android WebGPU surface ownership would require new JNI/NDK glue.

Upstream status to verify at spike start:

- `wgpu4k` describes itself as a WebGPU binding across Web, Desktop, and Mobile, but its README labels Android and iOS examples experimental.
- `wgpu4k-native` describes itself as a WebGPU binding compatible with Desktop and Mobile and has Android/iOS demo instructions.
- This repo currently depends on `io.ygdrasil:wgpu4k-toolkit:0.2.0-SNAPSHOT` for desktop/wasm through the GitLab Maven repo in `settings.gradle.kts`.

Primary references:

- <https://github.com/wgpu4k/wgpu4k>
- <https://github.com/wgpu4k/wgpu4k-native>
- <https://github.com/gfx-rs/wgpu>

---

## 2. Spike questions

Answer these in order. Stop at the first hard no-go and record the exact failure.

1. **Dependency resolution:** can this repo resolve wgpu4k or wgpu4k-native artifacts for `androidMain`, `iosArm64Main`, and `iosSimulatorArm64Main` without breaking existing desktop/wasm resolution?
2. **Kotlin/Gradle compatibility:** do the mobile artifacts compile with this repo's Kotlin/Gradle/AGP stack and manual source-set hierarchy?
3. **Surface ownership:** can the renderer attach to a caller-owned Android `Surface`/`SurfaceView` and iOS `CAMetalLayer`/native surface from Compose interop without owning the full activity/view-controller hierarchy?
4. **Swapchain/render loop:** can mobile render on demand, or at least throttle to a dirty-frame loop, instead of forcing a hot continuous render loop?
5. **Shared shader reuse:** can `app/src/wgpuMain/.../WgpuShaders.kt` compile and run unchanged or with only preprocessor-free common WGSL changes?
6. **Asset reuse:** can the current `chess.glb` and papermill environment resources load on mobile through common Compose resources, without platform-specific conversion beyond what already exists?
7. **Input/lifecycle fit:** can the existing `Board3DHost` remain the owner of camera, gestures, picking, selection, transitions, `detach()`, and `dispose()`?
8. **Visual parity:** can a mobile WebGPU renderer match or exceed the current SceneKit/Filament look: board framing, PBR/IBL, skybox/background, selection highlight, and piece transforms?
9. **Performance/thermal:** can a representative device hold acceptable latency, memory, and thermals for camera movement and move animation?
10. **CI story:** can Android/iOS mobile tests at least compile and run smoke tests, with GPU render checks marked opt-in if runners cannot provide real GPU access?

---

## 3. Spike branch rules

Suggested branch: `issue-32-m8-wgpu4k-mobile-spike`.

Keep the spike isolated:

- Do not delete or rewrite the existing SceneKit/Filament renderers.
- Do not change the public `Chess3DBoardRenderer` contract unless the change also improves existing backends.
- Do not move platform-specific view/surface types into `commonMain`.
- Do not refactor the game UI while validating renderer feasibility.
- Prefer new experimental files with names like `AndroidWgpuChessRenderer`, `IosWgpuChessRenderer`, `AndroidWgpuBoard3D`, and `IosWgpuBoard3D`.

The spike should be disposable until it proves all gates.

---

## 4. Implementation sketch

### 4.1 Source sets

Try the least invasive wiring first:

```text
commonMain
  wgpuMain
    desktopMain
    wasmJsMain
    androidMain?       # only if toolkit artifacts support it cleanly
    iosMain?           # only if toolkit artifacts support it cleanly
```

If `wgpu4k-toolkit` cannot support this directly, the spike may try `wgpu4k-native` in a temporary mobile-specific intermediate source set:

```text
commonMain
  wgpuMain             # shared WGSL, scene geometry, pure Kotlin helpers
  wgpuNativeMain       # wgpu4k-native binding glue
    androidMain
    iosMain
```

This is only a feasibility probe; the mobile product backends remain SceneKit and Filament unless the probe clears all gates without new platform surface ownership.

### 4.2 Android surface

Prototype:

- `AndroidWgpuChessSurface` wrapping a caller-owned `Surface` plus physical size
- `AndroidWgpuBoard3DSurface` hosted from Compose, ideally with the same transparent gesture overlay used by SceneView
- `AndroidWgpuChessRenderer` implementing `Chess3DBoardRenderer`

Minimum proof:

- Android app starts
- 3D toggle creates the renderer
- a simple clear color or triangle presents to the surface
- then the chess scene presents
- Compose dialogs still layer correctly or a documented fallback exists

### 4.3 iOS surface

Prototype:

- `IosWgpuChessSurface` wrapping a `CAMetalLayer` or the wgpu4k-native supported iOS surface handle
- `IosWgpuBoard3DSurface` hosted from `UIKitView`
- `IosWgpuChessRenderer` implementing `Chess3DBoardRenderer`

Minimum proof:

- `:app:linkDebugFrameworkIosSimulatorArm64` succeeds
- simulator or device creates the surface without crashing
- a simple clear color or triangle presents
- then the chess scene presents
- Compose pointer input remains the interaction layer

### 4.4 Shared renderer extraction

If the surface prototypes work, extract the desktop/wasm shared pieces into a reusable core:

- scene-to-draw-list conversion
- uniform packing
- material selection
- WGSL strings
- environment binding layout
- transition interpolation

Keep platform-specific code limited to:

- surface creation
- adapter/device/swapchain setup
- frame presentation/readback differences
- asset byte loading quirks

---

## 5. Verification matrix

| Gate | Android | iOS | Pass condition |
|---|---|---|---|
| Dependency resolution | `:app:compileDebugKotlinAndroid` | `:app:linkDebugFrameworkIosSimulatorArm64` | no Gradle/KLIB/metadata failures |
| Surface smoke | app/device test | simulator/device smoke | clear color or triangle presents |
| Chess scene smoke | app/device test | simulator/device screenshot | board and pieces visible |
| Shared interaction | fake or real UI test | fake or real UI test | tap-to-move still routed by `Board3DHost` |
| Dialog layering | promotion dialog test | promotion dialog test | dialog visible above 3D surface or documented limitation |
| Visual parity | screenshot compare | screenshot compare | no worse than current native backend |
| Thermal/perf | manual/device run | manual/device run | no continuous hot render loop for static board |

---

## 6. Spike Result

Date: 2026-06-16
wgpu4k version: `0.2.0-SNAPSHOT`
wgpu4k-native version: `v27.0.4`
Kotlin/Gradle/AGP versions: Kotlin `2.3.20`, Gradle `9.3.1`, AGP `9.1.1`

Android:
- Dependency resolution: PASS. `:app:compileAndroidMain` succeeds with a new `wgpuNativeMain` source set depending on `io.ygdrasil:wgpu4k-native:v27.0.4`. The plan's original `:app:compileDebugKotlinAndroid` task does not exist with this Android KMP plugin layout; `compileAndroidMain` is the equivalent main-source compile gate.
- Surface creation: NO-GO for this spike. The Android AAR exposes raw `WGPUSurfaceSourceAndroidNativeWindow`, whose `window` field requires an `ANativeWindow*`/native address. The artifact contains `libwgpu4k.so` and generated binding classes, but no helper that converts a caller-owned `android.view.Surface` or `SurfaceHolder` to the required `ANativeWindow*`. Proceeding would require adding new JNI/NDK platform glue, so the spike stops at the first hard no-go rather than rewriting the existing Filament backend.
- First frame: not attempted after Android surface no-go.
- Chess scene: not attempted.
- Dialog layering: not attempted.
- Performance/thermal: not attempted.
- Verdict: Keep `AndroidSceneViewChessRenderer`. Any revisit must be a separately scoped JNI/NDK surface-ownership spike, not a renderer dependency upgrade.

iOS:
- Dependency resolution: PASS. `:app:linkDebugFrameworkIosSimulatorArm64` and `:app:linkDebugFrameworkIosArm64` both succeed with `wgpu4k-native:v27.0.4`.
- Surface creation: not implemented because the ordered spike stopped at the Android surface no-go. The binding does expose `WGPUSurfaceSourceMetalLayer`, but the spike did not pursue an iOS-only replacement.
- First frame: not attempted.
- Chess scene: not attempted.
- Dialog layering: not attempted.
- Performance/thermal: not attempted.
- Verdict: Keep `IosSceneKitChessRenderer`. The spike did not justify replacing the mobile native renderer stack.

Overall decision:
- [ ] Replace Android SceneView/Filament now
- [ ] Replace iOS SceneKit now
- [x] Keep native mobile renderers and require a separate surface-ownership spike before any WebGPU replacement
- [ ] Hybrid: replace one mobile target only

Implementation notes:
- The compile probe temporarily split `wgpuMain` into pure shared WGSL/geometry helpers and added a disposable `wgpuNativeMain` source set with `wgpu4k-native`.
- That temporary code proved mobile metadata compatibility and shared shader visibility without entering the app renderer graph.
- The probe code was removed after recording this result so the branch does not carry unused mobile `wgpu4k-native` runtime artifacts.

---

## 7. Article Footnote Wording

> The M8 mobile WebGPU spike proved dependency and Kotlin/Native compatibility, but stopped at Android
> surface ownership: the binding requires `ANativeWindow*`, and the Android JVM artifact does not provide
> a caller-owned `Surface`/`SurfaceHolder` bridge. The golden path remains shared game/scene/interaction
> logic plus platform-native mobile renderers.
