# Desktop Filament Replacement Design

Date: 2026-06-25

## Goal

Replace the desktop JVM 3D board product backend with Google Filament, matching the
Android, iOS, and web direction more closely and removing the hand-written
`VulkanChessRenderer` from the normal desktop path.

The desktop app must keep the current `Chess3DBoardRenderer` boundary, 2D fallback behavior,
shared camera/ray-picking logic, shared move animation, and Compose Desktop UI behavior. The
replacement should reuse as much Filament code and protocol as practical across web, iOS, and
desktop.

## Non-goals

- Do not replace Android SceneView.
- Do not replace web Filament JS with native WebGPU or another renderer.
- Do not change chess rules, game state, Stockfish integration, or 2D board behavior.
- Do not rewrite platform glue beyond the small iOS adjustments needed for shared Filament
  lifecycle/protocol reuse.
- Do not commit downloaded Filament release payloads.

## Current Context

Desktop currently builds `VulkanChessRenderer` from `desktopBoard3DSupport()`. It renders
offscreen and feeds `ImageBitmapChess3DSurface`, so Compose only receives `ImageBitmap`
frames. Android uses SceneView/Filament. iOS uses a Swift/Obj-C++ Filament Metal view.
Web uses the Filament JavaScript API behind the same `Chess3DBoardRenderer` contract.

Official Filament supports native C++ APIs on Linux, macOS, and Windows, Java/JNI APIs on
Android, and JavaScript APIs on web. Its C++ API supports a headless swap chain and
`Renderer::readPixels`, which makes a desktop offscreen-readback backend feasible while
preserving the current Compose surface contract.

References:

- https://github.com/google/filament
- https://github.com/google/filament/blob/main/filament/include/filament/Engine.h
- https://github.com/google/filament/blob/main/filament/include/filament/Renderer.h

## Recommended Architecture

Use a desktop native Filament bridge with headless rendering and readback:

```text
commonMain
  Board3DHost / Board3DAnimationDriver / Board3DScene.encode / camera + picker
        |
        v
shared encoded Filament renderer lifecycle
  pending FEN, selected square, camera, resize, attach/detach, scene wire updates
        |
        +--> iOS peer: Swift UIView + shared/native Filament core + CAMetalLayer
        |
        +--> desktop peer: JNI bridge + shared/native Filament core
        |                 headless swap chain + readPixels -> ImageBitmap
        |
        +--> web peer: existing Filament JS glue, same wire protocol/constants
```

The desktop backend keeps CPU readback because Compose Desktop has no zero-copy Filament
surface path that is as reliable as the current ImageBitmap surface for dialogs, tests,
input layering, and packaging. The readback cost is acceptable for this board because frames
are produced on scene, camera, resize, and animation changes rather than a constant hot loop.

## Reuse Plan

### Kotlin reuse across iOS and desktop

Introduce a shared commonMain encoded Filament renderer wrapper that owns the logic currently
duplicated inside `FilamentIosChessRenderer`:

- pending FEN storage
- `Board3DAnimationDriver`
- selected-square bounce forwarding
- camera encoding
- resize handling
- attach/detach readiness
- peer shutdown

The wrapper delegates platform-specific work to a small peer interface, for example:

```kotlin
interface FilamentChessPeer {
    fun setScene(encoded: String)
    fun setCamera(encoded: String)
    fun resize(widthPx: Int, heightPx: Int)
    fun attach(surface: Chess3DSurface?)
    fun detach()
    fun shutdown()
}
```

iOS keeps its Swift-injected native view factory but routes through this shared wrapper.
Desktop provides a peer backed by the native desktop bridge. This lets the two native
Filament platforms share renderer lifecycle behavior without leaking platform types into
commonMain.

### Native reuse across iOS and desktop

Move the engine-independent Filament scene code into a shared C++ core where practical:

- gltfio asset loading and `MAX_PIECES + 1` instancing
- board instance visibility rules
- piece-pool reconciliation from `Board3DScene.encode`
- material selection by `ChessSetMeshNames`
- transforms and `PIECE_SCALE`
- IBL/skybox loading
- light and exposure constants
- camera projection/FOV boost
- teardown ordering

Keep platform host code thin:

- iOS host owns `CAMetalLayer`, `CADisplayLink`, and Swift/Obj-C++ protocol conformance.
- Desktop host owns JNI entry points, headless swap-chain resize, readback buffer,
  and native library loading.
- Web keeps JavaScript Filament, but consumes the same encoded scene/camera schema and
  shared constants from Kotlin string interpolation where possible.

If full native-core extraction would destabilize iOS, implement it in two passes: first land
the shared Kotlin lifecycle and desktop native bridge, then extract iOS/desktop common C++
scene code with screenshot parity checks. The product result still must avoid desktop Vulkan.

### Web reuse

The web Filament backend cannot share C++ code, but it should share the protocol:

- `Board3DScene.encode`
- camera encoding shape
- `ChessSetConventions`
- mesh/material naming in `ChessSetMeshNames`
- lighting/exposure constants where web Filament exposes equivalent controls

Any desktop-specific native constant that also appears in web or iOS should either live in
commonMain or be documented with a keep-in-sync comment and a test where feasible.

## New Components

### Fetch script

Add `tools/fetch_filament_desktop.sh`, pinned to Filament v1.72.0 to match the iOS
fetch script default, and stage gitignored desktop payloads under:

```text
app/src/desktopMain/filament/filament/
```

The script should:

- detect host OS and architecture
- download the matching Filament desktop release archive
- verify the expected headers/libs/tools exist
- copy or symlink only the required headers, libraries, and runtime dylibs/shared objects
- print the selected version and staged files

### Native bridge

Add a small native bridge under:

```text
app/src/desktopMain/native/filament_bridge/
```

The bridge exposes a narrow ABI to Kotlin:

- create/destroy renderer
- resize
- set encoded scene
- set encoded camera
- render/read pixels into RGBA8888
- report initialization/render errors as strings or failure codes

Use JNI for the Kotlin/JVM to C++ boundary. Prefer CMake for the desktop bridge because
it is conventional for native C++ and can link against the downloaded Filament release.
Gradle should wire native compilation into `desktopTest`, `desktopJar`, `run`, and
`packageDistributionForCurrentOS`.

### Desktop Kotlin wrapper

Replace the desktop factory path:

- `desktopBoard3DSupport()` creates `DesktopFilamentChessRenderer`.
- `DesktopBoard3DSurface` can stay mostly intact because it already displays `ImageBitmap`
  frames from a `Chess3DSurface`.
- `ImageBitmapChess3DSurface` stays the readback sink.

`DesktopFilamentChessRenderer` should marshal native calls to one dedicated render thread,
matching the current thread-affinity discipline of the Vulkan renderer and Filament's
thread-safety requirements.

## Build And Packaging

Gradle must:

- remove desktop product dependencies on `lwjgl-vulkan` and `lwjgl-shaderc` after the
  replacement is complete
- keep only any desktop dependencies still needed by non-renderer code
- compile the native bridge before desktop runtime/test tasks
- add the built bridge and required Filament runtime libraries to desktop JVM
  `java.library.path`
- package those libraries into Compose Desktop native distributions
- keep downloaded Filament payloads gitignored

CI must:

- call `tools/fetch_filament_desktop.sh` before Linux desktop build/test tasks
- remove the Linux Vulkan/lavapipe install step when no remaining test needs Vulkan
- keep iOS `tools/fetch_filament_ios.sh`
- run existing desktop tests and a real desktop Filament smoke test when native libs are
  available

## Runtime Behavior

1. `Board3DHost` creates the desktop renderer via `desktopBoard3DSupport()`.
2. The shared encoded renderer wrapper receives FEN, selection, camera, and resize updates.
3. The wrapper maps FEN to `Board3DScene`, drives transitions through
   `Board3DAnimationDriver`, and sends encoded scene/camera updates to the desktop peer.
4. The desktop peer calls the native bridge on its render thread.
5. The native bridge renders through Filament to a headless swap chain, reads RGBA pixels
   back, and returns them to Kotlin.
6. Kotlin converts RGBA bytes to `ImageBitmap` with the existing `rgbaBytesToImageBitmap`.
7. Init failures return null from the renderer factory so the UI keeps the current graceful
   2D fallback.

## Error Handling

- Native init failure logs a concise cause and returns null from the factory.
- Render/readback failure detaches the renderer, marks 3D unavailable, and leaves the app
  usable in 2D.
- `detach()` must remain idempotent and fast.
- `dispose()` releases the native renderer exactly once.
- Resizing to zero or near-zero dimensions should not create native resources.

## Tests

Keep existing common and UI tests. Add focused desktop coverage:

- native library loader test: missing payload gives a clear skip/failure message
- renderer contract test: attach/update/detach/dispose behavior matches
  `Chess3DBoardRenderer`
- smoke test: render starting FEN to a small frame, assert non-blank/multiple colors, and
  write a PNG under `app/build/` for visual inspection
- resize test: render after changing size without leaking/crashing
- iOS regression check: existing iOS simulator tests and `tools/ios_3d_screenshot.sh` still
  work after any shared Kotlin/native refactor
- web regression check: `:app:wasmJsBrowserDistribution` and existing wasm tests still pass

## Migration Steps

1. Add desktop Filament fetch/build plumbing without changing the runtime factory.
2. Add shared Kotlin encoded renderer lifecycle and move iOS Kotlin wrapper onto it.
3. Add desktop native bridge and desktop peer.
4. Switch `desktopBoard3DSupport()` to Filament.
5. Add desktop Filament smoke tests.
6. Remove `VulkanChessRenderer` and Vulkan/LWJGL shader dependencies once tests pass.
7. Update docs and CI from "desktop Vulkan" to "desktop Filament".

## Definition Of Done

- `:app:run` uses desktop Filament for 3D.
- Desktop 3D renders the starting position and legal moves through the existing 3D toggle.
- Desktop 3D camera drag, pinch/scroll zoom, and tap-to-move still work.
- Desktop init failure falls back to 2D.
- iOS and web Filament behavior is not regressed.
- `./gradlew :app:desktopTest` passes.
- The full repo verification path is updated and green, including Android, desktop, wasm,
  and Apple jobs.
- Desktop Vulkan product code and dependencies are removed or left only as explicitly
  documented historical/reference code outside the active build.

## Open Risks

- Filament desktop release packaging differs by OS, so the fetch script and Gradle wiring
  need host-specific handling.
- `Renderer::readPixels` is documented as expensive. This is acceptable for this app, but
  the smoke test should keep frame size small and runtime rendering should stay demand-driven.
- Shared native C++ extraction may touch iOS Filament code. Keep that change small and verify
  with iOS simulator build/tests plus the screenshot script.
- Compose Desktop native distribution packaging may need per-OS library layout fixes.
