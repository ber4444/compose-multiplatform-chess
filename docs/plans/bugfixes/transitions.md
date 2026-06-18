# Android 3D Transition Smoothness Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make Android 2D↔3D transitions responsive, with promptly visible and continuously animated progress UI.

**Architecture:** Separate transition state from final view state and move expensive renderer creation/disposal outside the UI-critical frame. Let Compose display loader state before work begins and complete only after renderer lifecycle work finishes.

**Tech Stack:** Compose Multiplatform, Kotlin coroutines, Android SceneView/Filament.

---

### Task 1: Reproduce and instrument transition blocking

**Files:** `app/src/commonMain/kotlin/com/example/myapplication/GameScreen.kt`, `app/src/commonMain/kotlin/com/example/myapplication/ChessLoader.kt`, `app/src/commonMain/kotlin/com/example/myapplication/board3d/Board3DHost.kt`, `app/src/androidMain/kotlin/com/example/myapplication/board3d/AndroidBoard3D.kt`, Android 3D UI tests.

- [ ] Identify which initialization/disposal calls execute on the main thread and why the loader enters composition too late.
- [ ] Add a failing UI/state regression test that proves transition state becomes visible before lifecycle work and controls remain responsive.
- [ ] Implement explicit entering/leaving states and dispatcher-safe lifecycle work without arbitrary sleeps.
- [ ] Verify the loader uses Compose animation state and is not frozen by main-thread work.
- [ ] Run `./gradlew :app:desktopTest --tests "*Board3DUiTest" :androidApp:assembleDebug :app:assembleAndroidDeviceTest`; run device tests when an emulator is available.
- [ ] Commit, push, and open a PR against `main` with timing/root-cause evidence.
