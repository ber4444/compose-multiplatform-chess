# On-device AI move coach — benchmark schema and thresholds

Status: **iOS Simulator run is a genuine, verified success (2026-07-29). Android is root-caused but not
green: the real production route decision never reaches Cactus on this device.** Both are n=1 —
not a statistically powered benchmark. See "First real run: findings" for what's trustworthy here
and what isn't yet.

## Required table schema

Per plan §9.1. Each row is one Platform × Runtime × Accelerator configuration.

| Platform | Device | SoC | RAM | OS | Runtime | Model | Accelerator | Compile mode | Cold init p50/p90 | First token p50/p90 | Complete p50/p90 | Peak memory MB | Thermal delta | Fallback rate | Notes |
|---|---|---|---:|---|---|---|---|---|---:|---:|---:|---:|---|---:|---|
| iOS | iPhone 17 (Simulator, iOS 26.5) | Apple M4 (host) | n/a | iOS 26.5 | Foundation Models | Apple on-device (system) | Neural Engine (via host) | system | ~1 ms (`warmup()` is a no-op by design) | 1864 ms (n=1) | 1865 ms (n=1) | not collected (iOS bench stub returns 0) | not collected | **0% (0/1) — real success** | 30 real tokens, valid coaching answer, correctly parsed after fixing markdown-fence stripping. This is a **Simulator**, not a physical iPhone |
| Android | SM-F926U (Galaxy Z Fold3 5G) | Snapdragon 888 | n/a | Android 15 | ML Kit Prompt | Gemini Nano (AICore) | AICore | system | not measured (see caveat below) | n/a — 0 tokens, fails before first token | n/a | not collected | not collected | 100% (1/1), **root cause now known and accurate**: `[ErrorCode -101] AICore is either not installed or the installed version is too low` | This is the route the real orchestrator actually picks on this device — see findings |
| Android | SM-F926U (Galaxy Z Fold3 5G) | Snapdragon 888 | n/a | Android 15 | Cactus (llama.cpp) | gemma3-270m | CPU | JIT | ~700 ms (n=1 cold, model already on disk) | not exercised through the real route | not exercised through the real route | ~325 MB (`Debug.getNativeHeapAllocatedSize` + PSS) | not collected | n/a | **The bench warms this generator up manually and it loads successfully, but the real orchestrator never selects this route on this device** — see findings. The 700ms is Cactus's own init, not what the app experiences |

## First real run: findings (2026-07-29)

Every prior run of this harness reported 100% fallback with sub-millisecond init — i.e., it never
actually invoked a real runtime. Root-causing it surfaced four distinct bugs, at three different
layers: the bench harness itself, the shared orchestrator, and Android's production ML Kit
generator.

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
pre-parse text — without it, both real bugs below were invisible behind the single ambiguous
`AiRoutePolicyDecider.FALLBACK_VALIDATION` reason string.

### 3. iOS: markdown code fence around otherwise-valid JSON (fixed, shared code — `DefaultAiCoachOrchestrator.kt`)

Foundation Models wrapped its JSON response in a ` ```json ... ``` ` fence even though the prompt
only says "output valid JSON," with no instruction about markdown. The raw text was genuinely
correct, useful coaching prose:

> `{"headline": "Pawn e2→e4", "explanation": "This move is a classic pawn advance, which opens up
> the center and prepares the pawn for further development and potential attacks."}`

`Json.decodeFromString<MoveCoachResponse>` can't parse a fenced string directly, so this was
discarded as a validation failure every time. Fixed by stripping a wrapping fence before parsing
(`stripJsonCodeFence`, same category as the LiteRT-LM `<think>`-stripping fix — clean a model's
habitual decoration before treating its output as data). **Verified: the bench now completes with
`fallbackTriggered: false` and a real 30-token answer.**

### 4. Android: `MlKitPromptGenerator` disguised its own errors as successful output (fixed — production code, not the bench)

`generate()` caught its own exceptions and emitted `{"error": "<message>"}` as if it were a
successful completion, rather than letting it propagate. `DefaultAiCoachOrchestrator.runOnDevice`
already has a clean outer `catch (t: Throwable) { fallback(request, "generation error: ${t.message}") }`
around generation — the try/catch inside the generator was actively working against it, converting
what should have been an accurate error into a confusing downstream JSON-parse failure. Fixed by
removing the swallow; the real cause now surfaces cleanly: `generation error: [ErrorCode -101]
AICore is either not installed or the installed version is too low.`

### The bigger finding this uncovered: Cactus is unreachable from the real route decision on this device

`resolveVendorRoute` (`VendorRouteExecutor.android.kt`) always resolves an offline/`LOCAL_ONLY`
policy to `VendorRoute.MlKitPrompt` — it never returns `VendorRoute.CactusLocal` directly. The
executor's own fallback (`if (mlkit.status() is AiAvailability.Available) mlkit else getCactus()`)
is the *only* path to Cactus, and `MlKitPromptGenerator.status()` is a hardcoded stub that always
returns `Available` (its own comment: "for now we just return Available"). So on any device where
AICore genuinely isn't installed — this Galaxy Z Fold3 (Snapdragon 888) included — the real Move
Coach feature always tries ML Kit, always fails, and **never falls through to Cactus**, even though
the docs (`android-delivery-decision.md`, this repo's `CLAUDE.md`) describe Cactus as the shipped
Android baseline and ML Kit as an "optional higher-tier route." The bench's own manual
`VendorRoute.CactusLocal()` warmup proves Cactus works fine on this hardware — it's just never
reached through the real code path. **Not fixed this session** — fixing it means either implementing
a real ML Kit availability check (needs API knowledge not verified here) or reordering
`resolveVendorRoute`'s priority, both of which are product/architecture decisions, not harness fixes.

### Known remaining gap in the harness itself

`onInitStart`/`onInitEnd` only time the bench's own manually-constructed generator
(`VendorRoute.CactusLocal()` on Android). Whenever the orchestrator's real route decision picks a
*different* generator — which, per the finding above, it always currently does on Android — the
bench's cold-init number describes a generator the app never actually uses. The 700ms Cactus number
in the table above is real, but it isn't what a real coached move on this device experiences.

## Route thresholds derived from benchmarks

Per plan §9.2. Populated after the variance table is filled.

| Device class | Default route | Disable reason | First-token budget | Complete budget | Notes |
|---|---|---|---:|---:|---|
| Android (any with sufficient RAM) | Cactus Gemma (llama.cpp CPU) | p90 > budget or thermal high | TBD | TBD | gemma3-270m HF-downloaded; documented as the baseline Android route, but **currently unreachable from the real route decision** — see findings above |
| Android supported AICore only | ML Kit Prompt | quota/busy/background | TBD | TBD | On unsupported hardware this fails immediately (`ErrorCode -101`) and falls back — confirmed live on Snapdragon 888 |
| iOS Apple Intelligence available | Foundation Models | unavailable/region/guardrail | TBD | TBD | **Confirmed working** on iPhone 17 Simulator / iOS 26.5 / Apple M4 host: ~1.9s end-to-end for a real answer |
| Unsupported mobile | Deterministic fallback | no local model | 0 | 0 | Always works |

## Provisional latency budgets (pre-benchmark)

The move-coach policy ships with these placeholders (see `AiRoutePolicies.moveCoachOffline`).
They are **design targets, not measured limits**:

- `firstTokenMs = 900`
- `completeMs = 3500`
- `costBudget = 0.0` (LOCAL_ONLY)

The one real measurement so far — iOS Foundation Models, ~1864ms first-token — already exceeds the
900ms budget on a Simulator running on an Apple M4 host, which should be a best case, not a worst
case. Cold Cactus/llama.cpp init alone can exceed the 900 ms first-token budget on first coached
move (observed ~700ms just for init, before any generation, on `gemma3-270m`). The §6.3 benchmark
gate (block enabling by default until p90 + thermal pass) replaces these before any release-ship.

## Files

- `android-ai-edge-portal-summary.md` — historical AI Edge Portal export (superseded by automated harness)
- `android-ai-edge-portal-raw-export.csv` — historical raw export
- `ios-foundation-models-instruments-summary.md` — summarized Instruments trace
- `android-delivery-decision.md` — output of the §6.1.1 model-delivery spike
