# On-device AI: next three steps

Written for a fresh agent. Everything below is on branch `game-summary-on-device-measurement`
(unmerged as of writing) and follows `docs/benchmarks/on-device-ai/game-summary-2026-08.md` — read
that first, it explains why the numbers in the earlier version of this story were wrong.

## Where things stand

| Surface | Android | iOS | Attached in the app? |
|---|---|---|---|
| Move Coach | ML Kit / AICore (`nano-v3`) available | Foundation Models available | **No**, both — decided by measurement |
| Game Summary | AICore 12/12 on the 2026-08 fixtures | Foundation Models 12/12 | Android **no** (`ATTACH_GAME_SUMMARY = false`); **iOS yes, in production** |
| Rules Q&A | AICore, returned unconditionally | Foundation Models native `Tool` | **Both yes, in production** |

Two facts drive everything here:

1. **Game Summary has no response validator at all.** Any non-blank text is accepted and shown.
2. **iOS already ships a model on both Game Summary and Rules Q&A**, so "add a validator" is not
   only about turning Android on — it retroactively protects what users get today.

---

## Task 1 — Write `GameSummaryResponseValidator`

**Why first:** it is the gate for Task 2, and it improves a surface that is already live on iOS.

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
3. **`validateMoveAttribution`** — every SAN token in the text must be either a played move or the
   `bestMoveSan` of some turning point, and a `bestMoveSan` must not be described as one the player
   played. *(First run: "opting for Qc4 and cxd4 respectively", where cxd4 was the engine's choice.)*
   The coach's `validateBetterMoveAttribution` is the model for this.
4. **`validateClassFidelity`** — a ply's `MoveClass` must not be restated as a different severity.
   *(AICore called a MISTAKE "a significant inaccuracy".)* Compare against a small synonym table;
   keep it in the validator, not in `ConceptVocabulary`, which belongs to `:evals`.
5. **`validateVoice`** — reject first person singular ("I played", "my mistake"). *(Foundation
   Models writes the summary as the player: "I made two significant mistakes in this game.")* See the
   caveat under Task 2 before relying on this one.
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

### 1d. Tests

`GameSummaryResponseValidatorTest` in `commonTest`, one case per rule, plus **replay the real
outputs**: `docs/benchmarks/on-device-ai/game-summary-2026-08.md` quotes the accepted and the flawed
ones, and `build/bench/summary-*.jsonl` from a run has the rest. A rule that does not fire on the
output that motivated it is not implemented. Add the accepted AICore summaries as cases that must
stay `Valid` — that is the half that catches an over-strict rule.

---

## Task 2 — Turn AICore on for Game Summary

**Gate: Task 1 has landed, and a 50-game fixture set agrees with the 12-game one.**

```bash
python3 tools/generate_summary_fixtures.py --games 50 --out build/bench/summary-fixtures.json
```

Then run both platforms per the "Reproducing" section of the benchmark doc. Acceptance, on 50 games:

- **≥ 90% accepted by the new validator** (the 12-game run was 12/12 pre-validator).
- **≥ 90% citing exactly the code-chosen plies** (11/12 on Android; iOS will fail this today).
- **0 invented `[move-N]`.** This one is not a percentage — a wrong board jump is a hard fail.
- **p95 latency under the 45 s `withTimeoutOrNull`,** with the median near the 12 s already measured.

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
and nothing covers this. Build the third, in the same shape as the other two — shared fixtures in
`app/src/commonMain/.../bench/`, per-platform runner, one JSONL row per case carrying **both** the
model's answer and the deterministic column.

- **Fixtures:** questions against `onDeviceAi/src/commonMain/resources/rulesCorpus/passages.tsv`
  (30 passages). Cover each passage at least once, plus paraphrases and a few out-of-corpus questions
  where the honest answer is "the corpus does not say".
- **Baseline column:** `RulesQaGrounding`'s cited-passage answer — the retrieval floor. That is the
  text to beat, exactly as `DeterministicCoach` and `GameSummaryGrounding` are on the other two.
- **What to measure:** did retrieval return the right passage (BM25 is already known good — see the
  fence in CLAUDE.md, do not re-tune it before proving retrieval is the failing step); did the
  model's phrasing preserve the passage's meaning; did it keep the `[passage-id]`; latency; and how
  often `RulesQaGrounding` has to rescue it.
- **The open question this answers:** whether the model turn earns its place at all, given that the
  retrieval floor already produces a correct cited answer.

**Also fix the integration asymmetry while here:** `defaultRulesQaAnswerer` returns an answerer on
Android *unconditionally, with no availability probe*, while the coach and summary now go through
`probeAvailableLocalVendors()` in `MainActivity.attachOnDeviceAi()`. On a device with no AICore
feature the Rules answerer is constructed anyway and fails at generation, where the orchestrator's
`groundedOrFallback` catches it. It works, but it is the only surface that decides availability by
failing.

---

## Leftovers worth doing at some point

- **`DeviceRunScorer` has no Game Summary support.** `./gradlew :evals:scoreDeviceRun` ingests the
  coach's `results.jsonl` only, so summary runs are currently scored by ad-hoc scripts — the exact
  "second scorer written next to the data" problem the `:evals` KDoc exists to prevent. Add a summary
  mode that cross-checks the validator verdict from Task 1 against the device's own.
- **`evals/scorecard.md`'s `aicore-nano-fast` row is stale** (0.0% grounded, 100% reject). It measured
  the doubled-`Final` bug *and* the placeholder-fixture bug *and* `preference = FAST`, which
  `FEATURE_NOT_FOUND`s on a Pixel 10. Its own note says "re-measure before trusting this row".
  Re-measure it as `aicore-nano-full` or delete it; leaving a 0.0% row is worse than no row.
- **Summary fixtures aren't staged into Android assets** the way the coach's golden set is, so a run
  needs a manual `run-as` push. One Gradle copy task would remove a step from the repro.
- **`mapToIntuition`'s 10–20% branch still says "positional or material"** — the same kind of hedge
  that was removed from the >20% branch, kept because it is at least accurate.
- **The branch is unmerged.** Its three fixes improve what iOS ships today regardless of any attach
  decision, so it is worth a PR on its own.

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
