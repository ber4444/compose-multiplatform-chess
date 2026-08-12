# Android on-device coach: every Cactus model measured (2026-08-11)

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
- It does not measure iOS. Foundation Models is a different runtime, is
  system-provided at no download cost, and has not been benchmarked here.
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
  every row after the model changed. It now reads `CactusTextGenerator.DEFAULT_MODEL`.
- The runner called `warmup()`, which returns as soon as init *starts* (B18), so
  generation began before the model had loaded and every row reported
  `fallbackTriggered: true, "no local model"`. It now calls `awaitWarmup()`, which
  `CLAUDE.md` already required of any caller reporting a terminal state.
