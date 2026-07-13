# iOS Foundation Models Instruments summary

Source: Instruments Foundation Models profiling template (Xcode 26+).
Per plan §7.3 / §9 the published coach must include this summary before iOS Foundation Models
is enabled by default. **Status: TBD.**

## Plan

1. Profile the move-coach flow (cold start, warm first token, complete, memory pressure,
   thermal state before/after) on at minimum one A-series iPhone and one M-series iPad/Mac
   target if supported by Foundation Models.
2. Capture with the Instruments "Foundation Models" template, export summarized tables
   (per §9.1 schema), commit here.
3. Use the p90 + thermal results to derive iOS route thresholds (per §9.2 schema) and update
   `AiRoutePolicies.moveCoachOffline` and `DeviceProfile` tiers in the module.

## Variance table

| Platform | Device | SoC | RAM | OS | Runtime | Model | Accelerator | Compile mode | Cold init p50/p90 | First token p50/p90 | Complete p50/p90 | Peak memory MB | Thermal delta | Fallback rate | Notes |
|---|---|---|---:|---|---|---|---|---|---:|---:|---:|---:|---|---:|---|
| iOS | TBD | TBD | TBD | TBD | Foundation Models | Apple on-device | system | system | TBD | TBD | TBD | TBD | TBD | TBD | Instruments |

## Notes

- Foundation Models requires iOS 26.0+ and Apple Intelligence enabled (region- and
  setting-gated). On unsupported devices the bridge reports `unavailable` and the
  orchestrator falls back deterministically (see `FoundationMoveCoach.swift` /
  `FoundationModelsBridgeRegistry`).
- AFM 3 / AFM 3 Core Advanced model variants (plan §2 source) are not benchmarked here.
  They are forward-looking; verify availability before re-running the matrix.
