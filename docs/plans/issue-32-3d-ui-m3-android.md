# Issue #32 — 3D UI, Milestone 3: Android (Vulkan)

Prereqs: [issue-32-3d-ui-overview.md](issue-32-3d-ui-overview.md) and merged [M1](issue-32-3d-ui-m1-foundation.md) (abstraction, `androidDeviceTest` toggle tests, `chess.glb`). Suggested branch: `issue-32-3d-m3`.

Goal: a real 3D backend on Android, injected from `MainActivity`. Vulkan-from-`Surface` on API 24+ is Materia's headline Android feature, so risk is moderate.

> **Engine context (post-M1 spike).** Desktop adopted **LWJGL headless Vulkan**, not Materia (M1 Spike result). Android renders to a *real* `SurfaceView` surface, which suits Materia's windowed renderer — but adopting it means publish-to-Maven plumbing (composite build is dead under Gradle 9.3.1) and a second renderer codebase. If M2 already stood Materia up, reuse that; otherwise the mini-spike below also weighs an NDK-native Vulkan renderer behind the same interface.

## Go/no-go mini-spike (timebox: half a day)

Two questions:

1. Consumption: does Materia (built with its own Gradle 8.13, published to a Maven repo per the M1 "Materia consumption recipe" — **not** `includeBuild`) provide an Android-target klib consumable from `androidMain`, and does `:androidApp:assembleDebug` link its per-ABI native libs? (Re-validate even if JVM/Apple passed; targets are independent.)
2. Surface: does Materia's Android backend render to a **caller-supplied** `android.view.Surface`/`ANativeWindow` (from a `SurfaceHolder`), on API 24+, without owning the Activity/view hierarchy?

No-go on either → record the verdict below and pause this milestone pending either a Materia fork patch exposing surface injection, or an **NDK-native Vulkan renderer** behind the same `Chess3DBoardRenderer` interface (consistent with desktop's hand-written approach) — do not improvise an alternative without updating this doc first.

## Files

All in `app/src/androidMain/kotlin/com/example/myapplication/board3d/` unless noted:

- **`AndroidBoard3D.kt`** —
  - `class AndroidChess3DSurface(val holder: SurfaceHolder, override val widthPx: Int, override val heightPx: Int) : Chess3DSurface`
  - `@Composable fun AndroidBoard3DSurface(renderer: Chess3DBoardRenderer, modifier: Modifier)` via `AndroidView(factory = { SurfaceView(context) })` with a `SurfaceHolder.Callback`:
    - `surfaceCreated` → `renderer.attach(AndroidChess3DSurface(holder, w, h))`
    - `surfaceChanged` → `renderer.onUserInteraction(Board3DInput.Resize(w, h))`
    - `surfaceDestroyed` → `renderer.detach()` — and the renderer contract's "detach must return quickly but the surface must not be touched afterwards" matters most here: the GPU work targeting the surface must be fenced before returning.
  - `fun androidBoard3DSupport(): Board3DSupport` — factory loads `Res.readBytes("files/models/chess.glb")`, `runCatching { ... }.getOrNull()` (also returns null on devices without Vulkan).
- **`AndroidVulkanChessRenderer.kt`** — Materia wrapper (or NDK-native on rescope); same structure as the desktop renderer (dedicated render thread, scene from `Board3DSceneMapper` + `ChessSetMeshNames`, render on demand / on surface callbacks).
- **`MainActivity.kt`** (modify) — pass `board3D = androidBoard3DSupport()` into `ChessApp`.
- **`app/build.gradle.kts`** — androidMain dependency on Materia consumed from a Maven repo (built separately with Gradle 8.13 per the M1 "Materia consumption recipe"); no `includeBuild`, no submodule.

Note on z-order: `SurfaceView` punches a hole in the window, but Compose `Dialog`s are separate windows, so promotion/game-over/draw dialogs layer correctly above it — still covered by an explicit test below. Do not touch `jniLibs.useLegacyPackaging` or the compose-resources asset hacks.

## Tests

Unit tests: none new in commonTest (scene layer unchanged).

UI tests (`app/src/androidDeviceTest/kotlin/com/example/myapplication/`):

- M1's fake-based `Board3DToggleTest` keeps passing untouched.
- Add `dialog renders above surface view` — real or fake support with 3D toggled on, seed a `pendingPromotion` state, assert `promotion_choice_QUEEN` is displayed and clickable.
- Add `Board3DRendererSmokeTest` — uses the **real** `androidBoard3DSupport()` factory on the device/emulator. The API 35 emulator with `-gpu swiftshader_indirect` supports Vulkan 1.1, so **this is the one CI environment where the real GPU path runs inside a required job** (`:app:connectedAndroidDeviceTest`). Still guard with JUnit `Assume` on factory-null / `vkCreateInstance` failure for exotic devices. Asserts: toggle on with real support → node `board_3d` exists, no crash after `updatePosition(post-e4 FEN)`, toggle off cleans up (no `SurfaceHolder` callbacks after detach).

## CI

- The existing emulator job already uses `swiftshader_indirect` and runs `:app:connectedAndroidDeviceTest`, which picks up the new tests.
- If Materia is adopted: CI must publish it to a Maven repo before building `:app` (no submodule/composite build). NDK-native rescope needs no such step.
- Watch APK size: Materia ships native libs per ABI; compare `:androidApp:assembleDebug` output size before/after and flag a >20 MB growth in the PR.

## Definition of done

- Android app shows the 3D toggle; enabling renders the 3D board on a device/emulator; dialogs layer correctly; 2D board remains the interaction surface; devices without Vulkan fall back gracefully (toggle reverts, unavailable message).
- `./gradlew :app:connectedAndroidDeviceTest` green including the new smoke test on the API 35 emulator.
- Full CI matrix builds (overview "Execution rules").

## Spike result

Executed 2026-06-13.

**Verdict: FAIL.** Materia was rejected during the M1 spike (no offscreen support) and M2 (iOS chose SceneKit). For Android, introducing a bespoke engine (Materia) solely for one target is not justifiable. The spike requirements (klib consumption, surface injection) are not met cleanly without maintaining a complex build fork.

**Rescope**: As specified in the doc, we fall back to an "NDK-native Vulkan renderer". However, instead of writing raw C++ Vulkan code (with JNI bridges and a C++ glTF parser) from scratch, we will use **Google Filament** (`com.google.android.filament`). Filament is an industry-standard, lightweight, C++-based renderer by Google that uses Vulkan by default on Android, provides a Kotlin API, natively parses `.glb` files, and binds perfectly to a `SurfaceView`. This matches the architectural precedent set by iOS M2 (which used SceneKit rather than raw Metal).

**Update:** the shipped Filament implementation was later moved off hand-written `Engine`/`SurfaceHolder` plumbing onto **SceneView** (Compose-native Filament) — see [issue-32-3d-ui-unresolved-questions.md §5](issue-32-3d-ui-unresolved-questions.md).
