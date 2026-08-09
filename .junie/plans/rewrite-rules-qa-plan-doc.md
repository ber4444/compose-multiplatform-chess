---
sessionId: session-260808-115431-1377
---

# Requirements

### Overview & Goals

**Yes — both files can go, and almost everything that was blocking their removal has now landed.**

The question was whether `docs/plans/rules-qa-on-device-tool-calling.md` and
`.junie/plans/rewrite-rules-qa-plan-doc.md` can be deleted. Nothing references either one — no
mention in `CLAUDE.md`, `README.md`, any workflow, or any other doc, unlike
`on-device-coach-rag-unification.md` and `cloud-eval-honesty-followups.md`, which `CLAUDE.md` names
explicitly. So deleting them breaks no link. And the feature they describe works: the
`Decision.FallBack` arm of `DefaultRulesQaOrchestrator.answer` runs the lookup and returns a grounded
answer, which is the fix the whole plan existed for.

Re-checking the working tree while answering, the residue I flagged has mostly been closed already:

 Item | State |
---|---|
 `MlKitPromptGenerator` reporting characters as `tokenCount` | **Done** — now `AiInferenceMetrics(0L, 0L, 0, …)` with a comment explaining the "unknown" sentinel |
 `ToolSupportSeamTest.kt` (P1-3) | **Done** — asserts `supportsTools == false` and no `AiTokenOrFinal.ToolCall` for a default `OnDeviceTextGenerator` |
 `OnDeviceRulesQaAnswererTest` mocking the concrete Cactus type | **Done** — now `mockk<OnDeviceTextGenerator>()` |
 `DefaultRulesQaAnswerer.android.kt` block body | **Done** — expression body restored |
 `README.md` Rules Q&A rows | **Done** — the `isCactusInitialized()` claim is gone |

Three things are left, and only the third is the deletion itself:

1. **`CLAUDE.md` still carries the removed gate.** Its Rules Q&A bullet ends *"since Cactus is
   initialized at launch on all Android builds (`attachMoveCoach`), `isCactusInitialized()` is true
   on release…"* — that gate no longer exists; the answerer is unconditional and the retrieval floor
   above the routing decision is what makes the feature work during the first-launch download.
   `README.md` was corrected; `CLAUDE.md` was not.
2. **Knowledge that lives only in the doc.** The measured BM25 probe and its rule — *"before touching
   the corpus or the scorer, prove retrieval is the failing step"* — and the two deliberately-open
   questions (P1-2's structured answer envelope, P0-3's ML Kit vs Cactus preference).
3. **Two remaining test gaps**, the ones the doc is currently the only record of:
   `test invalid json envelope falls back to question passages` still does not assert what its name
   says, and there is no empty-output, nested-`arguments` or prose-wrapped case.

So: close 1–3, then delete both files. Deletion is the last step, not the first.

> **Note on this file.** You already deleted `.junie/plans/` mid-session; I had to recreate it
> because the plan is read from that path when it is submitted. It is a session artifact with no
> references, so Step 3 deletes it again — nothing else needs doing about it.

### Scope

**In scope**
- `CLAUDE.md` — drop the stale gate clause; add the retrieval-proof rule and the two open questions.
- `onDeviceAi/src/androidHostTest/.../OnDeviceRulesQaAnswererTest.kt` — rename the mis-named test and
  add the three missing cases.
- Deleting `docs/plans/rules-qa-on-device-tool-calling.md` and
  `.junie/plans/rewrite-rules-qa-plan-doc.md`.

**Out of scope**
- Rewriting git history. The Gradle wrapper bump and the Move Coach prompt change stay where they
  landed — **declined by the user, not deferred.**
- Implementing P1-2 (the structured answer envelope) or answering P0-3 (which runtime Rules Q&A
  should prefer). Both become recorded open questions in `CLAUDE.md` rather than checkboxes.
- Running the six-step on-device protocol.
- Any further edit to the plan document — it is being deleted, so re-ticking its checkboxes is work
  thrown away.

### Functional Requirements

- `CLAUDE.md`'s Rules Q&A bullet describes the shipped behaviour: an unconditional answerer plus a
  retrieval floor that runs above the routing decision, with no mention of `isCactusInitialized()`.
- `CLAUDE.md` carries the retrieval-proof rule, so a future agent cannot repeat the four commits of
  corpus tuning that fixed a step which already worked.
- `CLAUDE.md` records both open questions, so deleting the doc does not silently close them.
- `OnDeviceRulesQaAnswererTest` has a test whose name matches its assertion, plus cases for empty
  model output, the nested-`arguments` envelope, and JSON wrapped in prose.
- Neither plan document exists, and no file references them.

### Non-Functional Requirements

- `:ondeviceai:check` stays green; the new cases run on the existing `androidHostTest` target.
- The `CLAUDE.md` addition stays proportional — a few lines inside the existing Rules Q&A blockquote,
  not a transplanted plan document.

# Technical Design

### Current Implementation

What the implementation session landed, verified against the working tree:

- `DefaultRulesQaOrchestrator.answer` — the `Decision.FallBack` arm calls
  `lookupTool.lookup(normalizedQuestion)` and returns
  `RulesQaResult.Success(RulesQaGrounding.composeFromPassages(passages), listOf(RuleCitation(passages.first()…)))`,
  citing exactly one passage like its sibling branch. `fallback(reason, question)` logs at warn and
  is reserved for an empty retrieval.
- `OnDeviceTextGenerator.supportsTools: Boolean get() = false`, overridden by `CactusTextGenerator`
  from `supports_tool_calling` and logged. The probe runs before `lm = instance`, and `status()`
  only reports `Available` once `lm != null`, so the first ask cannot read a stale `false`.
- `OnDeviceRulesQaAnswerer` — one `TimeSource.Monotonic` deadline shared by both turns;
  `parseLookupQuery` scans one-level-nesting candidates and reads `query` from the root or from
  `arguments`, logging both failure paths at debug.
- `RulesQaScreen` — exhaustive `when (FallbackPresentation.of(reason))` behind `LocalIsDebug`, which
  `MainActivity` and `MainViewController` feed from `FLAG_DEBUGGABLE` / `Platform.isDebugBinary`.
- `ToolSupportSeamTest` in `commonTest`; `OnDeviceRulesQaAnswererTest` on the `androidHostTest`
  target with `libs.mockk`.

### Key Decisions

**1. `CLAUDE.md` is the destination, not another `docs/plans/` file.** The guidelines file is the
repo's contract and is read on every task; a plan document is read only when someone goes looking.
The retrieval lesson already has a home there — the existing blockquote *"Retrieval is never gated on
the model…"* — so the rule and the probe belong in the same paragraph rather than in a new file.

**2. Open questions are recorded as open, not dropped.** P1-2 and P0-3 were deliberately deferred,
not forgotten. One sentence each inside the Rules Q&A bullet keeps them visible: the answer turn
still asks the model to reproduce `[id]` in prose rather than emitting a structured envelope, and
nothing yet records whether Rules Q&A should prefer ML Kit or Cactus now that the answerer is
ungated.

**3. The doc goes; the measurement survives as a rule, not as a transcript.** The BM25 table
(`draw-dead-position` 9.079 vs `draw-agreement` 5.979 for *"Game is a draw when only kings
remain?"*) is worth one line as evidence; the actionable part is the instruction to reproduce it
before touching the corpus or the scorer.

### Proposed Changes

`CLAUDE.md`, Rules Q&A bullet and the blockquote under it:

- Replace the trailing clause *"since Cactus is initialized at launch on all Android builds
  (`attachMoveCoach`), `isCactusInitialized()` is true on release, making Rules Q&A active on release
  Android as well"* with: the Android answerer is returned unconditionally, and the orchestrator runs
  the lookup on `Decision.FallBack` — so a first launch mid-download answers from the corpus instead
  of `RulesQaFallback.TEXT`.
- Add to the blockquote: **before touching the corpus or the scorer, prove retrieval is the failing
  step** — BM25 ranked the reported question's correct passage first by a 52% margin while the
  feature was dead, and four commits were spent tuning it.
- Add two open-question lines (P1-2, P0-3).

`OnDeviceRulesQaAnswererTest`:

- Rename `test invalid json envelope falls back to question passages` to name what it asserts — that
  an unparseable refinement leaves the question's own passages in play and the answer turn still
  produces a grounded answer.
- Add `empty model output`, `nested arguments envelope`, and `envelope wrapped in prose`, all through
  `mockk<OnDeviceTextGenerator>()`.

### File Structure

 File | Change |
---|---|
 `CLAUDE.md` | Rules Q&A bullet + blockquote: drop the gate, add the rule and the open questions |
 `onDeviceAi/src/androidHostTest/.../OnDeviceRulesQaAnswererTest.kt` | Rename one test; add three cases |
 `docs/plans/rules-qa-on-device-tool-calling.md` | **Deleted** |
 `.junie/plans/rewrite-rules-qa-plan-doc.md` | **Deleted** |

### Risks

- **Deleting the doc loses its history.** Mitigated by `git log` — the file's content stays
  recoverable, and the durable parts move to `CLAUDE.md`.
- **The `CLAUDE.md` bullet is already long.** Adding to it risks a paragraph nobody reads; keep each
  addition to one sentence and put the rule in the blockquote, which is visually separated.
- **Renaming a test can hide a regression** if the assertion is rewritten at the same time. Change
  the name and the assertion in the same commit and keep the original case as well, so coverage only
  grows.

# Testing

### Validation Approach

```bash
./gradlew :ondeviceai:check        # commonTest (incl. ToolSupportSeamTest) + androidHostTest
```

Doc-only edits carry no test; the check is that no file references the two deleted paths.

### Key Scenarios

- **Empty model output** — the answer turn returns `""`, so the answerer must emit the grounded
  passage text, never `RulesQaFallback`.
- **Nested `arguments` envelope** — `{"tool":"lookup_rule","arguments":{"query":"x"}}` refines the
  lookup exactly like the flat shape.
- **Envelope wrapped in prose** — leading and trailing text around the JSON still yields the query.

### Edge Cases

- An unparseable refinement must leave the question's own passages in play rather than clearing them.
- A refinement that consumes the whole deadline leaves the answer turn with `withTimeoutOrNull(0)`,
  which must fall to `grounded(passages)`.

### Test Changes

- **Changed:** rename `test invalid json envelope falls back to question passages`.
- **New:** empty model output, nested-`arguments` envelope, prose-wrapped envelope.

# Delivery Steps

###   Step 1: Move the durable Rules Q&A knowledge into CLAUDE.md
`CLAUDE.md` describes the shipped behaviour and carries everything the plan document was the sole
record of.

- Rewrite the tail of the Rules Q&A bullet: delete *"since Cactus is initialized at launch on all
  Android builds (`attachMoveCoach`), `isCactusInitialized()` is true on release…"* and state that
  `defaultRulesQaAnswerer` is unconditional on Android.
- Add, in the same bullet, that `DefaultRulesQaOrchestrator` runs `lookupTool.lookup(question)` on
  `Decision.FallBack`, so a first launch mid-download answers from the corpus — the retrieval floor
  sits **above** the routing decision, not inside the answerer.
- Extend the existing "Retrieval is never gated on the model" blockquote with the rule: prove
  retrieval is the failing step before touching the corpus or the scorer; BM25 ranked
  `draw-dead-position` first by a wide margin for the reported question while the feature was dead,
  and four commits were spent tuning a step that already worked.
- Record the two open questions: the answer turn still asks for `[id]` in prose rather than a
  structured envelope (P1-2), and no decision exists on whether Rules Q&A should prefer ML Kit or
  Cactus now that the answerer is ungated (P0-3).

###   Step 2: Close the last two test gaps in the answerer suite
`OnDeviceRulesQaAnswererTest` covers every path the plan document listed, and every test name matches
its assertion.

- Rename `test invalid json envelope falls back to question passages` to describe what it checks — an
  unparseable refinement leaves the question's passages in play and the answer turn still grounds.
- Add an **empty model output** case: the answer turn returns `""` and the result must be the grounded
  passage text, not `RulesQaFallback`.
- Add a **nested `arguments`** case: `{"tool":"lookup_rule","arguments":{"query":"…"}}` refines the
  lookup like the flat shape.
- Add a **prose-wrapped envelope** case: text before and after the JSON still yields the query.
- All four go through `mockk<OnDeviceTextGenerator>()`, keeping the suite vendor-neutral.

###   Step 3: Delete both plan documents
Neither `docs/plans/rules-qa-on-device-tool-calling.md` nor `.junie/plans/rewrite-rules-qa-plan-doc.md`
exists, and nothing references them.

- Delete `docs/plans/rules-qa-on-device-tool-calling.md`. Its P0/P1/P2 items are either shipped, moved
  to `CLAUDE.md` as open questions, or declined; `git log` keeps the history.
- Delete `.junie/plans/rewrite-rules-qa-plan-doc.md`.
- Grep for both filenames across the repo and confirm zero remaining references.
- Run `./gradlew :ondeviceai:check` once more so the deletion lands on a green tree.