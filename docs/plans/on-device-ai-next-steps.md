# On-device AI: next three steps

Written for a fresh agent. Follows `docs/benchmarks/on-device-ai/game-summary-2026-08.md` — read that
first, it explains why the numbers in the earlier version of this story were wrong.

## Where things stand

- **Game Summary** retains its validated on-device implementation and debug benchmarks. Commit
  `07159d04` removed the in-app summary and put the Opening Explainer in the game-over popup.
- **Rules Q&A** uses the app's availability probe before choosing a model route. Both mobile
  benchmarks are wired, and the emulator run established the current BM25 baseline.
- **Move Coach** uses the deterministic explanation on both phones.

## Task 1 — Write `GameSummaryResponseValidator` — **DONE**

**Why first:** it is the gate for Task 2, and it improves a surface that is already live on iOS.

**Shipped:** `validateCitationSet`, `validateCitationCoverage`, `validateMoveAttribution`,
`validateVoice`, `validatePieceType`. Measured over the 50-game corpus, it accepts **37 of 43**
AICore summaries and rejects **42 of 50** Foundation Models ones.

**Two rules from the list below were built, measured, and removed.** Both passed their own unit
tests and produced **zero true positives against 43 real summaries**:

- **`validateClassFidelity`** — no way to see negation, so it rejected *"While these aren't huge
  blunders…"* and *"[move-17] with Qc2 wasn't a blunder"*; and its per-tag text segmentation ran to
  the next `[move-N]`, so the last citation swallowed the closing paragraph and matched *"focusing on
  those moments of inaccuracy"*. Severity paraphrase is a judgement about a clause, and matching
  words in a window is not that.
- **The best-move-described-as-played branch** of `validateMoveAttribution` — it required the
  engine's move to appear *after* "instead of", so the standard counterfactual *"opting for e4
  instead of Nd2 would have been a stronger choice"* was rejected on six summaries.

Between them they took acceptance on known-good Android output down to **58%**, which would have
meant one summary in two costing a 12 s wait and then showing the composed text anyway. The lesson
is in `GameSummaryValidatorFieldTest`: a rule is not implemented until it has been run against
output nobody wanted it to reject.

### 1a. Make the turning points structured

`GameSummaryPromptBuilder.extractTurningPoints` returns `List<String>` — pre-rendered sentences. A
validator needs the facts, not the prose. Introduce:

```kotlin
internal data class TurningPoint(
    val ply: Int,
    val san: String,
    val moveClass: MoveClass,
    val bestMoveSan: String?,
)
```

`extractTurningPoints` builds `List<TurningPoint>`; a separate `render(TurningPoint): String` produces
today's sentence. `GameSummaryGrounding.composeFor` and the prompt builder both go through `render`,
so the text the user sees when the model is rejected is unchanged. Keep both `internal` —
`:onDeviceAi` is published to the React Native consumer and these are not its API.

### 1b. The rules

New `onDeviceAi/src/commonMain/.../GameSummaryResponseValidator.kt`, mirroring
`MoveCoachResponseValidator`'s shape exactly: `object` with
`fun validate(rawText: String, request: GameSummaryRequest): Result`, `Result.Valid(text)` /
`Result.Invalid(reason)`, and one `internal fun` per rule returning `String?` so each is unit-testable
alone. Reuse `splitSentences` and `containsMoveToken` from the coach validator rather than
reimplementing them — both already handle the chess-text traps (a `.` inside a move number, `e4`
matching inside `e4xd5`).

Rules, in the order they should be checked, each with the observed failure that motivates it:

1. **`validateCitationSet`** — every `[move-N]` in the text must be one of the request's turning-point
   plies. A tag that is not a turning point is worse than no tag: B16 turns `[move-N]` into a tappable
   board jump, so an invented tag navigates the user to the wrong ply. *(Foundation Models, first run:
   put a `[move-N]` on the engine's preferred move.)*
2. **`validateCitationCoverage`** — reject when the text cites fewer than all of the turning points
   **and** claims a count that disagrees ("I made two significant mistakes" listing 2 of 3), or cites
   none at all. Foundation Models produced no citation in 9 of 12 answers despite the system prompt
   requiring them; AICore covered 1 of 3 turning points once.
3. **`validateMoveAttribution`** — a move token in the text must be a played move or the
   `bestMoveSan` of some turning point, and a causal claim about the engine's move is invention
   because the facts never say *why* it was better. The coach's `validateBetterMoveAttribution` is
   the model for this. **Only unambiguous move tokens are checked** — piece moves, captures, castles,
   promotions — because the bare-pawn form is indistinguishable from prose naming a square, and
   rejected a correct summary for *"Sacrificing the Bishop on g6"*. The cost is that an invented pawn
   move goes unflagged; the alternative rejected true sentences. The "described as played by the
   player" half of this rule was built and removed — see the Task 1 header.
4. ~~**`validateClassFidelity`**~~ — **built and removed.** It was meant to catch AICore calling a
   MISTAKE "a significant inaccuracy". A window of words around a tag cannot tell that apart from a
   negation or a closing sentence, and it never once fired on a real misdescription. Do not
   reintroduce it without a way to read the clause, not the window.
5. **`validateVoice`** — reject first person singular ("I played", "my mistake"). *(Foundation
   Models writes the summary as the player: "I made two significant mistakes in this game.")* See the
   caveat under Task 2 before relying on this one. **"we"/"our" are allowed** — AICore writes "we
   could have played more precisely", which is a coach speaking *with* the player, not as them — and
   so is "me", so that "let me break this down" is not a rejection.
6. **`validatePieceType`** — reuse the coach's rule verbatim if it can be lifted; it catches
   "a blunder that weakened your pawn structure" only when a piece noun is wrong, which it is not
   here, so **do not claim it covers structural decoration**. Structural claims ("weakened your pawn
   structure", "opened the position") are not mechanically checkable from a `MoveAssessment` and
   should be left to the prompt.

**Be strict.** The fallback is the better writer — the same reasoning `MoveCoachResponseValidator`'s
`CONCEPT_VOCAB` KDoc records. A rejection lands on `GameSummaryGrounding`, which is what ships today,
so a false rejection costs the user nothing but the wait.

### 1c. Wire it

In `DefaultGameSummaryOrchestrator.runGeneration`, after `trimIncompleteSummaryTail`: an `Invalid`
result becomes `fallback(request, FallbackReason.Validation)`. Note the existing comment there
("we don't have a complex validation step like MoveCoach") becomes wrong — update it.

**No retry.** `DefaultAiCoachOrchestratorTest` pins `generateCount == 1` for the coach and this
surface must match: a validation failure emits the deterministic summary immediately.

### 1d. Tests — two halves, and the second one is the one that matters

`GameSummaryResponseValidatorTest` (`commonTest`) pins the shape of each rule: one case that must be
rejected, and for the rules that replaced a broken one, the phrasing that must **not** be.

`GameSummaryValidatorFieldTest` (`desktopTest`) is the half that catches an over-strict rule, and the
half that was missing the first time. It replays
`desktopTest/resources/game-summary-field-corpus.jsonl` — 43 AICore and 20 Foundation Models
summaries from the 50-game run, each carrying the turning points the device computed — and asserts:

- Android acceptance stays at or above **85%**;
- **the only reason it ever rejects an AICore summary is incomplete coverage** — any other rejection
  fails the build and names the summary, because on this corpus every other reason has been a bug;
- at least 60% of the Foundation Models rows are rejected;
- no accepted summary anywhere cites a ply that is not a turning point.

Regenerate the corpus from a fresh run rather than editing it: a corpus edited to make a rule pass
measures the rule against itself.

---

## Task 2 — Measure Game Summary with the validator

**Gate: Task 1 has landed, and the 50-game run repeats with the validator in place.**

The 50-game run has already been done *without* a validator (2026-08-15,
`tools/generate_summary_fixtures.py --games 50`), and it is the baseline to beat:

| | Android AICore | iOS Foundation Models |
|---|---|---|
| Succeeded | 43/43 (7 rows lost to `ErrorCode 30`, a contiguous block — harness, not model) | 50/50 |
| Cites exactly the code-chosen plies | **37/43 (86%)** | 18/50 |
| Cites a subset, no invented tag | 6/43 | 2/50 |
| **Invented a `[move-N]`** | **0** | **2/50** |
| No citation at all | 0 | **28/50** |
| First person ("I played") | 0 | **26/50** |
| Truncated | 0 | 0 |
| Latency median / p95 | 11.4 s / 13.2 s | 1.4 s / 2.2 s |

Acceptance gates for a future rollout:

- **0 invented `[move-N]`.** Not a percentage — a wrong tag is a wrong board jump, and this is the
  one failure the user cannot detect. Android is already at 0/43; iOS is at 2/50, which is on its
  own sufficient reason not to attach iOS without the validator.
- **≥ 90% accepted by the new validator.** The 14% of Android rows citing only a subset will be
  rejected by `validateCitationCoverage` and fall back — correct behaviour, but it means roughly one
  summary in seven costs an 11 s wait and then shows the deterministic text. If that ratio holds,
  consider whether the prompt can be made to enumerate all three before shipping.
- **p95 latency under the 45 s `withTimeoutOrNull`** — 13.2 s measured, comfortable.

Re-run both platforms per the "Reproducing" section of the benchmark doc. **Watch for `ErrorCode 30`
blocks**: 7 contiguous rows were lost to one here even with `keepBenchInForeground()`, most likely a
USB/system dialog stealing focus mid-run. A contiguous block of sub-second fallbacks is always the
harness; scattered failures are worth investigating.

The Android attach flag was removed with the Game Summary UI. Keep the rerun as benchmark evidence
for any future decision to restore the surface. Both runners now emit the assessed history and
the validator sample needed by the [summary scorer](#score-game-summary-runs).

### The iOS half is a prompt problem, not a validator problem

Do **not** ship `validateVoice` as the fix for iOS. Foundation Models writes "I" because the turning
points say *"You played b4"* and it re-narrates from that "you"; a validator would take iOS from
12/12 to roughly 3/12 accepted and show the deterministic text after a 1.4 s wait. Fix the prompt
first — an explicit voice instruction in `SYSTEM_PROMPT`, and/or rendering the turning points in a
person the model will not invert — then re-measure, then keep the rule as the backstop. The PGN
result in the benchmark doc shows how much leverage prompt shape has on this runtime.

---

## Task 3 — Benchmark Rules Q&A

The Android and iOS runners share the fixture loader and JSONL schema. Run the same fixtures on
physical devices with the model available, then compare the raw wording with the cited BM25 floor.

### 3a. Fixtures

`app/src/commonMain/.../bench/RulesQaFixtures.kt` loads fixtures, measures each turn, and emits the
same JSONL schema for both runners.

The 37 cases in `tools/rules_qa_fixtures.json` cover 30 passages, four confusable paraphrases,
and three out-of-corpus questions.

- **One per passage, phrased as a player would ask it** — not as the passage is worded. Copying the
  passage's own wording measures nothing: BM25 will always find it, and the model will always echo.
- **Paraphrases and near-misses** for the passages that are easy to confuse. `draw-dead-position`
  versus `draw-agreement` is the known pair — *"Game is a draw when only kings remain?"* is the
  question from the original bug report, and BM25 already ranks it correctly (9.079 vs 5.979).
- **Out-of-corpus questions** where the only honest answer is that the rules corpus does not cover it
  ("what is the Sicilian Defence?"). This is the case most likely to produce invention, and the one
  no existing test covers.

### 3b. Columns

One JSONL row per case, carrying **all three** texts so nothing has to be re-derived later:

| column | why |
|---|---|
| `question`, `expectedPassageId` | the case |
| `retrievedPassageId`, `retrievalScore` | did BM25 find the right passage — the step below the model |
| `groundedAnswer` | `RulesQaGrounding`'s cited-passage answer: **the floor to beat** |
| `modelAnswer`, `kind`, `fallbackReason` | what the model said, and why not if it didn't |
| `modelPassageIds`, `citedPassageIds` | passages supplied to the model and tags the model actually emitted |
| `answer` | final text after validation and fallback |
| `elapsedMs`, `modelIdentifier`, `deviceModel`, `osVersion` | same as the other two benches |

`fallbackReason` is not optional — see the `SummaryFixtures.jsonLine` comment for why a bare `kind`
is unreadable.

### 3c. What to score

- **Retrieval accuracy** — `retrievedPassageId == expectedPassageId`. **Establish this first and
  separately.** CLAUDE.md carries a fence here: while the feature was dead on device, four commits
  were spent tuning BM25 for a question it was already ranking correctly. If retrieval is at 100%,
  every remaining failure is above it.
- **Citation fidelity** — did the model keep the `[passage-id]`, and is it the retrieved one? A model
  citing a passage it wasn't given is the Rules-Q&A equivalent of the invented `[move-N]` that Task 2
  treats as a hard fail.
- **Faithfulness** — does the answer state anything the passage does not? Score by hand on the first
  pass; the out-of-corpus cases are where this will show.
- **Rescue rate** — how often `RulesQaGrounding` has to replace the model's wording. A high rate is
  not a failure, it is the floor doing its job, but it bounds what the model turn is worth.
- **Latency**, against the `rulesQaOffline` 20 s `completeMs` budget. Android measures both model turns and
  iOS measures the native tool and model response within the same orchestrator budget.

### 3d. The decision this run makes

When BM25 finds the right passage, `RulesQaGrounding` can already supply a cited answer. Compare
whether model-assisted lookup recovers misses and whether the model phrases correct answers more
directly. Check those gains against invented citations, unfaithful answers, and time spent before
falling back.

### 3e. Availability probe

`AppRoot` supplies `probeAvailableLocalVendors()` to the Rules Q&A route decision. Android's
answerer is constructed on all devices so the corpus path can still answer when the probe
returns no vendors.

### Run the Rules Q&A benchmark

Install a debug build on an unlocked AICore phone. The default fixture set is staged into the APK.

```bash
mkdir -p build/bench
adb shell am start -S -W -n io.github.ber4444.chess/com.example.myapplication.MainActivity --ei bench_rules_qa_iterations 1
# Wait for the activity to finish before pulling the rows.
adb exec-out run-as io.github.ber4444.chess cat files/bench/rules_qa.jsonl > build/bench/rules-android.jsonl
./gradlew :evals:scoreDeviceRun -Psurface=rules-qa -Pfile=../build/bench/rules-android.jsonl
```

On an Apple Intelligence iPhone with a debug build, place `tools/rules_qa_fixtures.json` in the app
container at `Documents/golden/rules_qa_fixtures.json`. Launch it with the benchmark environment,
keep the app in the foreground, then copy `Documents/rules_qa_results.jsonl` to
`build/bench/rules-ios.jsonl`.

```bash
xcrun devicectl device process launch --device "$CHESS_BENCH_IPHONE" --terminate-existing \
  --environment-variables '{"BENCHMARK_MODE":"1","BENCHMARK_SUITE":"rules-qa"}' io.github.ber4444.chess
./gradlew :evals:scoreDeviceRun -Psurface=rules-qa -Pfile=../build/bench/rules-ios.jsonl
```

Compare the BM25 top hit with the fixture label separately from model citation fidelity. Review
the raw model answers against their supplied passages, especially when the fixture label is null.
On November 6, 2026, the Android emulator matched **22/34** in-corpus labels and returned an
unrelated passage for **1/3** out-of-corpus questions. Its 37 rows contain no model output.

## Score Game Summary runs

Both runners record the PGN, side, difficulty, and assessed move history. They also retain the raw
completed model output, the text sent to the validator, and the device verdict. The scorer uses
the shipped validator and checks raw citations separately so a tag in a discarded tail still
counts toward the zero-invention gate.

```bash
./gradlew :evals:scoreDeviceRun -Psurface=summary -Pfile=../build/bench/summary-android.jsonl
```

Older rows need a rerun with the updated harness. See [Game Summary benchmark](../benchmarks/on-device-ai/game-summary-2026-08.md#reproducing)
for fixture generation and output paths. The November 6 emulator run scored all 50 deterministic
summaries and recorded zero completed model outputs.

## Leftovers worth doing at some point

- ~~**`evals/scorecard.md`'s `aicore-nano-fast` row is stale**~~ — **done in #139.** Replaced,
  together with the n=1 `foundation-models-ios` row, by the three columns of the 2026-08-15
  hundred-case run (`mlkit-aicore-full`, `foundation-models-ios`, `deterministic-coach`). The
  baseline column is new: the scorecard had never carried the thing the models are competing with,
  which made every on-device row unreadable on its own.
- **Summary fixtures aren't staged into Android assets** the way the coach's golden set is, so a run
  needs a manual `run-as` push. One Gradle copy task would remove a step from the repro.
- **`mapToIntuition`'s 10–20% branch still says "positional or material"** — the same kind of hedge
  that was removed from the >20% branch, kept because it is at least accurate.

## Fences — do not undo these

- **No PGN in the summary prompt.** It cost 8/12 Foundation Models rejections and was the only source
  of Android's invented narrative. The turning points carry every fact the summary may state.
- **`noRepeatNgramSize` stays ≥ 8 on this surface.** B15's default of 4 cuts parallel lists
  mid-sentence; `GameSummaryGroundingTest` pins this against a real truncated output.
- **`[move-N]` survives `CitationSanitizer`.** Widening that regex deletes B16's board jumps.
- **Do not attach the Move Coach** without first covering motif attribution and file/diagonal claims
  in `MoveCoachResponseValidator`. That rule is the gate, and it is documented on
  `ATTACH_MOVE_COACH`.
- **Check the harness before believing a model verdict.** Every "the model is bad at X" finding on
  this project so far has been ours — see the three at the top of the benchmark doc.
