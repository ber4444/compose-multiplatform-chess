# On-device AI: next three steps

Written for a fresh agent. Follows `docs/benchmarks/on-device-ai/game-summary-2026-08.md` — read that
first, it explains why the numbers in the earlier version of this story were wrong.

**Merged as #138.** This file was written on the unmerged `game-summary-on-device-measurement`
branch; that branch has landed, so Task 1 below is shipped code rather than a proposal, and the
"where things stand" table describes `main`.

## Where things stand

| Surface | Android | iOS | Attached in the app? |
|---|---|---|---|
| Move Coach | ML Kit / AICore (`nano-v3`) available | Foundation Models available | **No**, both — decided by measurement |
| Game Summary | AICore 12/12 on the 2026-08 fixtures | Foundation Models 12/12 | Android **no** (`ATTACH_GAME_SUMMARY = false`); **iOS yes, in production** |
| Rules Q&A | AICore, returned unconditionally | Foundation Models native `Tool` | **Both yes, in production** |

Two facts drive everything here:

1. **Game Summary now has a response validator** (`GameSummaryResponseValidator`, #138) — it did not
   when this file was written, and every rule below was designed against that gap. Any non-blank text
   used to be accepted and shown. A rejection now routes to `GameSummaryGrounding`.
2. **iOS already ships a model on both Game Summary and Rules Q&A**, so the validator was not only
   about turning Android on — it retroactively protects what users get today, and on the 50-game
   corpus it rejects 42 of 50 Foundation Models summaries.

---

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

## Task 2 — Turn AICore on for Game Summary

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

Acceptance for flipping the flag:

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

If it passes, set `ATTACH_GAME_SUMMARY = true` in `MainActivity`. **Leave `ATTACH_MOVE_COACH`
alone** — that decision is settled the other way and the constants were split precisely so this
change cannot drag it along.

### The iOS half is a prompt problem, not a validator problem

Do **not** ship `validateVoice` as the fix for iOS. Foundation Models writes "I" because the turning
points say *"You played b4"* and it re-narrates from that "you"; a validator would take iOS from
12/12 to roughly 3/12 accepted and show the deterministic text after a 1.4 s wait. Fix the prompt
first — an explicit voice instruction in `SYSTEM_PROMPT`, and/or rendering the turning points in a
person the model will not invert — then re-measure, then keep the rule as the backstop. The PGN
result in the benchmark doc shows how much leverage prompt shape has on this runtime.

---

## Task 3 — Benchmark Rules Q&A

**The only surface with a model live in production on both phones, and the only one never measured.**

There is no bench for it: `AndroidBenchRunner` covers the coach, `AndroidSummaryBench` the summary,
and nothing covers this. Build the third in the same shape as the other two — that shape is the
point, because it is what keeps one scorer over both columns.

### 3a. Fixtures

`app/src/commonMain/.../bench/RulesQaFixtures.kt`, mirroring `SummaryFixtures`: a `load(text)` that
parses a JSON fixture file and a `jsonLine(...)` that both platforms call, so neither runner invents
its own schema.

Cases, hand-written into `tools/rules_qa_fixtures.json` (no generator — the corpus is 30 passages, so
the set is small and should be authored deliberately):

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
| `citedPassageIds` | every `[id]` the model emitted |
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
- **Latency**, against the `rulesQaOffline` 20 s `completeMs` budget. Two model turns on Android
  (tool call, then phrasing) and no timeout of its own on iOS.

### 3d. The decision this run makes

**Does the model turn earn its place at all?** The retrieval floor already produces a correct, cited
answer without it. If the model mostly rephrases what `RulesQaGrounding` would have said, the surface
should follow the Move Coach and go deterministic — which would also settle the second open question
in CLAUDE.md's Rules Q&A note (whether to prefer ML Kit now that Cactus is gone). If it reliably turns
a passage into a better-targeted answer, it stays, and the numbers say so.

### 3e. Fix the integration asymmetry while here

`defaultRulesQaAnswerer` returns an answerer on Android *unconditionally, with no availability probe*,
while the coach and summary now go through `probeAvailableLocalVendors()` in
`MainActivity.attachOnDeviceAi()`. On a device with no AICore feature the answerer is constructed
anyway and fails at generation, where `DefaultRulesQaOrchestrator.groundedOrFallback` catches it. It
works, but it is the only surface that decides availability by failing, and it means the Rules screen
is the one place a user can reach a model this app has not established is there.

---

## Leftovers worth doing at some point

- **`DeviceRunScorer` has no Game Summary support, and it now has a stated prerequisite.**
  `./gradlew :evals:scoreDeviceRun` ingests the coach's `results.jsonl` only, so summary runs are
  still scored ad hoc — the exact "second scorer written next to the data" problem the `:evals` KDoc
  exists to prevent. #139 fixed only the *failure mode*: pointing the scorer at a summary JSONL used
  to die with `MissingFieldException` and a stack trace ending in the deserializer, and it now
  refuses by name and says why. **The mode itself is still open**, and the blocker is a bench-schema
  change, not a scorer change: `GameSummaryResponseValidator.validate` derives its turning points
  from `request.moveHistory`, and a summary row records `pgn`/`plies`/`playerBlunders` and no
  per-ply assessments, so the request the device validated against cannot be rebuilt. Add the
  assessed move history to the row in `AndroidSummaryBench`/`IosSummaryBench` first; the scorer half
  is small once the data is there. **Do not "fix" this by scoring the two rules that survive the
  gap** — a partial number under a heading that looks like the coach column is worse than no column.
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
