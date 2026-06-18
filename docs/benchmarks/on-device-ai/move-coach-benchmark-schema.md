# On-device AI move coach — benchmark schema and thresholds

Status: **schema only — no measured values yet.** Per plan §9, measured values land
after M6 (AI Edge Portal for Android + Instruments for iOS) and are a hard gate before
the coach is enabled outside debug builds.

## Required table schema

Per plan §9.1. Each row is one Platform × Runtime × Accelerator configuration.

| Platform | Device | SoC | RAM | OS | Runtime | Model | Accelerator | Compile mode | Cold init p50/p90 | First token p50/p90 | Complete p50/p90 | Peak memory MB | Thermal delta | Fallback rate | Notes |
|---|---|---|---:|---|---|---|---|---|---:|---:|---:|---:|---|---:|---|
| Android | TBD | TBD | TBD | TBD | LiteRT-LM | Gemma TBD | NPU | AOT/JIT | TBD | TBD | TBD | TBD | TBD | TBD | AI Edge Portal |
| Android | TBD | TBD | TBD | TBD | ML Kit Prompt | Gemini Nano | AICore | system | TBD | TBD | TBD | TBD | TBD | TBD | Device-local run |
| iOS | TBD | TBD | TBD | TBD | Foundation Models | Apple on-device | system | system | TBD | TBD | TBD | TBD | TBD | TBD | Instruments |

## Route thresholds derived from benchmarks

Per plan §9.2. Populated after the variance table is filled.

| Device class | Default route | Disable reason | First-token budget | Complete budget | Notes |
|---|---|---|---:|---:|---|
| Android high tier NPU | LiteRT-LM Gemma | p90 > budget or thermal high | TBD | TBD | From AI Edge Portal |
| Android supported AICore only | ML Kit Prompt | quota/busy/background | TBD | TBD | Gemini Nano path |
| iOS Apple Intelligence available | Foundation Models | unavailable/region/guardrail | TBD | TBD | From Instruments |
| Unsupported mobile | Deterministic fallback | no local model | 0 | 0 | Always works |

## Provisional latency budgets (pre-benchmark)

The move-coach policy ships with these placeholders (see `AiRoutePolicies.moveCoachOffline`).
They are **design targets, not measured limits**:

- `firstTokenMs = 900`
- `completeMs = 3500`
- `costBudget = 0.0` (LOCAL_ONLY)

Cold NPU/model init alone can exceed the 900 ms first-token budget on first coached move.
The §6.3 benchmark gate (block enabling by default until p90 + thermal pass) replaces these
before any release-ship.

## Files

- `android-ai-edge-portal-summary.md` — summarized AI Edge Portal export (TBD)
- `android-ai-edge-portal-raw-export.csv` — verbatim AI Edge Portal export, if preview terms permit
- `ios-foundation-models-instruments-summary.md` — summarized Instruments trace
- `android-delivery-decision.md` — output of the §6.1.1 model-delivery spike
