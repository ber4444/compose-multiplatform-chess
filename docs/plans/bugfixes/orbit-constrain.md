# iOS Camera Clamp Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Prevent iOS users from rotating the board upside down while preserving rotation and zoom.

**Architecture:** Determine whether inversion originates in shared pitch state, iOS gesture deltas, or SceneKit camera application. Enforce the non-inverting constraint at the earliest shared/platform boundary that does not change correct Android behavior.

**Tech Stack:** Shared camera math, Compose gestures, SceneKit.

---

### Task 1: Reproduce vertical inversion

**Files:** `app/src/commonMain/kotlin/com/example/myapplication/board3d/Math3D.kt`, `app/src/commonMain/kotlin/com/example/myapplication/board3d/Board3DHost.kt`, `app/src/iosMain/kotlin/com/example/myapplication/board3d/IosBoard3D.kt`, `app/src/iosMain/kotlin/com/example/myapplication/board3d/IosSceneKitChessRenderer.kt`, camera tests.

- [ ] Trace a large vertical drag from pointer delta to final SceneKit camera/up vector and compare Android application.
- [ ] Add a failing regression test asserting repeated iOS-style drags cannot produce an inverted camera.
- [ ] Fix the sign/clamp/application boundary while retaining full horizontal orbit and zoom range.
- [ ] Run `./gradlew :app:desktopTest --tests "*OrbitCameraControllerTest" :app:iosSimulatorArm64Test` and the iOS screenshot/runtime path.
- [ ] Commit, push, and open a PR against `main` with root cause and gesture evidence.
