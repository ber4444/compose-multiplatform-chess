# Issue #32 — 3D UI, Milestone 3: Android (SceneView/Filament)

> **Status update:** Android 3D shipped through **SceneView/Filament**, not Materia and not a raw
> NDK Vulkan renderer. The historical plan below is retained as the decision record; the current
> implementation is `AndroidBoard3D.kt` + `AndroidSceneViewChessRenderer.kt`, using `SurfaceType.Surface`,
> papermill IBL/skybox KTX assets, fixed SceneView node pools, and a transparent Compose gesture overlay
> for the shared tap/drag/pinch input path.

Prereqs: [issue-32-3d-ui-overview.md](issue-32-3d-ui-overview.md) and merged [M1](issue-32-3d-ui-m1-foundation.md) (abstraction, `androidDeviceTest` toggle tests, `chess.glb`). Suggested branch: `issue-32-3d-m3`.

Goal: a real 3D backend on Android, injected from `MainActivity`. The original Vulkan/Materia path below was rejected; the shipped path is SceneView/Filament.

> **Engine context (post-M1 spike).** Desktop adopted **LWJGL headless Vulkan**, not Materia (M1 Spike result). Android renders to a *real* `SurfaceView` surface, which suits Materia's windowed renderer — but adopting it means publish-to-Maven plumbing (composite build is dead under Gradle 9.3.1) and a second renderer codebase. If M2 already stood Materia up, reuse that; otherwise the mini-spike below also weighs an NDK-native Vulkan renderer behind the same interface.

## Go/no-go mini-spike (timebox: half a day)

Two questions:

1. Consumption: does Materia (built with its own Gradle 8.13, published to a Maven repo per the M1 "Materia consumption recipe" — **not** `includeBuild`) provide an Android-target klib consumable from `androidMain`, and does `:androidApp:assembleDebug` link its per-ABI native libs? (Re-validate even if JVM/Apple passed; targets are independent.)
2. Surface: does Materia's Android backend render to a **caller-supplied** `android.view.Surface`/`ANativeWindow` (from a `SurfaceHolder`), on API 24+, without owning the Activity/view hierarchy?

No-go on either → record the verdict below and pause this milestone pending either a Materia fork patch exposing surface injection, or an **NDK-native Vulkan renderer** behind the same `Chess3DBoardRenderer` interface (consistent with desktop's hand-written approach) — do not improvise an alternative without updating this doc first.

## Files

All in `app/src/androidMain/kotlin/com/example/myapplication/board3d/` unless noted:

- **`AndroidBoard3D.kt`** —
  - `@Composable fun AndroidBoard3DSurface(renderer: Chess3DBoardRenderer, modifier: Modifier)` hosts a
    SceneView `SceneView(..., surfaceType = SurfaceType.Surface, cameraManipulator = null,
    autoCenterContent = false)` and places a transparent Compose `Box(modifier)` above it for shared
    gestures.
  - `fun androidBoard3DSupport(): Board3DSupport` — factory validates `files/models/chess.glb`,
    `files/env/papermill_ibl.ktx`, and `files/env/papermill_skybox.ktx`, then returns
    `AndroidSceneViewChessRenderer(glb)` or null on init/resource failure.
- **`AndroidSceneViewChessRenderer.kt`** — Compose-observable SceneView/Filament state holder behind
  `Chess3DBoardRenderer`; stores scene, selection, camera, and lifecycle state while SceneView owns the
  engine/render loop.
- **`MainActivity.kt`** (modify) — pass `board3D = androidBoard3DSupport()` into `ChessApp`.
- **`app/build.gradle.kts`** — androidMain dependency on Materia consumed from a Maven repo (built separately with Gradle 8.13 per the M1 "Materia consumption recipe"); no `includeBuild`, no submodule.

Note on z-order/input: SceneView's surface stays below Compose dialogs, and its internal touch listener
consumes gestures; the transparent Compose overlay is intentionally the input surface. Do not touch
`jniLibs.useLegacyPackaging` or the compose-resources asset hacks.

## Tests

Unit tests: none new in commonTest (scene layer unchanged).

UI tests (`app/src/androidDeviceTest/kotlin/com/example/myapplication/`):

- M1's fake-based `Board3DToggleTest` keeps passing untouched.
- Add `dialog renders above surface view` — real or fake support with 3D toggled on, seed a `pendingPromotion` state, assert `promotion_choice_QUEEN` is displayed and clickable.
- Add `Board3DRendererSmokeTest` — uses the **real** `androidBoard3DSupport()` factory on the device/emulator. Guard with JUnit `Assume` on factory-null. Asserts: toggle on with real support → node `board_3d` exists, no crash after `updatePosition(post-e4 FEN)`, toggle off cleans up.

## CI

- The existing emulator job already uses `swiftshader_indirect` and runs `:app:connectedAndroidDeviceTest`, which picks up the new tests.
- SceneView/Filament dependencies are consumed from Gradle; there is no Materia publish step.
- Watch APK size when changing SceneView/Filament/model assets; compare `:androidApp:assembleDebug` output size before/after and flag a >20 MB growth in the PR.

## Definition of done

- Android app shows the 3D toggle; enabling renders the 3D board on a device/emulator; dialogs layer correctly; the transparent Compose overlay remains the interaction surface; init failures fall back gracefully (toggle reverts, unavailable message).
- `./gradlew :app:connectedAndroidDeviceTest` green including the new smoke test on the API 35 emulator.
- Full CI matrix builds (overview "Execution rules").

## Spike result

Executed 2026-06-13.

**Verdict: FAIL.** Materia was rejected during the M1 spike (no offscreen support) and M2 (iOS chose SceneKit). For Android, introducing a bespoke engine (Materia) solely for one target is not justifiable. The spike requirements (klib consumption, surface injection) are not met cleanly without maintaining a complex build fork.

**Rescope**: As specified in the doc, we fall back to an "NDK-native Vulkan renderer". However, instead of writing raw C++ Vulkan code (with JNI bridges and a C++ glTF parser) from scratch, we will use **Google Filament** (`com.google.android.filament`). Filament is an industry-standard, lightweight, C++-based renderer by Google that uses Vulkan by default on Android, provides a Kotlin API, natively parses `.glb` files, and binds perfectly to a `SurfaceView`. This matches the architectural precedent set by iOS M2 (which used SceneKit rather than raw Metal).

**Update:** the shipped Filament implementation was later moved off hand-written `Engine`/`SurfaceHolder` plumbing onto **SceneView** (Compose-native Filament) — see [issue-32-3d-ui-unresolved-questions.md §5](issue-32-3d-ui-unresolved-questions.md).
