# WebGPU Rim Lighting Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give the desktop/web stone rim realistic lighting consistent with the chess board.

**Architecture:** Route rim fragments through appropriate normals, direct light, and environment response rather than a flat/unlit color path. Preserve stone roughness and keep desktop/web shader behavior shared.

**Tech Stack:** WebGPU, WGSL, shared WGPU geometry/materials.

---

### Task 1: Compare board and rim shading paths

**Files:** `app/src/wgpuMain/kotlin/com/example/myapplication/board3d/WgpuShaders.kt`, `app/src/wgpuMain/kotlin/com/example/myapplication/board3d/ChessSceneGeometry.kt`, desktop/WebGPU tests.

- [ ] Trace vertex normals, material flags, and fragment-lighting branches for board squares versus rim geometry.
- [ ] Add a shader/source or frame-dump regression assertion that fails for the unlit rim path.
- [ ] Implement physically plausible rim diffuse/specular/environment response using existing bindings.
- [ ] Run `./gradlew :app:desktopTest --tests "*board3d*" :app:wasmJsBrowserDevelopmentWebpack` and inspect `build/wgpu-frame.png` when produced.
- [ ] Commit, push, and open a PR against `main` with root cause and frame evidence.
