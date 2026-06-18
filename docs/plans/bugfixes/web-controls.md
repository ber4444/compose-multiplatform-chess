# Web 3D Controls Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Restore Reset, Offer Draw, and the 3D switch above the web 3D canvas and keep them clickable.

**Architecture:** Correct DOM canvas/Compose stacking, clipping, and pointer routing. Keep GameScreen control semantics shared and avoid web-only duplicate controls.

**Tech Stack:** Compose Multiplatform Wasm, DOM canvas overlay, CSS/pointer events.

---

### Task 1: Trace overlay composition

**Files:** `app/src/commonMain/kotlin/com/example/myapplication/GameScreen.kt`, `app/src/wasmJsMain/kotlin/com/example/myapplication/board3d/WasmBoard3D.kt`, `app/src/wasmJsMain/kotlin/com/example/myapplication/board3d/WebGpuChessRenderer.kt`, Wasm UI tests.

- [ ] Inspect canvas insertion/z-index/size and Compose layer ordering to identify why controls are hidden or non-interactive.
- [ ] Add a Wasm UI/DOM regression test asserting all three tagged controls exist above the canvas and accept pointer input where supported.
- [ ] Fix stacking and pointer-event ownership without breaking board gestures.
- [ ] Run `./gradlew :app:wasmJsTest :app:wasmJsBrowserDevelopmentWebpack` and verify in the browser at desktop and narrow widths.
- [ ] Commit, push, and open a PR against `main` with root cause and browser evidence.
