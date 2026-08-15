# Game Summary on device — 2026-08-15

Measured because the Move Coach verdict does not transfer: a summary is a deliberate button press
with a spinner at game end, not an automatic panel, so a wait that disqualifies the coach may be
fine here. What does not change is the bar for truth — **this surface has no response validator at
all**, so whatever the model writes reaches the user.

12 real games, played engine-vs-engine with the player's side deliberately weakened and assessed
ply by ply (`tools/generate_summary_fixtures.py`). Both platforms read the same fixtures through
`SummaryFixtures`, so both models were handed the same games and the same turning points.

## Three bugs were measured before either model was

The first pass reported 7/12 for AICore and 4/12 for Foundation Models, and concluded that
Foundation Models "refuses two games in three and truncates every success". **All three failure
modes were ours.** They are worth reading in order, because each one looked exactly like a model
verdict:

1. **`[ErrorCode 30] Background usage is blocked`** — AICore refuses to generate unless the app is
   in the foreground, and `adb shell am start` on a locked or sleeping device produces a full JSONL
   of sub-second fallbacks that measure nothing. It cost 3 rows of the first run and **all 12** of
   the second. `svc power stayon usb` does not prevent it: the keyguard, not the screen timeout, is
   what stops the activity being resumed. `MainActivity.keepBenchInForeground()` now holds
   `FLAG_KEEP_SCREEN_ON` + `setShowWhenLocked` for the run.
   The two rows that first-pass reported as *"inference exceeded latency budget"* at ~46 s were the
   cold start immediately after the three blocked rows — not a slow model, the same bug.
2. **`noRepeatNgramSize = 4`** — B15's anti-repetition guard, whose default is tuned for the coach's
   single sentence. A summary of three turning points is a **parallel list**, and a parallel list
   repeats its connectives by construction, so the guard cut real answers at the start of the second
   *"so this was another small inaccuracy"*. It truncated 4 of 4 Foundation Models successes and 1
   of 7 AICore ones. `GameSummaryPromptBuilder` now asks for 8; `trimIncompleteSummaryTail` drops a
   ragged tail if anything else ever cuts one.
3. **The raw PGN in the prompt** — the single largest effect, in two directions at once.
   Foundation Models rejected 8 of 12 prompts with *"An unsupported language or locale was used"*,
   the **same 8 across two runs**, in 15–20 ms: an input guardrail, not a model deliberating. Enough
   of the prompt was chess notation that Apple's language check did not read it as English. And on
   Android the PGN was where the invention came from — every unsourced flourish in the run
   ("in the endgame", "contributed to the loss" on a game that has no result, "led to the
   checkmate") was the model narrating movetext it had been handed and told not to use.
   The turning points already carry every fact the summary is allowed to state, so the prompt now
   opens with one factual line (side, ply count) and the turning points.

Removing the PGN took Foundation Models from 4/12 to 12/12 and Android's unsourced-narrative rows
from 6/12 to **0/12**.

## The numbers, once all three were fixed

| | Android `mlkit-aicore-full` | iOS `FoundationModels` |
|---|---|---|
| Succeeded | **12/12** | **12/12** |
| Latency, median | 12.1 s | **1.4 s** |
| Truncated mid-sentence | 0/12 | 0/12 |
| Cites exactly the code-chosen turning points | **11/12** | 3/12 |
| Carries no `[move-N]` citation at all | 0/12 | **9/12** |
| Unsourced narrative claims | 0/12 | 0/12 |
| Voice | coach, second person | **first person, as the player** |
| Length, median | 541 chars | 294 chars |

AICore's summaries are the best on-device output measured on this project:

> *"Okay, let's break down the game. Specifically, [move-5] with b4 was a bit of an inaccuracy, and
> [move-25] with Bh5+ proved to be a significant mistake, giving up a good advantage. Finally,
> [move-29] with Rg1 was a blunder that further weakened our position."*

Foundation Models is eight times faster and gets the facts right, but writes the summary **as the
player**, which is the wrong product:

> *"I made two significant mistakes in this game. First, I played b4 instead of e4, which was
> slightly inaccurate."*

It also drops the `[move-N]` citations in 9 of 12 answers despite the system prompt requiring them,
and that tag is not decoration — B16 turns it into a tappable board jump, so an uncited summary
silently loses the affordance. Where it does cite, the tags are correct.

Remaining errors, both platforms, one row each: AICore's game-012 covered only 1 of 3 turning
points; Foundation Models' game-001 announced "two significant mistakes" and listed 2 of 3.

## Does it beat the deterministic composer?

`GameSummaryGrounding` cites 3 of 3 turning points, every time, instantly, and cannot fail or
invent. That is still the bar, and it is a high one.

**Android is now a genuine trade rather than a loss**: 12 s and a coach's voice against instant and
robotic, with citations intact either way. What argues against attaching it is not quality any more
— it is that this surface has **no response validator**, so the residual decoration ("was a blunder
that weakened your pawn structure", about a knight move) has nothing between it and the user. That
is a rule to write, not a model to replace: the coach's validator already covers piece-type and
file claims, and none of that machinery is wired here.

**iOS is not ready as-is**, for a reason that has nothing to do with speed: the first-person voice
and the missing citations are both prompt-level defects on a runtime that ignores instruction
detail the way `nano-v3` does not.

Recorded as: `MainActivity.ATTACH_ON_DEVICE_AI` stays `false`, with the per-surface reasoning on the
constant. This is the first surface where an on-device model has come close enough that flipping it
is a product decision rather than a quality one.

## Reproducing

```bash
python3 tools/generate_summary_fixtures.py --games 12 --out build/bench/summary-fixtures.json
```

Android — the screen must be unlocked; `keepBenchInForeground` handles the rest:

```bash
adb push build/bench/summary-fixtures.json /data/local/tmp/ && adb shell run-as io.github.ber4444.chess cp /data/local/tmp/summary-fixtures.json files/golden/
```

```bash
adb shell am start -W -n io.github.ber4444.chess/com.example.myapplication.MainActivity --ei bench_summary_iterations 1
```

```bash
adb exec-out run-as io.github.ber4444.chess cat files/bench/summary.jsonl > build/bench/summary-android.jsonl
```

iOS — needs the real app under `BENCHMARK_MODE`, since the Foundation Models bridge is registered
from `iOSApp.swift` and the Kotlin/Native test runner sees no vendors:

```bash
SIMCTL_CHILD_BENCHMARK_MODE=1 SIMCTL_CHILD_BENCHMARK_SUITE=summary xcrun simctl launch booted io.github.ber4444.chess
```
