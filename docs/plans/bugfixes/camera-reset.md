# Android 3D Camera Reset Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Prevent excessive zoom after the Android sequence 3D→2D→3D.

**Architecture:** Make renderer recreation consume one canonical camera snapshot and avoid compounding aspect, distance, or scene transforms. Keep normal drag and pinch behavior unchanged.

**Tech Stack:** Compose state, shared orbit camera math, Android SceneView.

---

### Task 1: Trace camera recreation

**Files:** `app/src/commonMain/kotlin/com/example/myapplication/board3d/Board3DHost.kt`, `app/src/commonMain/kotlin/com/example/myapplication/board3d/Math3D.kt`, `app/src/androidMain/kotlin/com/example/myapplication/board3d/AndroidBoard3D.kt`, `app/src/androidMain/kotlin/com/example/myapplication/board3d/AndroidSceneViewChessRenderer.kt`, camera/UI tests.

- [ ] Record camera distance/aspect and SceneView transforms across two renderer creations to identify the compounded value.
- [ ] Add a failing test reproducing two create/dispose cycles and asserting the second initial camera equals the first.
- [ ] Fix the ownership/reset boundary at the source of the accumulated transform.
- [ ] Run `./gradlew :app:desktopTest --tests "*OrbitCameraControllerTest" :androidApp:assembleDebug :app:assembleAndroidDeviceTest` and device tests if available.
- [ ] Commit, push, and open a PR against `main` with the before/after camera values.
