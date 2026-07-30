# Coaching grounded in verified game facts

> Status: proposed, not implemented. **Revision 2.** Revision 1 covered only the on-device move
> coach. This revision folds in the game-summary redesign, memory/habits, chat scoping, and the
> grounding-substrate switch — after two rounds of on-device and deployed-server evidence showed the
> original scope was too narrow to fix the actual problem.

## Contents

- [Evidence](#evidence)
- [Root cause: content in context, and the wrong substrate](#root-cause-content-in-context-and-the-wrong-substrate)
- [The missing primitive: `MoveAssessment`](#the-missing-primitive-moveassessment)
- [Design principles](#design-principles)
- [What changes in the coach](#what-changes-in-the-coach)
- [What changes in the summary](#what-changes-in-the-summary)
- [What changes in chat](#what-changes-in-chat)
- [Memory and habits](#memory-and-habits)
- [Phases](#phases)
- [Decision gate](#decision-gate-does-the-model-beat-the-templates-at-all)
- [Alternatives considered](#alternatives-considered-and-why-this-shape-won)
- [Adjacent fixes not covered here](#adjacent-fixes-not-covered-here)
- [Deferred: precedence](#deferred-make-the-localcloud-precedence-structural)
- [Non-goals](#non-goals)

---

## Evidence

### 1. The coach cannot reason (on-device, Galaxy Z Fold3, gemma3-270m via Cactus)

Verified running on-device — Cactus logged `Context initialized successfully` and real completions,
no fallback:

| Attempt | Panel text | Verdict |
|---|---|---|
| Baseline | `Good: "Nf3 develops the knight and controls the central e5/d4 squares."` + `Good: "This is a good move that improves the position."` | Both sentences are **prompt scaffolding, verbatim** — style example #1 and the `Bad:` counter-example. Nf3 was never played. |
| After echo rejection | `The move is Pawn c7 to C6.` | Correct and grounded, but a **restatement of the user prompt's `Move:` line**. Says *what*, not *why*. |

The model stopped parroting the system prompt and started parroting the user prompt. That is
gemma3-270m's ceiling, not a code bug: 270M parameters cannot reason about chess, only pattern-match
the nearest text in context. Removing the misleading option (the examples) made it fall back to the
truthful one (the move description).

The deterministic fallback for that same move produces `Engine choice: c6. It gains space and opens
lines.` — which at least answers *why*. **The model lost to the templates.**

### 2. Chat cannot answer questions about your game (deployed server)

Asked *"what went wrong with my game"*, the deployed chat returned:

> A representative move sequence is 1. e4 e5 2. Nf3 Nc6 3. Bc4 Nf6 4. d3 `[lichess-c-955-c55]`.
> Another representative move sequence is 1. e4 e5 2. Nf3 Nc6 3. Nc3 f5 `[lichess-c-779-c46]`.

The expected answer was something like *"You attacked with your bishop too eagerly. Next time think
ahead and ensure it won't get lost next move."*

That answer is **structurally impossible** today, for two compounding reasons:

1. **The corpus is opening theory only.** PR #97's own Known-gaps section says it: *"the seeded
   corpus only carries opening classifications, so evaluative/tactical questions have nothing
   grounded to cite and legitimately fall back."* "Legitimately" is the right word — the system is
   behaving correctly given its substrate.
2. **The validator requires lexical overlap with a cited passage.** `PositionChatService` mandates a
   source id in square brackets *inside* each sentence, and the validator requires **≥2 content
   words shared with the cited passage per sentence** (so "loose paraphrase is rejected even when
   factually correct" — PR #97's own words). The desired sentence shares zero content words with any
   opening passage and cites nothing. It would be rejected as ungrounded.

So the guardrail forces the model toward restating retrieved opening lines. The output isn't a model
failure; it's the architecture working as specified, against the wrong substrate.

---

## Root cause: content in context, and the wrong substrate

The coach and rules Q&A share **the same model, the same object**:

```kotlin
// onDeviceAi/src/androidMain/.../OnDeviceTextGeneratorFactory.android.kt
cachedGenerator ?: CactusTextGenerator().also { cachedGenerator = it }
```

A `@Volatile` singleton, warm across moves. Yet rules Q&A is viable and the coach is not. The
variable is not capability — it is **whether the answer's content is in the context window**:

- **Coach** — context holds the move, tags, and style examples. The *content* ("why is this good")
  must come from the model's weights. A 270M model has none, so it emits the nearest matching text.
- **Rules Q&A** — context holds retrieved passages that *contain* the answer. The model only
  rephrases. That is a task 270M can do.

Retrieval closes the gap, and the retriever is free: `BundledRuleLookupTool` is a pure-Kotlin BM25
scan, deliberately chosen so that "every lookup [stays] local and dependency-free" without a second
model in the binary.

**But retrieval alone is not enough**, and this is the correction to revision 1: for questions about
*your game*, no corpus contains the answer. The grounding source must be **the game record itself**.
Cite `[move-14]`, not `[lichess-c-955]`. Then *"You played Bc4 and lost the bishop on the next move"*
does share content words with the cited record — the strict validator keeps working, and becomes an
asset: it forces the model to stick to what verifiably happened in your game instead of inventing
chess wisdom.

> The guardrail isn't wrong; it's **mis-specified for this task**. It was designed for
> citation-faithfulness against a reference corpus (rules, openings) and works well there. Coaching
> needs the same rigor pointed at a different substrate.

---

## The missing primitive: `MoveAssessment`

Nothing about the quality of a move is stored anywhere today. `MoveRecord` is:

```kotlin
data class MoveRecord(val uci: String, val san: String, val fenAfter: String)
```

No eval, no tags, no judgment. The coach's output is a transient string that is never persisted.
**This is why "what went wrong with my game" and "what are my bad habits" are unanswerable in
principle, not merely unimplemented.**

The proposed primitive, persisted per ply alongside the game:

| Field | Source | Notes |
|---|---|---|
| `ply`, `side`, `uci`, `san` | existing `MoveRecord` | already available |
| `cpBefore`, `cpPlayed`, `cpBest` | `ChessEngine.evaluate(fen)` | **`cpBest` is not computed today** — needs an engine-best comparison |
| `cpLoss` | `cpBest − cpPlayed` | the basis for everything evaluative |
| `class` | thresholded `cpLoss` | `ok` / `inaccuracy` / `mistake` / `blunder` |
| `motifs` | deterministic detection | `hangs-piece`, `missed-fork`, `weakens-king`, `loses-center`, … |

Two properties worth protecting:

- **Detection is code, never the model.** A motif must be checkable and unit-testable. The moment
  the model decides "this was a blunder because the bishop was loose," you get confidently-wrong
  chess commentary — the exact failure this whole effort exists to eliminate.
- **Backfill is free.** `MoveRecord.fenAfter` gives every position, `GameHistoryRepository` stores
  full PGNs, and **Stockfish already runs on-device**. Habits can be computed retroactively from
  games already played, in the background, offline, with no new capture path and no cloud.

---

## Design principles

Carried forward from the fixes that actually worked this cycle:

1. **Deterministic layer owns truth; the model owns fluency.** `MoveCoachContextExtractor`'s own
   docstring already claims this — *"The model is never asked to derive these — it only rephrases
   them"* — but the coach prompt does not implement it. That divergence is the root bug, one level
   above where earlier fixes were aimed.
2. **The model narrates; it never detects.** Applies to motifs, habits, and evaluations alike.
3. **Constraints live in code, not in the prompt.** Demonstrated three times: the `Bad:`
   counter-example was ignored (a 270M model cannot represent "don't say this") until the same rule
   moved into the validator, where it became deterministic.
4. **The deterministic floor never regresses.** Every path ends at `MoveCoachFallback`, which is
   grounded in the perft-verified move generator — the check/checkmate tags trace back to the same
   `applyWinConditions`/`checkCheck` the perft rig gates. *Plain but correct* is the worst case.

---

## What changes in the coach

### Subject: your moves, not the engine's

Today the coach explains **Black's (the engine's) move** — `MoveCoachManager`'s callback is
registered with the comment *"trigger the coach on engine moves."* Every stated goal (explaining
your mistakes, tracking your habits) is about **your** moves. This is a subject change, not a tuning
change, and it is a prerequisite for everything below.

### Tags: evaluative, not just descriptive

Current tags describe *what happened* (`capture`, `develops`, `center-control`). "Why was that a
mistake" needs *how much it cost*: `cpLoss` and its classification. `MoveCoachRequest` already
carries `evaluationBeforeCp`/`evaluationAfterCp` and `ChessEngine.evaluate(fen)` exists — the gap is
the **engine-best comparison**, which nothing computes today.

### Eval → intuition is a deterministic mapping

`−250cp` + *a piece became capturable* → `"you left the bishop undefended"`. The **detection** is
code (testable); only the **phrasing** comes from the model. This is the same split as everywhere
else in this document.

### Difficulty: two knobs, only one of them a prompt

`EngineDifficulty` (Easy/Medium/Hard/Max) should shape advice the way a human coach would — a junior
player and a strong player do not get the same feedback:

- **Concept gate (code).** Which motifs are worth surfacing at each level: beginner gets hanging
  pieces and development; advanced gets pawn structure, prophylaxis, initiative. It also gates the
  **threshold** — a 100cp slip is worth flagging on Max and is noise on Easy.
- **Register (prompt).** Junior-coach tone vs. peer tone.

Keeping the gate in code makes *"does Easy mode avoid jargon"* a unit test rather than a vibe.

---

## What changes in the summary

The game summary is the natural home for the evaluative layer, because it can see the whole game:

- Rank plies by `cpLoss`, surface the top 2–3 turning points, and explain each in human terms via
  the deterministic mapping above.
- Factor engine difficulty through the same concept gate.
- Cite the assessment records (`[move-14]`), so the existing validation discipline applies.

This is the surface where "what went wrong with my game" should actually be answered — proactively,
without the user needing to know what to ask.

---

## What changes in chat

### Scope: what chat is actually for

Chat's justification is **unbounded query space** — questions a fixed per-move line or end-of-game
summary structurally cannot enumerate:

- **Counterfactuals** — *"why not Bxf7?"* The strongest case. You cannot pre-generate a summary for
  every move *not* played. Also largely deterministic: evaluate the alternative with Stockfish, diff
  the cp, extract the refutation line, and let the model narrate.
- **Follow-ups** — *"why is that bad for my king?"* Depth on demand after a summary.
- **Position-level planning** — *"what's my plan here?"* Not tied to a single move.
- **Habit queries** — *"what do I keep getting wrong?"*, *"give me a fork position to practice."*
  These depend on the memory layer below.

### Split out the Hint button

*"What's the best move?"* is likely the single most common query, and it **needs no LLM at all** —
Stockfish already computes it locally, instantly, offline. That should be a **Hint button**, not a
cloud round-trip. Routing it through chat is slower, costs tokens, requires network, and is less
discoverable.

### Grounding substrate switch

For any question about the user's own game, retrieve **assessment records**, not corpus passages,
and cite them as `[move-N]`. This is the fix for PR #97's Known-gap 1 and is what makes the desired
"you attacked with your bishop too eagerly" answer *representable* under the existing validator.

### Retrieval query from deterministic features

PR #97's Known-gap 1 has a second sub-cause: *"the query embedding includes the user's message,"* so
*"Is this good for Black?"* on a Catalan position retrieved four unrelated D00 passages. Build the
retrieval query from **deterministic features** (position, phase, motifs) rather than raw free text,
and use the user's message only to select *which* records/passages are relevant.

---

## Memory and habits

The scalability path, and the reason `MoveAssessment` must be persisted rather than transient:

| Layer | Owner | Example |
|---|---|---|
| Assessment (per ply) | code | `cpLoss=-280, class=blunder, motif=hangs-piece-to-fork` |
| Habit (aggregate) | code | "hangs pieces to knight forks in 40% of games" |
| Narration | model | "You keep losing pieces to forks — watch knights landing on c7/f7." |
| Practice suggestion | retrieval | fork-motif positions, keyed by the detected habit |

The model must never *detect* a habit — detection has to be checkable, exactly like the move tags
are. Aggregation is a deterministic pass over stored assessments, and it can run over historical
games via the backfill path described above.

---

## Phases

**Phase 1 — `MoveAssessment` + subject switch.** Persist per-ply assessments; compute `cpBest`;
implement deterministic motif detection; switch the coach subject to the player's moves. No model
changes. Unblocks everything else. *This is the load-bearing phase.*

**Phase 2 — Evaluative summary.** Rank by `cpLoss`, cp→concept mapping, difficulty concept gate,
cite `[move-N]`. Answers "what went wrong with my game" proactively.

**Phase 3 — Grounded per-move line.** Make the per-move line a retrieval turn over assessments +
tags, reusing the two-turn structured-output pattern proven in `StructuredOutputRulesQaAnswerer`.
Prompt becomes a *rewrite* instruction, not a *reason about chess* instruction. Validator and
deterministic floor unchanged.

**Phase 4 — Chat re-scope.** Assessment-record retrieval for game questions; counterfactual support
via Stockfish; Hint button split out; deterministic-feature query construction.

**Phase 5 — Habits + practice.** Aggregate assessments across games; backfill from stored PGNs;
narrate aggregates; suggest practice positions.

**Phase 6 (optional) — Offline chat.** Swap in a bundled BM25 retriever so chat works offline.
`DefaultPositionChat` currently emits `FALLBACK_NO_CHAT_MODEL` for on-device routes, but that is a
**policy** choice (`AiRoutePolicies.positionChat` is cloud-only), not an architectural limit — the
server composer already takes passages as a parameter and does not care where they came from.

> **Correction to revision 1.** Rev 1 proposed "bundle a subset of the ECO opening corpus" as an
> early phase. That does **not** fix the evidence above: it is still opening theory, so it would
> make opening questions work offline while leaving "what went wrong with my game" exactly as
> broken. Corpus bundling is deferred to Phase 6 and reframed as an *offline-chat* enabler, not a
> coaching fix. The coaching fix is the substrate switch.

### Where retrieval stops working

Even with a corpus, retrieval quality splits by game phase:

- **Opening** — strong. Moves have crisp lexical identity (`1.e4 c6` → Caro-Kann). `TAG_OPENING`
  (`fullmoveNumber <= 10`) already tells us when we are here.
- **Middlegame** — weak. *"Why is this knight move good in this position"* is not a lexical lookup;
  no corpus contains your position.

Assessment records do not have this problem — they are *about* your position by construction. This
is another argument for the substrate switch over corpus expansion.

---

## Decision gate: does the model beat the templates at all?

`evals/scorecard.md` scores routes on grounding violation / retry / fallback / length, and the
**`cactus-android` row is empty** ("manual — hardware numbers not collected"). Fill it and compare
against the populated `local-template` and `deterministic-fallback` rows.

Ship Phase 3 only if the grounded route beats `local-template` on grounding violation and fallback
rate. Judgement quality still needs owner hand-review — the scorecard header says as much — but a
route that cannot beat a template on automated metrics is not worth 200 MB.

**Kill criteria.** If the grounded route does not beat the templates, drop the model from the
per-move line and keep the deterministic one-liner: instant, offline, no battery cost, always
correct. Cactus stays in the build for the RAG paths (rules Q&A, offline chat), so the download
still earns its place — it just stops pretending to be a chess commentator.

**Honest bar-setting.** The deterministic floor is *plausible*, not insightful: "opens lines" is a
stretch for c6 (it really controls d5/b5), and when no tags match it degrades to `"Engine choice:
<move>. It is the engine's top choice for this position."` — as generic as the model's restatement.
`MoveCoachFallback` also picks only **one** headline and **one** reason, first-match-wins, so a move
that is simultaneously a capture, a check, and a center grab renders lossily. Synthesizing several
verified facts into varied prose is the *specific* value a model could add — and is a rewriting
task, which small models can do.

---

## Alternatives considered, and why this shape won

| Option | Verdict |
|---|---|
| **Leave it.** Correct-but-plain beats confidently-wrong. | Viable, zero work. Rejected only because the summary/memory goals need the assessment layer anyway. |
| **Reject restatement → prefer the fallback.** One validator rule, no download. | Good, and cheap. Subsumed: Phase 1's evaluative tags make the fallback strictly better, so this becomes the natural floor. |
| **Bigger model** (gemma3-1b, Qwen2.5-0.5B). | Deferred, not rejected. Costs download + cold start, and does not fix grounding — a bigger model still cannot know *your* game. Revisit only after Phase 1 makes facts available. |
| **Re-scope 270M to pure rewriting.** | Adopted, as Phase 3. |
| **Drop the generative coach; push all explanation to chat ("4+1").** | Rejected on interaction grounds — see Non-goals. |
| **Deterministic-only on Android; remove the model.** | Retained explicitly as the kill-criteria outcome, not the default. |

---

## Adjacent fixes not covered here

Real, small, and tracked nowhere else — they should not be lost with this document:

1. **Citation rendering.** `[lichess-c-955-c55]` must never reach a casual player's screen.
   Citations are required for validation — fine — but should be validated internally and rendered as
   a source affordance (or stripped) for display. Smallest pure win.
2. **Server-side output sanitization** (PR #97 Known-gap 2). The thinking model leaks reasoning into
   content (`]..." -> Yes, used [lichess-d-3-d00].`). Same bug family as three fixes already
   shipped — `<end_of_turn>` token stripping, few-shot echo rejection, and `90887fd`
   ("strip `<think>` CoT blocks from LiteRT-LM output") — but all three live in the **on-device**
   path while this leak is in the **server** path (`LlmChatComposer` / `PositionChatValidator`).
   Worth prioritising: a leaked fragment gets *rejected*, so a good answer is lost to a formatting
   artifact — part of the observed 2-of-5 failure rate.
3. **PR #97 Known-gap 3** (intermittent 502s after machine restart) is Fly.io infrastructure and
   unrelated to this plan.

---

## Non-goals

- **Replacing the per-move line with chat.** They are different interaction models: the coach is
  **push** (fires automatically, zero user effort); chat is **pull** (the user must know what to
  ask, and a casual player often does not). Keep an automatic line; make chat the depth surface.
- **A second on-device model.** Embeddings were rejected once on size grounds and that reasoning
  holds. If BM25 is not good enough, the answer is a better substrate or the cloud route — not a
  second runtime.
- **Removing Cactus.** "Drop the 270M" would mean dropping it *from the coach only*.
  `DefaultRulesQaAnswerer.android.kt` uses the same singleton for rules Q&A, where grounding does
  the heavy lifting and the model earns its keep.

## Risks

- **270M may produce mush even when grounded.** Rules Q&A suggests otherwise, but that is a crisper
  task. The decision gate exists for this reason.
- **Assessment cost.** `cpBest` needs an extra engine call per ply. Bound the depth, compute
  asynchronously, or restrict to end-of-game analysis rather than live per-move.
- **Storage growth.** One assessment per ply per game, capped like `GameHistoryRepository`'s
  200-game limit.
- **Retrieval collisions.** Style example #1 is about Nf3, a very common move; the echo detector
  will reject a legitimate Nf3 explanation that matches it. Low harm (falls back to templates), but
  swapping the examples to rarer moves would reduce it.

## Deferred: make the local/cloud precedence structural

Not part of the coaching redesign, but it constrains it, and it should not be lost.

`AiRoutePolicyDecider.decide()` prefers a local route whenever a device model is available — that
branch is evaluated **before** `RunCloud`, and it consults neither `allowCloud` nor `privacyClass`:

```kotlin
context.isDeviceModelAvailable -> Decision.RunOnDevice   // main, pre-#106
vendorRoute != null            -> Decision.Route(...)    // #106, same precedence
```

This predates PR #106 (which faithfully preserved it). The cloud-only surfaces work today only
because they report `isDeviceModelAvailable = false` — two boolean literals in
`KtorStreamingChatClient` and `KtorOpeningExplainerClient`. Those are now commented and pinned by a
decider test, and a separate fix stops a *cloud-capable vendor* being selected for a policy that
forbids cloud (`fix/vendor-route-cloud-guard`). Both are hardening; neither changes precedence.

The structural fix is to have `decide()` consult `resolveVendorRoute` **only when the policy permits
local routing**, so the cloud-only guarantee comes from the type system rather than from two
literals a future contributor could reasonably "tidy" to `true`. That is deliberately deferred:

- It is a **semantic change to shipped behaviour**, not a bug fix — cloud-preferring policies would
  stop preferring a local model when one exists.
- The existing 60-context sweep only asserts that `LOCAL_ONLY` never reaches cloud. It does not pin
  the device-first ordering in either direction, so it would not catch the change. The sweep needs
  extending *first*.
- It interacts with this plan: once coaching is grounded in `MoveAssessment` records rather than a
  corpus, which surfaces want local-first vs. cloud-first may change. Settling precedence before
  that is premature.

Do it as its own PR, sweep-extension first.

## Dependencies

- **PR #106 (hybrid inference vendor adoption)** replaces the `OnDeviceTextGeneratorFactory` seam
  with `VendorRouteExecutor` / `VendorRoute`, and `StructuredOutputRulesQaAnswerer` already takes an
  `executor` on that branch. The shared-warm-singleton property this plan relies on still holds
  there (the Android executor keeps its own `cachedGenerator`), but **every factory reference in
  this document must be re-pointed at the executor seam if #106 lands first.**
