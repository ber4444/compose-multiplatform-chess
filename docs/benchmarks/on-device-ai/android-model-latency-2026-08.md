# On-device coach measured: Android catalog, AICore, and iOS (2026-08-11)

Status: **Measured on hardware.** Galaxy Z Fold 3 (SM-F926U, Android 15), Cactus
1.4.1-beta, `:androidApp` debug build driven by
`runAndroidBench` (`--ei bench_iterations 3`) over the first three cases of
`evals/golden/candidates.json`. Three cases per model, one run each — enough to
disqualify, not enough to rank two close candidates.

## Why this exists

The Move Coach panel was reported as useless: the model either echoed its own
prompt back or restated the move. The assumed cause was model size, so Android
moved `gemma3-270m` → `qwen3-0.6`. That made it worse in a new way, and the
question became which model in Cactus's catalog can actually serve a per-move
coach. This is the answer, from the device rather than from the model cards.

## The catalog

Logged from `CactusLM.getModels()` on the device (`CactusTextGenerator` prints it
at init). Text-generation entries only; `-pro`, `-embed`, `-vl` and `-tool`
variants omitted:

    functiongemma-270m(182MB)  gemma3-270m(172MB)  qwen3-0.6(394MB)
    qwen3-1.7(1161MB)          gemma3-1b(642MB)    smollm2-360m(227MB)
    lfm2-350m(233MB)           lfm2-700m(467MB)    lfm2-1.2b(722MB)

**There is no Gemma 4.** The newest Gemma offered is `gemma3-1b`. (Gemma 4 E2B
appears elsewhere in this repo, on the *wasm* LiteRT-LM path at ~2 GB — not a
phone download, and not offered by Cactus.)

## Results

`completeMs` equalled `firstTokenMs` on every row: Cactus returns the whole
completion at once, so "to answer" is the full wait the user sees. No streaming.

| Model | Size | To answer (3 cases) | Outcome |
|---|---|---|---|
| `gemma3-270m` | 172 MB | fast | Echoes the prompt or restates the move. Rejected by the validator. |
| `qwen3-0.6` | 394 MB | **20 s / 36 s / 33 s** | Unusable. See below. |
| `gemma3-1b` | 642 MB | **7.6 s / 19.5 s / 5.2 s** | Generic waffle, markdown headings, one refusal. |
| `lfm2-700m` | 467 MB | **6.3 s / 17.2 s / 6.8 s** | Fluent and **factually wrong**. |

### qwen3-0.6 — disqualified on latency

Every `qwen3-*` entry is a reasoning model. It opens with `<think>` and spends
hundreds of tokens there before saying anything:

    <think>
    Okay, the player just played an Nh3. The baseline says it's a strong move.
    I need to explain whether Nh3 is good or bad here.

`/no_think` is Qwen3's documented switch and is **inert through Cactus** — tried
in both the system turn and the user turn, verified by the raw output still
opening with `<think>`. There is no way to turn the deliberation off on this
path, so the whole family is out.

### lfm2-700m — disqualified on truth

Fast enough to consider, and confidently wrong:

> **Nh3** was a strong move because it immediately controls the center of the
> board…

Nh3 is a knight to the rim; it does not touch the centre. The same sentence was
produced for `hxg3`, a black pawn capture. **All three cases passed
`MoveCoachResponseValidator`** — none of them claims check, mate, a capture, or
names the wrong piece, so no gate fires. This is precisely the axis
`move-coach-quality-axes.md` describes as unchecked, now observed on the shipped
Android path.

### gemma3-1b — the best of them, still not good enough

No `<think>` block, so the latency is generation rather than deliberation. But
5–20 s to produce *"a solid move that established a strong foundation for future
development"*, one answer opening `Okay, let's analyze this position…` with a
`**Analysis:**` markdown heading, and one outright refusal (*"I am sorry, I
cannot answer your question because I do not have access to the game history"*).

## Conclusion

**No model in the Cactus catalog beats the deterministic coach on this device, on
either axis.** The deterministic line is produced in microseconds from data the
engine already computed, is correct by construction, and since the motif work now
reads:

> Your bishop on b5 pins the knight on c6 against the king on e8.

against 5–20 s for *"a solid move that established a strong foundation"*, or 6 s
for a sentence that is simply false.

`docs/plans/on-device-coach-rag-unification.md` recorded this once already, from
a desktop run: **"The model lost to the templates."** That was a 270M model and
could be read as an argument for a bigger one. This is the same conclusion at
270M, 600M, 700M and 1B, on real hardware, with latency numbers attached.

## What this does not say

- It does not say a *good* model would lose. It says the models that fit in a
  phone download and answer in reasonable time, in this catalog, do.
- It does not generalise to iOS — see the Foundation Models section below, which
  is the same pipeline succeeding in 1.8 s.
- Three cases per model. That disqualifies; it does not rank.

## Reproducing

```bash
./gradlew :androidApp:installDebug
adb push evals/golden/candidates.json /data/local/tmp/candidates.json
adb shell "run-as io.github.ber4444.chess sh -c 'mkdir -p files/golden && cat /data/local/tmp/candidates.json > files/golden/candidates.json'"
adb shell am start -n io.github.ber4444.chess/com.example.myapplication.MainActivity --ei bench_iterations 3
adb shell "run-as io.github.ber4444.chess cat files/bench/results.jsonl"
```

`bench_iterations` is the number of **runs**, cycling through the golden cases —
pass 100 to cover the full file.

Two bugs were fixed in the harness to get these numbers, both of which made every
row meaningless in a way that still looked like data:

- `modelIdentifier` was hardcoded to `"gemma3-270m"`, so the JSONL mislabelled
  every row after the model changed. It now derives from the route that actually
  ran, since the ML Kit route has no Cactus slug at all.
- The runner called `warmup()`, which returns as soon as init *starts* (B18), so
  generation began before the model had loaded and every row reported
  `fallbackTriggered: true, "no local model"`. It now calls `awaitWarmup()`, which
  `CLAUDE.md` already required of any caller reporting a terminal state.


---

# AICore / ML Kit Prompt API — not available (Pixel 10 Pro XL)

Tested on a Pixel 10 Pro XL (`mustang`, AICore
`0.thirdpartyexperimental.ffdf_aicore_20260723`), which does run AICore.

`MlKitPromptGenerator` reported `Unavailable`, and the reason was two bugs stacked
on a genuine gate:

1. **`releaseStage = ModelReleaseStage.PREVIEW`** asked for a feature the device
   does not provision, and the failure was opaque:
   `[ErrorCode 606] FEATURE_NOT_FOUND: Feature -1 is not available`. `-1` is the
   enum failing to resolve at all. Changed to `STABLE`, the only other value the
   AAR defines, and the id resolves: `Feature 645`.
2. **`download()` was never called.** `GenerativeModel` exposes
   `download(): Flow<DownloadStatus>`; `warmup()` was an empty stub, so a device
   reporting `DOWNLOADABLE` would have stayed that way forever — the probe would
   report not-Available, the decider would pick Cactus, and warmup would never
   run to trigger the download that would have fixed it. Now wired, along with
   `getBaseModelName()` logging.

With both fixed, AICore still answers **`Feature 645 is not available`**. The
third-party Prompt API is gated separately from AICore itself: a device can ship
AICore models for Google's own features and still not expose the generic Prompt
API to third-party apps. `com.google.mlkit:genai-prompt` is `1.0.0-beta4`.

So the ML Kit route could not be benchmarked here. `CLAUDE.md` already recorded
the reason it was rejected once — *"narrow AICore device support"* — and this is
that, confirmed on current hardware, with the two client-side bugs removed so the
next attempt starts from a working client.

---

# iOS Foundation Models — and yes, the Simulator works

**The Simulator does support Foundation Models**, on an Apple Silicon host whose
macOS has Apple Intelligence enabled (here: macOS 26.5.2, opted in). Verified end
to end, not inferred: the app log shows the framework probing
(`com.apple.GenerativeModels:availability`) and then actually running inference
(`com.apple.tokengeneration:Inference — Scrubbing entire prompt as use case is
Foundation Models`). No physical iPhone needed.

Measured on an iPhone 17 Pro simulator:

| Route | To answer | Tokens | Validation | Output |
|---|---|---|---|---|
| `FoundationModels` | **1.8 s** | 10 | passed | *"e4 is a good move because it controls the center."* |

Correct, specific, grounded, and one sentence — and e4 really does control the
centre, unlike `lfm2-700m`'s identical claim about a knight on h3.

**This is the on-device coach working.** Against the Android table above — 20-36 s,
or 5-20 s of waffle, or 6 s of confident falsehood — the same pipeline, the same
prompt and the same validator produce a good answer in under two seconds on iOS.
The problem was never the architecture; it is that Android has no comparable
model available to it.

## Running it

```bash
xcodebuild -project iosApp/iosApp.xcodeproj -scheme iosApp -configuration Debug \
  -sdk iphonesimulator -destination "platform=iOS Simulator,name=iPhone 17 Pro" \
  -derivedDataPath build/ios-dd CODE_SIGNING_ALLOWED=NO build
xcrun simctl install "iPhone 17 Pro" "$(find build/ios-dd/Build/Products -maxdepth 2 -name '*.app' | head -1)"
SIMCTL_CHILD_BENCHMARK_MODE=1 xcrun simctl launch "iPhone 17 Pro" io.github.ber4444.chess
```

`BENCHMARK_MODE` selects `BenchmarkView` (`iosApp/iosApp/iOSApp.swift`), which calls
`runIosBench`. Results land in the app container:

```bash
find "$(xcrun simctl get_app_container 'iPhone 17 Pro' io.github.ber4444.chess data)" -name bench_results.jsonl
```

Note this needs the **real app**, not `:app:iosSimulatorArm64Test` — the Foundation
Models bridge is registered into `FoundationModelsBridgeRegistry` from
`iOSApp.swift`, so the Kotlin/Native test runner reports no vendors at all. Same
constraint as `tools/ios_3d_screenshot.sh` and for the same reason.
