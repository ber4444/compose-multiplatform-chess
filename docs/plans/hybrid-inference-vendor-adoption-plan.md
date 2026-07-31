# Plan: Hybrid Inference Vendor Adoption

**Status:** in-progress

## Overview
This plan outlines the staged adoption of hybrid (on-device and cloud) inference SDKs across all Compose Multiplatform targets, allowing the orchestrator to dynamically route requests based on offline capability, user preference, and OS-provided ML APIs.

## Phases

### VA-1: Widen the Seam (Status: completed)
- Introduce the `AiRouteExecutor` abstraction to decouple `DefaultAiCoachOrchestrator` and `DefaultGameSummaryOrchestrator` from direct ML SDK calls.
- Standardize the JSON structured output interface across generators.

### VA-2: Android (Status: completed)
- Implement `MlKitPromptGenerator` for fully offline, local inference via Google ML Kit.
- Implement `FirebaseHybridGenerator` using `Firebase.vertexAI` for cloud inference.
- Route dynamically based on `effectiveOfflineOnly` flags and privacy class.

### VA-3: iOS Foundation Models (Status: proposed)
- Update `Orchestrator` and `FoundationModelsBridge` for native iOS Foundation Models.
- Hook into Apple's on-device APIs for offline inference.

### VA-4: Desktop Backend (Status: proposed)
- Provide a standalone desktop implementation (e.g., local ONNX, server proxy, or mock fallback).

### VA-5: Web / Wasm Backend (Status: proposed)
- Implement browser-compatible WebGL/WebGPU or standard REST inference routes.

### VA-6: Telemetry & Quota Handling (Status: proposed)
- Gracefully handle `AiAvailability.Busy` and quota limits across vendors.

### VA-7: UI & UX Integration (Status: proposed)
- Expose the chosen model route (Local vs. Cloud) transparently in the application UI.

### VA-8: Documentation (Status: proposed)
- Finalize article updates once manual measurements are complete.
- Before wiring quality into routing decisions, see `docs/benchmarks/on-device-ai/move-coach-quality-axes.md` — capability/privacy routing currently has no quality dimension, and the local model's reason-faithfulness and piece-type accuracy both measured below its honesty rate.
