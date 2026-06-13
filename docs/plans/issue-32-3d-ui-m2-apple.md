# Issue #32 — 3D UI, Milestone 2: Apple (iOS via MoltenVK)

Prereqs: [issue-32-3d-ui-overview.md](issue-32-3d-ui-overview.md) and a merged [M1](issue-32-3d-ui-m1-foundation.md) (the abstraction, the toggle UI tests in `iosSimulatorArm64Test`, and `chess.glb` all already exist). Suggested branch: `issue-32-3d-m2`.

Goal: a real 3D backend on iOS, injected from `MainViewController.kt`. This is the highest-risk milestone — Materia's Apple backend is explicitly **beta** — so it opens with its own gate.

> **Engine context (post-M1 spike).** Desktop did **not** adopt Materia (its JVM backend has no offscreen path; see M1 Spike result). iOS is different: it renders to a *real* `CAMetalLayer`, which is what Materia's windowed renderer wants — so Materia is a genuine option here. But this is the **first milestone to actually introduce Materia**, which means standing up the publish-to-Maven plumbing (composite build is NOT viable — see below) and accepting a second renderer codebase alongside desktop's LWJGL. The mini-spike below decides Materia vs Metal-direct; either way the `Chess3DBoardRenderer` interface is unchanged.

## Go/no-go mini-spike (timebox: 1 day)

Two binary questions:

1. Does Materia's Apple backend compile as a Kotlin 2.2.20 klib consumable from this repo's 2.3.20 `iosMain`, with Materia **built by its own Gradle 8.13 and published to a Maven repo** (the M1 spike proved `includeBuild` composite builds fail under this repo's Gradle 9.3.1; see the M1 "Materia consumption recipe")? Verify `:app:linkDebugFrameworkIosSimulatorArm64` succeeds. Re-validate per-target: the JVM klib read cleanly in M1, but Apple/Native klibs are a separate question.
2. Can it create a `VkSurfaceKHR` (via MoltenVK) from a **caller-supplied** `CAMetalLayer`, rather than insisting on owning the view/window?

**No-go on either → rescope** this milestone to a Metal-direct renderer written in `iosMain` (Kotlin/Native `platform.Metal` bindings, same `Chess3DBoardRenderer` contract — the abstraction is the insurance policy). Record the verdict and decision in the "Spike result" section below. The file list and tests below stay identical either way; only `MoltenVkChessRenderer`'s internals change.

## Files

All in `app/src/iosMain/kotlin/com/example/myapplication/board3d/` unless noted:

- **`IosBoard3D.kt`** —
  - `class IosChess3DSurface(val metalLayer: CAMetalLayer, override val widthPx: Int, override val heightPx: Int) : Chess3DSurface`
  - `@Composable fun IosBoard3DSurface(renderer: Chess3DBoardRenderer, modifier: Modifier)` using `UIKitView(...)` with a `UIView` whose backing layer is a `CAMetalLayer` (or an `MTKView` with its automatic draw loop disabled). **Attach is deferred until the layer has nonzero bounds** (first nonzero resize callback), detach in `onRelease`. Compose iOS interop composites `UIKitView` content beneath Compose dialogs — verified by the dialog test below.
  - `fun iosBoard3DSupport(): Board3DSupport` — factory loads `Res.readBytes("files/models/chess.glb")`, `runCatching { ... }.getOrNull()`.
- **`MoltenVkChessRenderer.kt`** — Materia wrapper (or Metal-direct on rescope). Same structure as the desktop renderer: dedicated render dispatcher, scene from `Board3DSceneMapper` + `ChessSetMeshNames`, render on demand.
- **`MainViewController.kt`** (modify) — `ChessApp(viewModel, board3D = remember { iosBoard3DSupport() })`. **No Swift or framework-API changes** — all 3D wiring stays in Kotlin; the Xcode project, `project.yml`, and `StockfishChessEngine.swift` are untouched.
- **`app/build.gradle.kts`** — `iosMain` dependency on Materia consumed from a Maven repo (built separately with Gradle 8.13 per the M1 "Materia consumption recipe"); no `includeBuild`, no submodule. Skip on rescope (Metal-direct needs no extra dependency).

## Tests

Unit tests: **none new in commonTest** — the scene mapper, geometry, and picking are unchanged; that is the payoff of M1's renderer-agnostic design.

UI tests (`app/src/iosSimulatorArm64Test/kotlin/com/example/myapplication/`):

- M1's fake-based `Board3DToggleUiTest` already covers the toggle/lifecycle/fallback paths — it must keep passing untouched.
- Add `dialog renders above 3d surface` — toggle 3D on with the fake support, drive the game into a `pendingPromotion` state (seed via `FenConverter.fenToGameState` like the existing tests), assert `promotion_choice_QUEEN` is displayed and clickable.
- Add `IosRendererSmokeTest` — uses the **real** `iosBoard3DSupport()` factory; `Assume`-style skip (early return with a logged message, since kotlin.test has no Assume) when Metal device creation fails. Asserts the factory returns non-null and `updatePosition(STARTING_FEN)` + attach to a small offscreen `CAMetalLayer` does not crash.

## CI (`.github/workflows/android-tests.yml`, apple job)

- The fake-based toggle tests run under the existing `:app:iosSimulatorArm64Test` — required.
- The smoke test: iOS simulators get Metal via host-GPU paravirtualization on arm64 macOS runners, but treat it as `continue-on-error: true` until proven stable.
- If Materia is adopted: CI must publish it to a Maven repo before building `:app` (a `./gradlew -p materia publish...` step against the pinned Materia checkout, or a pre-published internal artifact). No `submodules: recursive` (composite build is not used).
- Watch static framework size: compare the `linkReleaseFrameworkIosArm64` output before/after; flag in the PR description if it grows by more than ~20 MB.

## Definition of done

- iOS app (simulator) shows the 3D toggle; enabling renders the 3D board; promotion/game-over/draw dialogs render above it; 2D board remains the interaction surface.
- `./gradlew :app:iosSimulatorArm64Test` green; `xcodebuild ... test` (Swift tests) untouched and green.
- Full CI matrix builds (overview "Execution rules").

## Spike result

_To be appended: klib-consumption and CAMetalLayer verdicts, Materia-vs-Metal-direct decision, any fork patches._
