# Issue #32 — M6: wgpu4k unified WebGPU backend (spike)

Status: **PHASE 0 IN PROGRESS** — research done, build spike pending.

Goal: evaluate collapsing the three native 3D backends (Desktop/LWJGL-Vulkan, iOS/SceneKit,
Android/Filament) plus the missing Web target onto a single Kotlin-first WebGPU stack
([wgpu4k](https://github.com/wgpu4k/wgpu4k)) + WGSL. Approach and rationale: see the approved plan
(`is-this-a-feasible-smooth-lemon.md`). Sequencing is **wasm-first, incremental** — each working
backend stays until its wgpu4k replacement proves parity.

**Relationship to [M4 wasm](issue-32-3d-ui-m4-wasm.md):** M6 *resolves* M4's deferred engine decision
rather than replacing the milestone — wgpu4k is M4's own no-go **option (b)**. M4's canvas-overlay
strategy, `navigator.gpu` fallback, and tests are reused as-is (Phase 2 below); M4's Materia-specific
parts are superseded.

## Phase 0 gate criteria (kill-switch)
1. wgpu4k pins cleanly against this repo's Kotlin 2.3.x / CMP 1.10.x (the Materia failure mode).
2. Minimal textured + shadowed triangle renders on every shipping target.
3. iOS XCFramework integrates with the existing `embedAndSignAppleFrameworkForXcode` phase.
4. Fail any → stop.

## Findings (research)

### 1. Kotlin / CMP compatibility — **PASS** (primary kill-switch cleared)
- wgpu4k `main` `gradle/libs.versions.toml` declares **Kotlin 2.3.21**; this repo is on **2.3.20**
  (`gradle/libs.versions.toml:5`) — same 2.3.x line, wgpu4k is one patch *ahead*. No klib
  forward-compat break (the exact risk that killed Materia in M1–M3).
- wgpu4k tracks current Compose tooling (activity-compose 1.13.0). This repo's CMP 1.10.3 / Compose
  UI 1.10.5 are compatible-era.

### 2. Coordinates & versioning — **resolved**
- Group **`io.ygdrasil`**. High-level facade artifact: **`io.ygdrasil:wgpu4k-toolkit`** (low-level
  binding is `wgpu4k-native`, version-scheme-follows-wgpu-native at `v27.0.4`).
- **Not on Maven Central.** Published to a **GitLab Maven repo**:
  `https://gitlab.com/api/v4/projects/25805863/packages/maven` (+ `mavenCentral()` + `google()` for
  transitive deps). This must be added to `dependencyResolutionManagement.repositories`. (Confirmed
  via the `hello-cube` example's `settings.gradle.kts`.)
- Published `wgpu4k-toolkit` versions (GitLab `maven-metadata.xml`, updated 2026-05-15):
  `<latest>0.2.0-SNAPSHOT</latest>`, `<release>0.1.0.M2</release>`; line:
  preview-1/2/3, 0.0.3-SNAPSHOT, 0.1.0-SNAPSHOT, 0.1.0.M1, 0.1.0.M2, 0.1.1-SNAPSHOT, 0.2.0-SNAPSHOT.
- **Kotlin alignment:** the Kotlin-2.3.21 build (matching our 2.3.20) is the current **`0.2.0-SNAPSHOT`**.
  The only milestone *release* (`0.1.0.M2`) predates that. **Caveat:** depending on a SNAPSHOT is fine
  for the spike but is a stability risk for any real migration — track upstream for a 0.2.0 release.

### 3. Toolchain prerequisites (verified)
- **Gradle:** the README says wgpu4k needs **9.10+**, but that is its *build* requirement — **consuming
  the published artifact resolved and compiled fine on the existing Gradle 9.3.1**. No wrapper bump
  needed for the spike.
- **JDK:** wgpu4k JVM path uses Panama FFM (needs JDK 22+). Dev machine has **JDK 26** ✓.
- **jvmTarget:** raised the **desktop** target `JVM_11` → **`JVM_22`** (`app/build.gradle.kts:50-54`)
  for FFM; **Android stays `JVM_11`**. Desktop compiled successfully at 22 on JDK 26.

### 4. Backend maturity (per README matrix — may be stale at v27.x; reconfirm)
- Desktop JVM macOS arm64 ✅; wasm/JS 🆗; **Android 🛠️ WIP; iOS 🛠️ WIP**.
- Implication: the gate's "all 5 targets now" bar likely **cannot** pass today — Android and iOS
  backends are work-in-progress. This *aligns* with the wasm-first ordering: the supported targets
  (Web Phase 2, Desktop Phase 5) come first; Android/iOS (Phases 3–4) wait on wgpu4k maturity.
- On Apple, wgpu-native uses **Metal directly (no MoltenVK)** — so iOS risk is maturity, not a
  translation layer.

### 5. Build spike results (desktop) — **kill-switches PASS**
Changes made: `settings.gradle.kts` (GitLab repo, scoped to `io.ygdrasil`), `gradle/libs.versions.toml`
(`wgpu4k = "0.2.0-SNAPSHOT"` + `wgpu4k-toolkit` lib), `app/build.gradle.kts` (desktop dep + jvmTarget 22).
- **Resolution PASS:** `:app:dependencyInsight` resolved
  `io.ygdrasil:wgpu4k-toolkit:0.2.0-SNAPSHOT:20260515.183350-6`, selecting the `jvm` Kotlin platform
  variant. BUILD SUCCESSFUL on Gradle 9.3.1.
- **Binary-compat PASS (the Materia kill-switch):** `:app:compileKotlinDesktop` compiled a probe
  (`app/src/desktopMain/.../board3d/Wgpu4kSpike.kt`) referencing `WGPUContext` + `glfwContextRenderer`.
  Kotlin 2.3.20 reads wgpu4k 0.2.0-SNAPSHOT metadata cleanly.

### 6. API map (for the real renderer)
- JVM entry: `io.ygdrasil.webgpu.glfwContextRenderer(width,height,title,…)` → `GLFWContext { windowHandler, wgpuContext }`.
- `WGPUContext { surface, adapter, device, renderingContext }`; instance via `WGPU.createInstance(backend?)`.
- Headless path: `io.ygdrasil.webgpu.TextureRenderingContext` — render to a texture (maps onto the
  existing desktop offscreen→`ImageBitmap` readback; reuse `rgbaBytesToImageBitmap`).
- Reusable triangle: `wgpu4k-scenes` `HelloTriangle.kt` lives in **commonMain** (cross-target).
- **Adapter needs a surface (verified via `javap` on `wgpu4k-jvm-0.2.0-SNAPSHOT`):**
  `WGPU.requestAdapter(NativeSurface, GPUPowerPreference)` — **no surfaceless overload**. Surface
  factories: `getSurfaceFromMetalLayer(addr)` (macOS), `getSurfaceFromX11Window` / `Wayland` (Linux),
  `getSurfaceFromWindows` (Win), `getSurfaceFromAndroidWindow`. → A headless desktop renderer must
  create an **offscreen `CAMetalLayer`** (via the `io.ygdrasil:rococoa` dep on the graph) and pass its
  address to `getSurfaceFromMetalLayer`, then render into a texture and read back. This is the wgpu4k
  analogue of the current LWJGL headless pipeline and is the first concrete slice of the desktop renderer.
- Native lib ships in `wgpu4k-native-jvm` (`v27.0.4`), extracted/loaded via FFM at runtime.

## Verdict so far
Both desktop kill-switches **PASS** (resolution + Kotlin-2.3.20 binary compat). Realistic near-term gate
is **Desktop + Web only**; Android/iOS deferred until wgpu4k's native targets leave WIP.

## Next steps
1. Desktop **runtime** proof / first renderer slice: create an offscreen `CAMetalLayer` (Rococoa) →
   `getSurfaceFromMetalLayer` → `createInstance()` → `requestAdapter(surface)` → `requestDevice()`
   (FFM loads `libwgpu_native` on macOS arm64) → render `HelloTriangle` → copy texture to buffer →
   `rgbaBytesToImageBitmap`. Wire behind `Chess3DBoardRenderer` as the desktop `Board3DSupport`. Best
   verified visually by running the app (`./gradlew :app:run`).
2. **wasmJs**: add `wgpu4k-toolkit` to `wasmJsMain`, confirm the wasm variant resolves + compiles,
   render the same `HelloTriangle` to a canvas overlay (reuse M4's overlay strategy).
3. Record per-target results + final re-scoped gate verdict.
