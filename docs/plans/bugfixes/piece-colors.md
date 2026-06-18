# Android White Piece Material Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Render Android white pieces with the intended white material instead of black.

**Architecture:** Correct color-to-material/texture binding where pooled `ModelNode` instances are configured. Do not alter geometry loading or other platform renderers.

**Tech Stack:** Android SceneView, Filament glTF materials, Kotlin.

---

### Task 1: Verify material binding

**Files:** `app/src/androidMain/kotlin/com/example/myapplication/board3d/AndroidBoard3D.kt`, `app/src/androidMain/kotlin/com/example/myapplication/board3d/AndroidSceneViewChessRenderer.kt`, Android renderer tests.

- [ ] Inspect mesh/material names and current white/black assignment; identify whether the defect is lookup, instance reuse, or material mutation.
- [ ] Add the smallest failing mapping/pool regression test possible.
- [ ] Correct the binding without changing black-piece rendering.
- [ ] Run `./gradlew :androidApp:assembleDebug :app:assembleAndroidDeviceTest`; capture Android emulator evidence if available.
- [ ] Commit, push, and open a PR against `main` with root cause and evidence.
