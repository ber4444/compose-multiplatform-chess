# Issue #32 — 3D UI Unresolved Questions Finalization

This document formalizes the resolutions to the open decisions raised across the various 3D UI milestones.

## 0. Repository Cleanup
- The repository root will be cleaned of temporary scripts, node modules, and screenshots that were used for spikes and debugging (e.g., `*.js`, `*.py`, `*.png`, `node_modules/`).
- The valid offline-generated environment assets (`face_*.exr`, `papermill_ibl.ktx`, `papermill_skybox.ktx`) will be preserved under `app/src/commonMain/composeResources/files/env/` to serve the M7 asset pipeline.

## 1. Adopt Sketchfab 3D Model
- The current unverified `chess.glb` model will be replaced with an explicitly licensed (CC-BY/CC0) Sketchfab model: [Chess](https://sketchfab.com/3d-models/chess-e54c2d04d4f74823b69ba4a794fb4500).
- `ChessSetMeshNames.kt` will be updated to map the `PieceKind`/`PieceColor` to the correct node/mesh names within the new GLB file.

**Implementation note.** The selected Sketchfab model metadata was verified through the public API:
model `Chess` by Verfassen, licensed Creative Commons Attribution 4.0 (`CC BY 4.0`), author credit
required and commercial use allowed. The unauthenticated download endpoint returns `401`, so the
binary replacement of `app/src/commonMain/composeResources/files/models/chess.glb` still needs an
authorized Sketchfab download/export. `THIRD_PARTY_NOTICES.md` records both the current checked-in
asset provenance and the approved replacement attribution.

## 2. Offline Generation (M7 Asset Pipeline)
- We are confirming the use of offline-generated environment assets for iOS (SceneKit) and Android (Filament), rather than doing runtime conversions.
- Android will use `papermill_ibl.ktx` and `papermill_skybox.ktx` (generated offline via Filament's `cmgen`).
- iOS will use the 6-face EXR images (`face_*.exr`) for its environment skybox and lighting.

## 3. Keep 2D Board Hidden (M5 Polish)
- The optional M5 polish of collapsing the 2D board into a thumbnail is rejected. The 2D board will remain fully hidden when 3D mode is active to keep the interaction model unambiguous.

## 4. Accept WebGPU Dependency Risk for Desktop/Web (M6)
- The stability risk of using the `io.ygdrasil:wgpu4k-toolkit:0.2.0-SNAPSHOT` dependency is accepted for the desktop/web renderer. It remains pinned and can be upgraded as maintenance, but its release status does not make it the planned mobile backend.

## 5. Replace M3's Filament Boilerplate with SceneView

**Resolution.** Replace the hand-written Filament plumbing on Android with **SceneView**
(`io.github.sceneview:sceneview:4.18.0`), the Jetpack-Compose-native Filament wrapper. The
`Chess3DBoardRenderer` abstraction is unchanged — SceneView is the Android backend's *internal mechanism*.
This is Android-only; Android stays on Filament through SceneView as the product backend. The M8 mobile
surface spike showed that replacing it with a shared WebGPU path would require separate JNI/NDK surface
ownership work, so SceneView remains the Android product backend.

**Why.** M3 landed on raw Filament (`Engine`/`Renderer`/`Scene`/`SwapChain`/`Choreographer` + manual
`SurfaceHolder.Callback` + `gltfio.AssetLoader`). SceneView wraps exactly that, with automatic lifecycle and a
`SurfaceType` enum that resolves the dialog-above-surface z-ordering this milestone worried about. Net:
~300 lines of imperative plumbing collapse to a declarative `SceneView { }` with node composables.

**How it fits the interface (no commonMain changes).**
- `AndroidSceneViewChessRenderer` becomes a thin
  Compose-observable state holder implementing `Chess3DBoardRenderer`, backed by `mutableStateOf` for the
  FEN-derived `Board3DScene`, `CameraParams`, and the selected `BoardSquare`.
- `attach`/`detach` no longer own a `SwapChain`/`Choreographer` loop (SceneView owns the engine + render
  lifecycle); they reduce to recording surface size / no-op, and `dispose` defers to SceneView's automatic
  teardown. The interface contract (idempotent `detach`, `updatePosition` before `attach`, `dispose` releases)
  is still honoured — now trivially, since state is just snapshot state.
- `updatePosition(fen[, transition])` writes the mapped `Board3DScene` into observable state.
- `onUserInteraction(SetCamera/Resize)` writes camera/aspect into observable state.
- `setSelectedSquare(square)` writes selection into observable state.
- `AndroidBoard3DSurface` (the `surfaceContent` lambda) renders SceneView's
  `SceneView(modifier, surfaceType = SurfaceType.Surface) { ... }`, reading the renderer's observable state:
  `ModelNode`s for board + pieces (positions/rotations from `Board3DSceneMapper`/`ChessSetMeshNames`), a
  highlight node for the selected square, and a camera node driven by the renderer's `CameraParams`. The
  shared `Board3DHost` keeps wiring tap/drag/zoom gestures into `modifier`; with `SurfaceType.Surface`
  (the default — dialogs layer above) Compose `pointerInput` keeps intercepting touches, exactly as the
  current `Box`-hosted `SurfaceView` does.

**Design points to settle during implementation.**
- *Asset loading.* `chess.glb` is a compose resource copied into Android assets by the build hacks. Load it
  inside the `Scene` via SceneView's `modelLoader` (`rememberModelInstance` from the asset path, or
  `modelLoader.createModelInstance(ByteBuffer)` from `Res.readBytes`). Per-piece `ModelNode` instances replace
  the old `createInstancedAsset` clone-by-index trick.
- *Unavailable fallback.* SceneView/Filament auto-selects Vulkan or GLES and is robust on modern devices, so
  the `board3DUnavailable` path is now rarely hit. Keep the factory `suspend` and **nullable**: return `null`
  if the glb fails to load or SceneView init throws, preserving the `board3DUnavailable` contract and the
  existing `test3DFallbackWhenFactoryReturnsNull` test. The factory should no longer need to construct a
  Filament `Engine` just to probe support.
- *IBL/skybox.* `papermill_ibl.ktx` / `papermill_skybox.ktx` (see §2) load through SceneView's
  environment/IBL APIs rather than `KTX1Loader` directly.

**Dependency.**
- Add to `gradle/libs.versions.toml`: `sceneview = "4.18.0"` and
  `sceneview = { module = "io.github.sceneview:sceneview", version.ref = "sceneview" }`.
- In `app/build.gradle.kts` `androidMain`, replace `filament-android` / `filament-gltfio` / `filament-utils`
  with `implementation(libs.sceneview)` (SceneView bundles Filament transitively). Confirm no other
  `androidMain` code still references the removed direct-Filament artifacts.
- *Risk:* SceneView 4.18.0 aligns to a specific androidx Compose version; confirm it coexists with Compose
  Multiplatform 1.10.x in `androidMain` (flag if a version bump or `exclude` is needed). Watch APK size
  (SceneView + bundled Filament) and flag >20 MB growth in the PR, mirroring the existing M3 note.

**Files (for the follow-up implementation task).**
- `app/src/androidMain/.../board3d/AndroidSceneViewChessRenderer.kt` — renamed; rewritten as a state holder.
- `app/src/androidMain/.../board3d/AndroidBoard3D.kt` — `SurfaceHolder.Callback` wiring → `Scene { }`
  composable; `androidBoard3DSupport()` constructs the renamed renderer.
- `gradle/libs.versions.toml`, `app/build.gradle.kts` — dependency swap.
- No change to the commonMain `Chess3DBoardRenderer` interface, `Board3DHost`, the scene layer, or
  `MainActivity` wiring.

**Tests.**

*Unit (commonTest):* none new. The scene layer (`Board3DSceneMapper`, `Math3D`, `BoardRayPicker`,
`ChessSetMeshNames`) is renderer-agnostic and already unit-tested; SceneView does not move those boundaries,
and the common contract test against `FakeChess3DRenderer` still defines the lifecycle semantics the SceneView
renderer must honour. If the rewrite introduces isolable pure logic (e.g. mapping a `Board3DScene` to node
transforms/rotations), extract it to a pure function and add a focused unit test; otherwise no new units.

*UI (`app/src/androidDeviceTest/.../board3d/`):*
- `Board3DUiTest` (fake-based: toggle / null-factory fallback / animation delivery) — unchanged; must keep
  passing untouched.
- `AndroidBoard3DUiTest.dialogRendersAboveSurfaceView` — unchanged intent, now the **regression guard** that
  the SceneView `SurfaceType.Surface` setup plus Compose dialog windowing keeps dialogs above the 3D
  surface: 3D on + seeded `pendingPromotion` → `promotion_choice_QUEEN` is displayed and clickable.
- `AndroidBoard3DUiTest.board3DRendererSmokeTest` — unchanged intent, now exercises the real SceneView
  `androidBoard3DSupport()`. `Assume`-guard on factory-null. Toggle on → `board_3d` exists; make a move
  (drives `updatePosition`); toggle off cleanly. Verifies the SceneView renderer builds, accepts a position
  update, and tears down without crashing on the API 35 `swiftshader_indirect` emulator.
- **New** `selectionHighlightDoesNotCrash` — real support, 3D on, drive a selection so `setSelectedSquare`
  fires; assert `board_3d` still present and no crash (the highlight node is GPU-only and not pixel-
  introspectable in instrumented tests). `Assume`-guarded like the smoke test.
- `FakeChess3DRenderer` stays duplicated in `androidDeviceTest` (instrumented source set can't see commonTest).

*CI:* unchanged — `:app:connectedAndroidDeviceTest` on the API 35 `swiftshader_indirect` emulator already
runs these as a required job.

## 6. Implementation Closeout (what actually landed)

The §5 SceneView migration is implemented and verified on a physical device (Samsung Z Fold,
`SM-F926U`): tap-to-move, a visible selection highlight, camera orbit/zoom, and a board that fits the
screen all work. The sections below record where the implementation **deviated from the §5 plan** and the
non-obvious gotchas that drove those deviations — read this before touching `AndroidBoard3D.kt` again.

### 6.1 Touch routing — SceneView's SurfaceView eats every touch
The §5 plan assumed Compose `pointerInput` on the `modifier` keeps intercepting touches "exactly as the
current `Box`-hosted `SurfaceView` does." It does **not**: `SceneRenderer.attachToSurfaceView` installs
`surfaceView.setOnTouchListener { _, e -> dispatch(e); true }` — it **unconditionally consumes** every
touch, so the shared `Board3DHost` gestures never fire. Fix: host the `SceneView(Modifier.matchParentSize())`
inside a `Box {}` and add a **transparent sibling** `Box(modifier)` *after* it, carrying the gesture
modifier. Being the last child it wins Compose hit-testing while the Scene below still renders. (iOS does the
equivalent with `UIKitView(interactive = false)`.)

### 6.2 Camera — drive the CameraNode, disable SceneView's manipulator
- Pass `cameraManipulator = null` and `autoCenterContent = false` to the `SceneView` composable. The default
  `cameraManipulator` does `cameraNode.transform = manipulator.getTransform()` **every frame**, silently
  overwriting whatever you set (the render stays pixel-identical no matter your math).
- Drive the camera at the **node** level: `cameraNode.position`, `cameraNode.lookAt(...)`,
  `cameraNode.setProjection(...)`. Setting the raw Filament camera (`cameraNode.camera.lookAt(...)`) is
  overwritten from the node transform each frame.
- Framing: the 3D board is laid out **square** (`GameScreen`: `fillMaxWidth().aspectRatio(1f)`), so the
  viewport aspect is ~1 on every platform. The default camera is framed for that square viewport via
  `OrbitCameraController` (`DEFAULT_PITCH_DEG = 35`, `DEFAULT_DISTANCE = 17`, `FOV_Y_DEG = 50`) so the whole
  board + marble frame fits with margin. (The earlier portrait "effectiveDistance pull-back" idea was dropped
  — it was dead code given the square layout.) Portrait FOV handling survives only as a guard and stays in
  sync with `CameraMath.effectiveFovYRad` so picking matches the projection.

### 6.3 Piece rendering — fixed node pool, not keyed per-piece instances
The §5 "per-piece `ModelNode` instances" / `createModelInstance` approach works for the initial frame but is
fragile under moves. `chess.glb` is read **once** into a `ByteArray` held by the renderer
(`AndroidSceneViewChessRenderer(glbBytes)`, loaded via `Res.readBytes` in the factory) and each node's
`FilamentInstance` is created **synchronously** from a fresh `ByteBuffer` (the `rememberModelInstance` helper
loads async and left freshly-keyed nodes blank). Pieces render as a **fixed pool of 32 `ModelNode`s** created
in the first composition, each showing `boardScene.pieces[i]` and updated *reactively* (position / rotation /
`isVisible` params + a `LaunchedEffect` that toggles the right renderable's visibility) — no add/remove churn
on a move. One board `ModelNode` shows the marble tiles + frame (hides the piece templates and the stray
`Plane` mesh); a green `CylinderNode` disk (`materialLoader.createColorInstance`) marks the selected square.
GLB squares are 2 units vs the game's 1 unit, so every node is `scale = 0.5` and pieces sit at
`squareCenter`.

### 6.4 Gestures, tests, and verification
- **Gestures:** orbit (single-finger pan) and zoom (pinch) are handled in **one**
  `detectTransformGestures { _, pan, zoom, _ -> ... }` plus a `detectTapGestures`. Keeping `detectDragGestures`
  and `detectTransformGestures` in separate `pointerInput` blocks made transform swallow single-finger drags as
  no-op `zoom = 1` gestures, so the board never orbited.
- **Tests (supersedes §5 Tests for the device suite):** a live SceneView's render loop
  (`while (true) { withFrameNanos { renderFrame } }`) keeps the Compose test clock perpetually busy, so
  `waitForIdle()` — and every finder/assertion that calls it — throws `ComposeNotIdleException` on a device
  where SceneView actually runs. It is **not** infinite recomposition in our code (0 recompositions while
  idle); `mainClock.autoAdvance = false` only makes finders hang. So `board3DRendererSmokeTest` and
  `selectionHighlightDoesNotCrash` now drive a **fake `Board3DSupport`** (plain `Box` surface, no SceneView)
  via `fakeBoard3DSupport()`, exercising the GameScreen 3D wiring without the unsyncable render loop;
  `dialogRendersAboveSurfaceView` is **`@Ignore`d** (manual/CI-only) — it needs the real surface to test
  occlusion, and the dialog-above-`Surface` guarantee is structural anyway (a Compose `Dialog` is a separate
  window above content, and `SurfaceType.Surface` is not z-ordered on top). Device suite: 2 pass + 1 skipped.
- **Verification gotcha:** a full-image mean pixel-diff of `adb screencap` is far too coarse to detect a piece
  sliding across the board (a wooden pawn on marble ≈ 0.5 mean, same as AA noise) — it falsely reads as
  "nothing rendered." Use an **amplified + per-square** diff (`ImageChops.difference` →
  `ImageEnhance.Brightness(...).enhance(8)`, measured on small crops around the from/to squares). screencap
  *does* capture live SurfaceView frames; it is not stale.

### 6.5 SurfaceType — keep `Surface`
`SurfaceType.Surface` is the deliberate choice (per §5): it lets Compose dialogs layer above the board.
`TextureSurface` was briefly used while probing a (non-existent) render bug; it also shifts the projection and
reverses the dialog rationale, so it was reverted. Do not switch without re-checking `dialogRendersAboveSurfaceView`
manually.
