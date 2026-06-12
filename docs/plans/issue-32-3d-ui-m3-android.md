# Issue #32 — 3D UI, Milestone 3: Android (Vulkan)

Prereqs: [issue-32-3d-ui-overview.md](issue-32-3d-ui-overview.md) and merged [M1](issue-32-3d-ui-m1-foundation.md) (abstraction, `androidDeviceTest` toggle tests, `chess.glb`). Suggested branch: `issue-32-3d-m3`.

Goal: a real 3D backend on Android, injected from `MainActivity`. Vulkan-from-`Surface` on API 24+ is Materia's headline Android feature, so risk is moderate.

## Go/no-go mini-spike (timebox: half a day)

One question: does Materia's Android backend render to a **caller-supplied** `android.view.Surface`/`ANativeWindow` (from a `SurfaceHolder`), on API 24+, without owning the Activity/view hierarchy?

No-go → record the verdict below and pause this milestone pending either a Materia fork patch exposing surface injection, or (if M1 ended on the LWJGL fallback) a separate decision on an NDK-based renderer — do not improvise an alternative without updating this doc first.

## Files

All in `app/src/androidMain/kotlin/com/example/myapplication/board3d/` unless noted:

- **`AndroidBoard3D.kt`** —
  - `class AndroidChess3DSurface(val holder: SurfaceHolder, override val widthPx: Int, override val heightPx: Int) : Chess3DSurface`
  - `@Composable fun AndroidBoard3DSurface(renderer: Chess3DBoardRenderer, modifier: Modifier)` via `AndroidView(factory = { SurfaceView(context) })` with a `SurfaceHolder.Callback`:
    - `surfaceCreated` → `renderer.attach(AndroidChess3DSurface(holder, w, h))`
    - `surfaceChanged` → `renderer.onUserInteraction(Board3DInput.Resize(w, h))`
    - `surfaceDestroyed` → `renderer.detach()` — and the renderer contract's "detach must return quickly but the surface must not be touched afterwards" matters most here: the GPU work targeting the surface must be fenced before returning.
  - `fun androidBoard3DSupport(): Board3DSupport` — factory loads `Res.readBytes("files/models/chess.glb")`, `runCatching { ... }.getOrNull()` (also returns null on devices without Vulkan).
- **`AndroidVulkanChessRenderer.kt`** — Materia wrapper; same structure as the desktop renderer (dedicated render thread, scene from `Board3DSceneMapper` + `ChessSetMeshNames`, render on demand / on surface callbacks).
- **`MainActivity.kt`** (modify) — pass `board3D = androidBoard3DSupport()` into `ChessApp`.
- **`app/build.gradle.kts`** — androidMain dependency on the substituted Materia modules.

Note on z-order: `SurfaceView` punches a hole in the window, but Compose `Dialog`s are separate windows, so promotion/game-over/draw dialogs layer correctly above it — still covered by an explicit test below. Do not touch `jniLibs.useLegacyPackaging` or the compose-resources asset hacks.

## Tests

Unit tests: none new in commonTest (scene layer unchanged).

UI tests (`app/src/androidDeviceTest/kotlin/com/example/myapplication/`):

- M1's fake-based `Board3DToggleTest` keeps passing untouched.
- Add `dialog renders above surface view` — real or fake support with 3D toggled on, seed a `pendingPromotion` state, assert `promotion_choice_QUEEN` is displayed and clickable.
- Add `Board3DRendererSmokeTest` — uses the **real** `androidBoard3DSupport()` factory on the device/emulator. The API 35 emulator with `-gpu swiftshader_indirect` supports Vulkan 1.1, so **this is the one CI environment where the real GPU path runs inside a required job** (`:app:connectedAndroidDeviceTest`). Still guard with JUnit `Assume` on factory-null / `vkCreateInstance` failure for exotic devices. Asserts: toggle on with real support → node `board_3d` exists, no crash after `updatePosition(post-e4 FEN)`, toggle off cleans up (no `SurfaceHolder` callbacks after detach).

## CI

- No new infrastructure: the existing emulator job already uses `swiftshader_indirect` and runs `:app:connectedAndroidDeviceTest`, which picks up the new tests.
- Watch APK size: Materia ships native libs per ABI; compare `:androidApp:assembleDebug` output size before/after and flag a >20 MB growth in the PR.

## Definition of done

- Android app shows the 3D toggle; enabling renders the 3D board on a device/emulator; dialogs layer correctly; 2D board remains the interaction surface; devices without Vulkan fall back gracefully (toggle reverts, unavailable message).
- `./gradlew :app:connectedAndroidDeviceTest` green including the new smoke test on the API 35 emulator.
- Full CI matrix builds (overview "Execution rules").

## Spike result

_To be appended: surface-injection verdict and any fork patches._
