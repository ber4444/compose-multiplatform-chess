# On-device AI move coach — benchmark schema and thresholds

Status: **first real runs captured, 2026-07-29 — n=1 per cell, not yet a statistically powered benchmark.** Both rows below reached genuine on-device generation (not a routing short-circuit) for the first time, but both still end in fallback at the post-generation validation step — see "First real run: findings" before trusting any single number here. Re-run with a larger `iterations` count once that's root-caused.

## Required table schema

Per plan §9.1. Each row is one Platform × Runtime × Accelerator configuration.

| Platform | Device | SoC | RAM | OS | Runtime | Model | Accelerator | Compile mode | Cold init p50/p90 | First token p50/p90 | Complete p50/p90 | Peak memory MB | Thermal delta | Fallback rate | Notes |
|---|---|---|---:|---|---|---|---|---|---:|---:|---:|---:|---|---:|---|
| Android | SM-F926U (Galaxy Z Fold3 5G) | Snapdragon 888 | n/a | Android 15 | Cactus (llama.cpp) | gemma3-270m | CPU | JIT | ~700 ms (n=1 cold) | ~15 ms (n=3, all warm-cache) | ~15 ms (n=3) | ~325 MB (`Debug.getNativeHeapAllocatedSize` + PSS) | not collected | 100% (3/3) | Model was already on disk; first-token/complete numbers are not trustworthy — see findings below, this looks like a near-empty completion, not real decode |
| iOS | iPhone 17 (Simulator, iOS 26.5) | Apple M4 (host) | n/a | iOS 26.5 | Foundation Models | Apple on-device (system) | Neural Engine (via host) | system | ~1 ms (`warmup()` is a no-op by design; real cost is in first generate call) | 2907 ms (n=1) | 2909 ms (n=1) | not collected (iOS bench stub returns 0) | not collected | 100% (1/1) | Real generation: 27 tokens produced. This is a **Simulator**, not a physical iPhone — no physical-device run has been done yet |
| Android | TBD | TBD | TBD | TBD | ML Kit Prompt | Gemini Nano | AICore | system | TBD | TBD | TBD | TBD | TBD | TBD | Optional higher-tier route; narrow AICore support — not exercised this run |

## First real run: findings (2026-07-29)

Every prior run of this harness reported 100% fallback with sub-millisecond init — i.e., it never actually invoked a real runtime. Two bugs in the bench wiring itself caused that, both fixed in this session:

1. **Android: `initializeCactus(context)` was never called on the bench path.** `MainActivity.onCreate`'s bench branch (`intent.hasExtra("bench_iterations")`) returns before reaching the line that initializes Cactus's native context for the normal app flow. `VendorRouteExecutor.android.kt`'s own `getCactus()` comment warns "Assume context was initialized earlier, otherwise this will throw" — in practice the failure is caught and silently downgraded to a fallback, so every run reported `fallbackTriggered: true` in under 1ms with no indication why. Fixed by calling `initializeCactus(this)` in the bench branch.
2. **Both platforms: `DefaultAiCoachOrchestrator` was constructed without a `contextProvider`.** Its built-in default (`isDeviceModelAvailable = false`) is a conservative fallback for callers that don't supply one — every real entry point (`MainActivity`, `Main.kt`, `AppRoot.kt`, iOS `MainViewController`) overrides it to `true`. The bench runners never did, so `resolveVendorRoute` returned `null` before ever attempting Cactus or checking `SystemLanguageModel.default.availability` — meaning **the iOS "Foundation Models unavailable" result from every run before this session was this bug, not a real availability check.** Fixed by passing `contextProvider = { AiContextSnapshot(isDeviceModelAvailable = true) }` in both `AndroidBenchRunner` and `IosBenchRunner`.

Also added: `BenchResult.fallbackReason` (previously the JSONL only recorded `fallbackTriggered: Boolean` with no reason string, even though the orchestrator already threads one through — `onFallback(reason: String)` in `DefaultAiCoachOrchestrator`).

**After both fixes, a third issue remains, unresolved:** both platforms now reach real generation and still fall back, both with reason `"model output failed validation"`. That string is ambiguous by construction — `DefaultAiCoachOrchestrator.runGeneration` returns the same `AiRoutePolicyDecider.FALLBACK_VALIDATION` constant whether the failure is (a) the model's raw text not parsing as the expected `{"headline", "explanation"}` JSON, or (b) valid JSON that then fails `MoveCoachResponseValidator`'s grounding/forbidden-phrase check — the bench data can't currently distinguish which. On iOS the real 27-token generation makes (a) look less likely than on Android, where `tokenCount: 0` and a ~15ms "generation" strongly suggest an empty or near-empty completion rather than real decode. This wasn't root-caused further this session — it needs either reading the raw pre-parse text (not currently logged) or adding a temporary diagnostic log, and touches the production prompt/validator, not just the bench harness, so it's flagged here rather than patched.

## Route thresholds derived from benchmarks

Per plan §9.2. Populated after the variance table is filled.

| Device class | Default route | Disable reason | First-token budget | Complete budget | Notes |
|---|---|---|---:|---:|---|
| Android (any with sufficient RAM) | Cactus Gemma (llama.cpp CPU) | p90 > budget or thermal high | TBD | TBD | gemma3-270m HF-downloaded; baseline Android route |
| Android supported AICore only | ML Kit Prompt | quota/busy/background | TBD | TBD | Optional higher-tier Gemini Nano path |
| iOS Apple Intelligence available | Foundation Models | unavailable/region/guardrail | TBD | TBD | From Instruments |
| Unsupported mobile | Deterministic fallback | no local model | 0 | 0 | Always works |

## Provisional latency budgets (pre-benchmark)

The move-coach policy ships with these placeholders (see `AiRoutePolicies.moveCoachOffline`).
They are **design targets, not measured limits**:

- `firstTokenMs = 900`
- `completeMs = 3500`
- `costBudget = 0.0` (LOCAL_ONLY)

Cold Cactus/llama.cpp init alone can exceed the 900 ms first-token budget on first
coached move (observed ~1–2 s on `gemma3-270m`). The §6.3 benchmark gate (block enabling by default until p90 + thermal pass) replaces these
before any release-ship.

## Files

- `android-ai-edge-portal-summary.md` — historical AI Edge Portal export (superseded by automated harness)
- `android-ai-edge-portal-raw-export.csv` — historical raw export
- `ios-foundation-models-instruments-summary.md` — summarized Instruments trace
- `android-delivery-decision.md` — output of the §6.1.1 model-delivery spike
