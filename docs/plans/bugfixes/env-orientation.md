# Environment Orientation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Put vegetation on the ground and walls toward the sky on desktop, iOS, and web, then provide bokeh on desktop and iOS.

**Architecture:** Correct cube orientation at the platform upload/face-mapping boundary so skybox and IBL remain aligned. Apply blur through renderer-native depth-of-field or skybox mip sampling without changing Android.

**Tech Stack:** Kotlin Multiplatform, WebGPU/WGSL, SceneKit, Compose resources.

---

### Task 1: Diagnose orientation mapping

**Files:** `app/src/wgpuMain/kotlin/com/example/myapplication/board3d/CubeMapUpload.kt`, `app/src/wgpuMain/kotlin/com/example/myapplication/board3d/WgpuShaders.kt`, `app/src/iosMain/kotlin/com/example/myapplication/board3d/IosBoard3D.kt`, `app/src/iosMain/kotlin/com/example/myapplication/board3d/IosSceneKitChessRenderer.kt`

- [ ] Trace source KTX face order, upload transforms, shader sampling direction, and SceneKit face order; document the exact incorrect axis/sign in the commit/PR.
- [ ] Add a focused face-direction test where practical, or capture before/after target screenshots when GPU behavior is not unit-testable.
- [ ] Implement one orientation correction per backend boundary, keeping skybox and reflections consistent.
- [ ] Configure desktop bokeh through the existing skybox mip path and iOS bokeh through `SCNCamera` depth of field.
- [ ] Run `./gradlew :app:desktopTest :app:wasmJsBrowserDevelopmentWebpack :app:iosSimulatorArm64Test` and `tools/ios_3d_screenshot.sh` when a simulator is available.
- [ ] Commit, push, and open a PR against `main` with root cause and visual evidence.
