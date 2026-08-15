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

## The Android emulator cannot substitute

Asked directly, and answered on the machine: **no.** The API 37
`google_apis_playstore` image (`sdk_gphone16k_arm64`) does not ship
`com.google.android.aicore` at all —

```
$ adb shell pm list packages | grep -i aicore
(nothing)
$ adb shell pm list packages | grep -iE "gms|vending"
package:com.android.vending
package:com.google.android.gms
```

Play Store and GMS are present; AICore is not, and it is not installable from
Play — it is a device-gated system component, not an app.

Running the probe on the emulator confirms it end to end: `MLKit status:
Unavailable`, and — the useful part — **no `ErrorCode 606` at all**. The two
failures are distinguishable, which is worth knowing before debugging the next
one:

| Where | Signature | Meaning |
|---|---|---|
| Emulator | `Unavailable`, no error code | No AICore on the image |
| Pixel 10 Pro XL | `606 FEATURE_NOT_FOUND: Feature 645` | AICore present, Prompt API not provisioned to third parties |

This is the opposite of iOS, where the Simulator *does* provide Foundation Models
through the host Mac. There is no equivalent host-passthrough on Android: testing
the ML Kit route needs physical hardware that provisions the feature, and the
Pixel 10 Pro XL above does not.

So the ML Kit route could not be benchmarked initially. `CLAUDE.md` already recorded
the reason it was rejected once — *"narrow AICore device support"* — and this is
that, confirmed on current hardware, with the two client-side bugs removed so the
next attempt starts from a working client.

**UPDATE (August 2026):** A follow-up probe measured ML Kit availability against three client
configurations on the same Pixel 10 Pro XL:

| Variant | Result |
|---|---|
| `preference = FAST` | `UNAVAILABLE` — `FEATURE_NOT_FOUND: Feature 645` |
| `sample-default` (empty `generationConfig { }`) | `AVAILABLE`, `baseModelName = nano-v3` |
| `preference = FULL` | `AVAILABLE`, `baseModelName = nano-v3` |

So the paragraph above is wrong, and wrong in an instructive way: the device provisions the Prompt
API perfectly well. What it does not provision is the *FAST* model variant, which is the only one
this codebase ever asked for. FAST and FULL select different base models and therefore different
AICore feature ids, and the Google sample sets neither — `Generation.getClient(generationConfig { })`
is the whole of its setup. A one-line client config turned a working device into "narrow AICore
device support".

**Measured latency** (`runAndroidBench`, `mlkit-aicore-full`, first three opening cases):

| Case | Init (ms) | TTFT (ms) | Complete (s) |
|---|---|---|---|
| opening-001 | 444 | 488 | 3.1 |
| opening-002 | 734 | 574 | 3.0 |
| opening-003 | 597 | 541 | 3.9 |

Sub-second init, ~500 ms to first token, 3–4 s to finish. For comparison, the Cactus catalogue on
the Galaxy Z Fold 3 ranged from 5 s to 36 s, and every one of those models was also *wrong*. This is
the first Android runtime that is neither slow nor false.

**The "repetition loop" was ours.** Every case came back with the complete answer emitted twice,
verbatim — the same symptom recorded in `evals/scorecard.md` as an AICore defect since July.
`MlKitPromptGenerator` streamed each chunk as an `AiTokenOrFinal.Token` and then emitted
`Final(text = fullText)` carrying the accumulated answer, while every orchestrator appended *both*
Token and Final text into one buffer. Tokens summed to the answer; Final appended the answer again.
The arithmetic was visible in the original write-up and went unread: 314 characters against a
300-character cap is one 157-character answer doubled, not a model degenerating.

Two things kept it hidden. Every other generator — iOS `FoundationModelsBridge`, desktop and wasm
LiteRT-LM — emits `Final(text = "")`, and so does `FakeTextGenerator`, so no `commonTest` could
express the violation. And ML Kit was the one backend that did not chain `withAntiRepetitionGuard`,
which would have caught it downstream. Both are fixed, and `FinalTextContractTest` now pins the
consumer half.

The lesson for the next runtime write-up is the same one this file already learned about the
provider LLM: **a fluent output that fails a length gate is not evidence about the model.** Check
the plumbing that assembled the string before attributing the shape of it to the thing that
generated it.

**Re-run after the fix, same device.** The duplication is gone; each answer is emitted once and
reaches the validators cleanly. Three raw outputs:

| Output | Validator |
|---|---|
| "This controls the center." | passed |
| "The center is the heart of chess. It's where pieces move and overall strategy matters." | passed |
| "Alright, let's get to it!" | rejected — no chess grounding |

The rejection is the validator working. The two *passes* are the finding, and they are not good news
for attaching this model: neither says anything about the move that was played. "This controls the
center" is true of a large fraction of opening moves, and "the center is the heart of chess" is a
definition, not an assessment. Compare what `DeterministicCoach` already emits for the same ply,
grounded in the recorded `MoveAssessment` — a named motif, a centipawn delta, and the stronger
alternative from `bestMoveSan`.

This is the same shape as the `gemma3-1b` result higher up this page — "5–20 s on generic waffle" —
except nano-v3 produces its waffle in three seconds and passes the grounding rule while doing it.
Speed was never the reason the Android coach is deterministic; **specificity** was, and this run does
not yet overturn it. What it does establish is that the runtime is viable, the client is correct, and
the remaining question is a prompt-and-evidence question rather than a device question. Before
attaching this to the app, run the full golden set and score it against the deterministic baseline on
grounding, not on fluency — `MoveCoachPromptBuilder` currently hands the model the assessment facts,
and whether nano-v3 uses them or talks around them is exactly what these three samples leave open.

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


---

# Game Summary, measured on its own terms — and also disqualified

Game Summary has a different contract from the coach: a deliberate button press with a spinner at
game end, not an automatic per-move panel. A wait that disqualifies the coach could be perfectly
acceptable here, so it was benchmarked separately (`AndroidSummaryBench`, `--ei
bench_summary_iterations 3`) against `gemma3-1b` — the best of the catalog — on the Fold 3, over a
24-ply game with three real turning points.

**The bar was set before the numbers were seen:** ~15 s, and truth as the hard gate, because this
surface has **no response validator at all** — any non-blank text is accepted and shipped.

| Run | Time | Result |
|---|---|---|
| 0 | **22.1 s** | *"Okay, let's analyze this game and highlight the key mistakes."* Generic waffle. **Cited none of the three turning points it was given.** |
| 1 | **23.7 s** | *"White sacrificed their Bishop (move-1)"* — **fabricated.** Move 1 was `e4`, a pawn. |
| 2 | **16.8 s** | **Answered in German.** *"Nachspielung der Partie ist sehr interessant…"* |

Every one was returned as `Success`. Nothing rejected them, because there is nothing to reject them
with — so all three would have reached the user verbatim: the waffle, the invented bishop sacrifice,
and the German.

Both gates failed, and the truth gate failed badly. A model that ignores the turning points it was
handed, invents a piece sacrifice on move 1, and switches language is not a summariser; it is a
liability on the one surface with no guard rail.

**Verdict: Cactus is removed.** Android's Move Coach and Game Summary are both deterministic.
`GameSummaryGrounding` composes the same turning points the model was given and could not use:

> Three moments decided this game. [move-5]: You played Qh5. This was a blunder. The engine
> preferred Nf3. This move lost significant material or allowed a forced mate. …

Instant, correct, cites every turning point, and in the user's language.
