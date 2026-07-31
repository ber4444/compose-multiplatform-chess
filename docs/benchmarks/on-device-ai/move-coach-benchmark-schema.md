# On-device AI move coach — benchmark schema and thresholds

Status: **both platforms produced a genuine, verified real success (2026-07-29).** Both are n=1 —
not a statistically powered benchmark; re-run with a larger `iterations` count before trusting a
specific latency number. See "First real run: findings" for the full bug history and what's still
open.

## Required table schema

Per plan §9.1. Each row is one Platform × Runtime × Accelerator configuration.

| Platform | Device | SoC | RAM | OS | Runtime | Model | Accelerator | Compile mode | Cold init p50/p90 | First token p50/p90 | Complete p50/p90 | Peak memory MB | Thermal delta | Fallback rate | Notes |
|---|---|---|---:|---|---|---|---|---|---:|---:|---:|---:|---|---:|---|
| iOS | iPhone 17 (Simulator, iOS 26.5) | Apple M4 (host) | n/a | iOS 26.5 | Foundation Models | Apple on-device (system) | Neural Engine (via host) | system | ~1 ms (`warmup()` is a no-op by design) | 1864 ms (n=1) | 1865 ms (n=1) | not collected (iOS bench stub returns 0) | not collected | **0% (0/1) — real success** | 30 real tokens, valid coaching answer. This is a **Simulator**, not a physical iPhone |
| Android | SM-F926U (Galaxy Z Fold3 5G) | Snapdragon 888 | n/a | Android 15 | Cactus | gemma3-270m | CPU | JIT | ~700 ms (n=1 cold, model already on disk) | 2715 ms (n=1) | 2716 ms (n=1) | ~603 MB (`Debug.getNativeHeapAllocatedSize` + PSS) | not collected | **0% (0/1) — real success, reached via the real route decision** | 301 real tokens, valid coaching answer. This is the route `resolveVendorRoute`/`VendorRouteExecutor` actually picked once `MlKitPromptGenerator.status()` was fixed — see findings |
| Android | Pixel 10 Pro XL | Tensor G5 | n/a | Android 16 | Cactus | gemma3-270m | CPU | JIT | ~723 ms / ~743 ms | 2104 ms / 3362 ms | 2104 ms / 3363 ms | ~593 MB | not collected | **80% (4/5)** | 4 runs failed validation because the model copied the prompt's JSON skeleton instead of evaluating the position. 1 valid run. |
| Android | SM-F926U (Galaxy Z Fold3 5G) | Snapdragon 888 | n/a | Android 15 | ML Kit Prompt | Gemini Nano (AICore) | AICore | system | n/a | n/a — fails before first token | n/a | not collected | not collected | 100% (1/1) — **correctly detected as unavailable and skipped**, not attempted-and-failed | Real, accurate result: `checkStatus()` reports this device doesn't have working AICore, so the executor now falls through to Cactus without ever calling `generateContentStream` |

## First real run: findings (2026-07-29)

Every prior run of this harness reported 100% fallback with sub-millisecond init — i.e., it never
actually invoked a real runtime. Root-causing it surfaced five distinct bugs across three layers:
the bench harness itself, the shared orchestrator, and Android's production ML Kit generator.

### 1–2. Bench harness bugs (fixed, harness-only)

- **Android: `initializeCactus(context)` was never called on the bench path.** `MainActivity`'s
  bench branch returns before the line that initializes Cactus's native context for the normal app
  flow. `VendorRouteExecutor.android.kt`'s own `getCactus()` comment warns "otherwise this will
  throw" — the failure was silently downgraded to a fallback with no indication why.
- **Both platforms: the bench runners constructed `DefaultAiCoachOrchestrator` without a
  `contextProvider`.** Its built-in default (`isDeviceModelAvailable = false`) is a conservative
  fallback for callers that don't supply one — every real entry point overrides it to `true`. The
  bench never did, so `resolveVendorRoute` returned `null` before ever attempting a real generator
  or checking `SystemLanguageModel.default.availability` — meaning **every prior "Foundation Models
  unavailable" result was this routing bug, not a real availability check.**

Also added: `BenchProbe.onRawOutput` and `BenchResult.rawOutput`, so the bench can capture the raw
pre-parse text — without it, none of bugs 3–5 below would have been diagnosable; they were all
invisible behind the single ambiguous `AiRoutePolicyDecider.FALLBACK_VALIDATION` reason string.

### 3. Markdown code fence around otherwise-valid JSON (fixed, shared code — `DefaultAiCoachOrchestrator.kt`)

Both Foundation Models (iOS) *and* Cactus/gemma3-270m (Android) wrap their JSON response in a
` ```json ... ``` ` fence even though the prompt only says "output valid JSON," with no instruction
about markdown. The raw text was genuinely correct, useful coaching prose in both cases —
`Json.decodeFromString<MoveCoachResponse>` just can't parse a fenced string directly, so it was
discarded as a validation failure every time. Fixed once, in shared code, by stripping a wrapping
fence before parsing (`stripJsonCodeFence` — same category as the LiteRT-LM `<think>`-stripping fix:
clean a model's habitual decoration before treating its output as data). Confirmed to matter for
*both* platforms independently once the routing bugs below were also fixed.

### 4. Android: `MlKitPromptGenerator` disguised its own errors as successful output (fixed)

`generate()` caught its own exceptions and emitted `{"error": "<message>"}` as if it were a
successful completion, rather than letting it propagate. `DefaultAiCoachOrchestrator.runOnDevice`
already has a clean outer `catch (t: Throwable) { fallback(request, "generation error: ${t.message}") }`
around generation — the try/catch inside the generator was actively working against it. Removed the
swallow so a real failure surfaces as an accurate `"generation error: ..."` fallback instead of a
disguised JSON-parse failure.

### 5. Android: `MlKitPromptGenerator.status()` was a hardcoded stub — Cactus was unreachable (fixed)

The real bug behind bug 4. `status()` always returned `Available` regardless of actual device
support ("for now we just return Available"). `VendorRouteExecutor`'s own fallback
(`if (mlkit.status() is AiAvailability.Available) mlkit else getCactus()`) is the *only* path to
Cactus for an offline/`LOCAL_ONLY` policy — `resolveVendorRoute` never returns `CactusLocal`
directly — so with `status()` lying, Cactus was silently unreachable on any device without working
AICore, this Galaxy Z Fold3 (Snapdragon 888) included, despite the docs describing Cactus as the
shipped Android baseline.

Fixed with the real ML Kit GenAI API, decompiled from the actual `genai-prompt:1.0.0-beta3` /
`genai-common:1.0.0-beta4` jars rather than assumed: `GenerativeModel.checkStatus(): Int`, mapped
via `com.google.mlkit.genai.common.FeatureStatus` (`AVAILABLE=3, DOWNLOADABLE=1, DOWNLOADING=2,
UNAVAILABLE=0`) to the app's own `AiAvailability` sealed type, with any thrown exception mapped to
`AiAvailability.Error` rather than assumed impossible — either outcome makes the executor's existing
`else getCactus()` branch finally reachable.

**Verified live, in order:**
1. Before this fix: `fallbackReason: "generation error: [ErrorCode -101] AICore is either not
   installed or the installed version is too low"` — accurate, but Cactus still never tried.
2. After this fix: the ML Kit row above shows the same real condition **detected up front** and
   skipped — no failed `generateContentStream` call at all — and the executor falls through to
   Cactus, which then completes with `fallbackTriggered: false` and 301 real tokens.

### Remaining, known gap in the harness itself

`onInitStart`/`onInitEnd` only time the bench's own manually-constructed generator
(`VendorRoute.CactusLocal()` on Android). The 700ms Cactus cold-init figure above happens to be
correct now that Cactus is genuinely the route taken, but that's coincidental — if the real route
decision ever picks a *different* generator than the one the bench warms up manually, the init
timing will silently describe the wrong thing again. Not fixed; flagging for whoever next extends
this harness.

## Route thresholds derived from benchmarks

Per plan §9.2. Populated after the variance table is filled.

| Device class | Default route | Disable reason | First-token budget | Complete budget | Notes |
|---|---|---|---:|---:|---|
| Android (any with sufficient RAM) | Cactus Gemma | p90 > budget or thermal high | TBD | TBD | gemma3-270m HF-downloaded; **confirmed reachable and working** as the fallback route once `MlKitPromptGenerator.status()` was fixed |
| Android supported AICore only | ML Kit Prompt | quota/busy/background | TBD | TBD | On unsupported hardware this is now correctly detected as unavailable and skipped (confirmed on Snapdragon 888) rather than attempted-and-failed |
| iOS Apple Intelligence available | Foundation Models | unavailable/region/guardrail | TBD | TBD | **Confirmed working** on iPhone 17 Simulator / iOS 26.5 / Apple M4 host: ~1.9s end-to-end for a real answer |
| Unsupported mobile | Deterministic fallback | no local model | 0 | 0 | Always works |

## Provisional latency budgets (pre-benchmark)

The move-coach policy ships with these placeholders (see `AiRoutePolicies.moveCoachOffline`).
They are **design targets, not measured limits**:

- `firstTokenMs = 900`
- `completeMs = 3500`
- `costBudget = 0.0` (LOCAL_ONLY)

Both real measurements so far exceed the 900ms first-token budget: iOS Foundation Models on a
Simulator/Apple M4 host at ~1864ms, and Android Cactus/gemma3-270m on a real Snapdragon 888 at
~2715ms (plus ~700ms cold init beforehand, since this was the model's first load after a fresh
install). The §6.3 benchmark gate (block enabling by default until p90 + thermal pass) replaces
these before any release-ship — n=1 per platform is nowhere near enough to set a real threshold,
but both real numbers already say the provisional budget is optimistic.

## Files

- `android-ai-edge-portal-summary.md` — historical AI Edge Portal export (superseded by automated harness)
- `android-ai-edge-portal-raw-export.csv` — historical raw export
- `ios-foundation-models-instruments-summary.md` — summarized Instruments trace
- `android-delivery-decision.md` — output of the §6.1.1 model-delivery spike
