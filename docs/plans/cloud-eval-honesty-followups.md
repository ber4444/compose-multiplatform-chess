# Follow-ups — cloud eval honesty and the two cloud surfaces

Originating scope: everything found while fixing the cloud retrieval path (commits `9b32f12`…
`4947cc5`) that was deliberately not fixed at the time, plus two items from the original branch
review.

**P0-1, P0-2, P1-1, P1-2, P1-3, P2-1 and P2-2 closed on 2026-08-05** and are no longer work items —
their findings are in [What the measurements found](#what-the-measurements-found), which is short on
purpose: the detail now lives where it can't rot, in the code and tests that enforce it.

**R-1 was reviewed on 2026-08-05 and the verdict is *no* — the cloud output is not worth showing a
user.** Four named causes, none visible to any automated check here; that section is now the most
load-bearing part of this document and its derived work is unstarted. R-2 remains open.

The through-line, unchanged: **an aggregate that cannot name its own cause is not evidence** — and
R-1 adds its converse: *every* aggregate here can now name its cause, and the product is still bad.
Faithfulness was never the thing worth measuring.

---

## Open work

### R-1 Prose quality hand-review — **DONE 2026-08-05. Verdict: no, this is not worth showing.**

Owner read the ten-opening sample from `tools/collect_cloud_samples.sh` against the deployment.
**The answer to "does it tell the player something they could not see?" is no**, for three specific
reasons — none of them a matter of taste, and *every one of them passes every automated check in
this repo*. This is the "grounded and useless" outcome the plan predicted, now observed:

1. **The opening's name is printed twice.** `"B20 — Sicilian Defense: Sicilian Defence: Black
   answers the king's pawn with c5…"`. `TemplateComposer` prefixes `passage.title` (which is
   `"$eco — $name"`), and `EcoNarrator.characterize()`'s text *also* opens with the opening's name,
   because it was written to be a standalone claim. Two components each doing the right thing alone.
2. **Passage two is ECO taxonomy, not chess.** For a Sicilian, the second cited passage is
   `"B00 — King's Pawn Game: Alekhine, Pirc, Modern and other king's-pawn replies…"` — a list of
   *sibling opening names* that means nothing to a player at this board. Two causes compound: the
   book tier fills its remaining slots with **shorter, less specific prefix matches** (1.e4 still
   matches when 1.e4 c5 already did), and `EcoNarrator` characterizes wide ECO *ranges* in
   taxonomic language.
3. **Position Chat is a copy of the Opening Explainer's first sentence.** Both composers quote the
   *top passage's first sentence*, and `LlmChatComposer`'s prompt explicitly instructs "Reuse the
   sources' own wording; do not paraphrase loosely" (added to satisfy the validator's token-overlap
   rule). So the chat answer to *"Why is this popular for Black?"* is verbatim the explainer's
   opening line. The second surface adds nothing.

**Underneath all three: the corpus has ~500 distinct claims spread over 3,803 rows.** The
characterization is keyed by *ECO code*, so B20's four retrieved passages —
`Sicilian Defense`, `King David's Opening`, `Myers Attack, with h4` — carry the **identical** first
sentence, differing only in the name appended after it. Retrieval is working perfectly and returning
four copies of one claim. `EcoNarratorTest` enforces that every ECO *has* a characterization and
that it fits the 125-char window; nothing asks whether two rows say different things.

**This is the single most important finding in this document**, because it is invisible to
everything else: retrieval is 8/8 correct, the validator accepts 99/100, grounding scores clean, and
the product is still not worth shipping.

#### R-1 second pass, after `LineNarrator` shipped — **still no. Now with a criterion.**

Owner re-read the post-reseed samples. Repetitions persist (`"Ruy Lopez: Ruy Lopez"`,
`"Queen's Gambit: Queen's Gambit and Slav"`) and **most line descriptions still fail**. The verdict
came with the thing this document has been missing all along — a **test for usefulness**, which no
validator, scorer or eval row here has ever encoded:

> **Does it give a practical plan, a trade-off, or a decision rule?** Naming a move or restating
> geometry the player can already see does not count.

Graded against it, with the owner's own examples:

| Tier | Examples | Verdict |
|---|---|---|
| **Useful** | "prepare d4", "attack White's centre later", "gives up castling", "accepts less space for a sound structure", "Black has a blocked light bishop" | a plan, a trade-off, or a consequence |
| **Low-value** | "a bishop goes to c4", "knights develop early", "the queen comes out on move 2", "a flank pawn moves", "this line is defined by X" | restates the move list |
| **Actively harmful** | generic parent-category prose attached to a specific opening; duplicate labels; sibling-opening lists | worse than silence — it misinforms about *this* line |

**This inverts two of my design decisions, and the branch table above should be re-read through it.**

- **Most of `LineNarrator`'s branches are the *low-value* row verbatim.** "brings the bishop to c4"
  (31.1%), "the queen comes out on move 2" (2.2%), "pushes h4 on the wing" (3.5%), "defined by X"
  (16.9%) are the owner's examples of what does **not** count. Only the king-move branch ("gives up
  castling", 0.7%) and arguably the fianchetto/capture branches clear the bar. **Roughly 55% of rows
  are producing text the reviewer classes as low-value, and the earlier "24.6% says something usable"
  was my grading, not a user's.**
- **`EcoNarrator`'s family claim is *actively harmful* on a specific line.** It is by construction
  "generic parent-category prose attached to a specific opening". I kept it as the lead for each
  ECO's base line and as the second sentence everywhere — that decision needs revisiting, not just
  the branch above it. A B20 sub-line that opens with the generic Sicilian family sentence is in the
  bottom tier of this table.
- **The three-tier test belongs in the harness, or it will be re-derived by hand every time.** It
  cannot be a keyword rule (that is P0-1's mistake again) but it *can* be a review protocol with a
  fixed rubric, applied to a fixed sample, recorded per run.

Derived work (not yet started, ordered by ratio of user-visible improvement to effort):

- [ ] **PR #121 merge gate — keep seeded `LineNarrator` claims literally true.** A review found
      that a later king move could be described as newly losing castling rights, and castling after
      `...exd4` as happening before the centre opened. Both are false claims seeded as source text.
      Restrict every SAN-only branch to its literal move fact unless the context is mechanically
      replayed; add regressions for those corpus shapes. This must land, deploy and reseed before
      treating the corpus as current.
- [ ] **PR #121 merge gate — fix template-chat rendering.** Its title-plus-passage composition
      duplicates opening names, its second passage repeats parent-opening taxonomy, its chunker
      can glue words at chunk boundaries, and its 125-character quote path can still cut a word.
      Render the top passage sentence without a repeated title or second passage, preserve every
      whitespace character across chunks, and apply word-boundary truncation before emitting it.
      Add regression tests for each shape, then deploy before the next sample review.
- [ ] **Stop printing the name twice** — confirmed still live after the reseed (`"Ruy Lopez: Ruy
      Lopez"`), and the R-1 second pass puts duplicate labels in the *actively harmful* tier. Either drop the title prefix in `TemplateComposer` or stop
      leading `EcoNarrator` strings with the opening name. One of the two, not both — the composers
      quote the first sentence, so it must still stand alone.
- [ ] **Don't cite a shorter prefix when a longer one matched** — sibling-opening lists
      (`"Queen's Gambit: Queen's Gambit and Slav"`) are also *actively harmful*, not merely noise. `PostgresPassageRepository` should
      prefer sibling lines *within* the resolved ECO over the parent-family row, or the composers
      should stop quoting passage two when its `moves` is a strict prefix of passage one's.
- [ ] **Make the two surfaces say different things.** Chat should answer the *question* from the
      passage rather than restate the passage; the "reuse the wording" instruction exists to satisfy
      `PositionChatValidator`'s ≥2-content-word overlap, so relaxing one requires re-checking the
      other. See the fence: fix the measurement, not the threshold — but here the *instruction* is
      the thing distorting the output.
- [ ] **Product-blocking: route Position Chat by answer shape before composing.** The post-reseed
      sample shows related facts that do not answer the question: Scandinavian "is recapturing with
      the queen bad?", Queen's Gambit "is the pawn free?", and Ruy Lopez "should Black worry?"
      need a direct answer first, then the condition/trade-off and usual response. Define a typed
      question shape and a minimum response contract: `WHY` requires a benefit plus trade-off;
      `PIECE_PURPOSE` requires target/role/follow-up; `YES_NO` requires explicit first-clause
      polarity plus condition; `PAWN_FREE` requires cost and likely continuation; `COMPARISON`
      requires both openings/structures and their resulting trade-off; `PROBLEM_PIECE` requires the
      piece, restriction and standard remedy; `PLAN` requires a future pawn break, manoeuvre,
      target or condition. The composer must be unable to emit a generic opening sentence when the
      selected shape requires one of those fields. Test each shape with a positive answer and an
      otherwise-grounded non-answer that the contract rejects.
- [ ] **Product-blocking: make chat retrieval specificity-aware.** After resolving the longest
      prefix, retrieve the exact line and a line-specific plan/idea passage; include a same-family
      contrast only when it directly answers the question. Suppress strict-prefix ancestors and
      broad sibling taxonomy by default (`B00` after the Sicilian, `C44` after the Italian, and the
      Zukertort family list); allow them only for an explicit family-level question. This strengthens
      the shorter-prefix retrieval item above with the query-aware policy and exact bad cases.
- [ ] **Make the corpus unit a sourced player insight, not an ECO-row characterization.** Store
      line-specific plans, trade-offs and non-obvious consequences with a move-prefix and
      provenance; use board-replayed facts only as supporting evidence or ranking signals. A
      `LineNarrator` that sees SAN can make rows distinct, but cannot author why the Caro-Kann keeps
      the c8 bishop free or why Black accepts less space. The composer must select at least one
      non-obvious insight rather than make visible board geometry the whole answer.
- [ ] **Make player insights answerable by question shape.** In addition to the line prefix and
      provenance, curate fields such as `choice_reason`, `central_tradeoff`, `white_plan`,
      `black_plan`, `piece_purpose`, `common_misconception`, `comparison_to`, and
      `typical_response`. A French "problem piece" request must retrieve an explicit c8-bishop
      fact, not infer an answer from generic `...c5` counterplay prose. Board-description sentences
      ("the bishop goes to c4", "the queen goes to a5") are metadata only unless paired with a
      sourced implication, risk or plan.
- [x] **Give the corpus more than one claim per ECO** — *in progress, see below.*

#### Per-line claims (`LineNarrator`) — the R-1 fix being implemented

**Design.** `EcoNarrator` stays as the *family* claim; a new `LineNarrator` adds a **line-specific
first sentence** derived from the row's own move sequence, so the four B20 rows stop opening with
the same words. Placement is the whole point: both composers quote the **first sentence**, so a
distinguishing sentence anywhere else would change nothing.

**The hard constraint is honesty.** This is seed-time code with no board and no engine, so it may
state only what SAN itself proves — an early king move forfeits castling, a bishop went to the long
diagonal, a capture released the central tension, the queen came out before the minor pieces. It
must **not** evaluate ("Black is comfortable", "White has the initiative"): the project's rule is
that code detects and only the model narrates, and an invented evaluation in the corpus would be
cited as a source by both composers and validated as grounded. That is the failure mode this whole
document exists to catch, and seeding it into the corpus would make it permanent.

**The base line of each ECO keeps the family claim first.** For `1.e4 c5` the family sentence *is*
the best sentence; only deeper rows (`2.Ke2`, `2.h4`) lead with what makes them different. Requires
grouping rows by ECO at seed time to find the shortest line.

Acceptance criteria — **all four met in code, `LineNarratorTest` (6 tests, green):**

- [x] Distinct rows within one ECO have **distinct first sentences**, asserted over the real corpus
      — the check `EcoNarratorTest` never made. Also asserted corpus-wide for every ECO with ≥4 rows.
- [x] Every generated sentence fits the **125-char** window `TemplateComposer.sentence` truncates at.
- [x] No generated sentence contains an evaluation, pinned by a forbidden-vocabulary test
      (`better`, `advantage`, `initiative`, …) so a future "helpful" addition trips it.
- [ ] **The `Sicilian Defense: Sicilian Defence:` duplication is NOT fixed by this change** — that is
      the separate title-prefix item above, still open. This change only stops the four B20 rows
      reading identically.

**Honest assessment of the result.** Sample of what the composers will now quote:

```
B20 — Sicilian Defense                       → [family claim, unchanged: the base line]
B20 — Sicilian Defense: Amazon Attack        → White brings the queen out on move 2 with Qg4, ahead of the minor pieces.
B20 — Sicilian Defense: Bowdler Attack       → White brings the bishop to c4 at move 2, the move this line is named for.
C60 — Ruy Lopez: Alapin Gambit               → The line resolves the tension on move 5: Black captures with dxc6.
```

**This is a partial fix and should not be recorded as more.** The strong branches (king move,
fianchetto, early queen, capture) say something a player can use; the fallback branch — most rows —
*describes the defining move* rather than explaining an idea, so those passages are now distinct but
still thin. Getting past that needs either a board at seed time (to tell developing from retreating,
or to detect a real gambit) or an actual source of opening ideas. **R-1's verdict is not overturned
by this commit**; re-review after reseeding before claiming otherwise.

**Not live until reseeded** (`:server:seed` against the deployment's database).

**Not live until reseeded.** `SeedMain` writes this text into Postgres; changing the generator
changes nothing on the deployment until `:server:seed` runs against it. Same fence as the
`eco`/`moves` columns — see CLAUDE.md § Cloud retrieval.

**How to collect the twenty responses** (this step is mechanical; the reading is not):

```bash
tools/collect_cloud_samples.sh > /tmp/cloud-samples.txt   # ~2 min, 10 openings × 2 surfaces
```

It hits both cloud surfaces for ten real openings and prints every response in full. Two things are
expected and are *not* what you are judging: each chat turn takes ~11 s and arrives all at once
(that is P1-2's provider batching), and the answers are cited with `[lichess-…]` ids (the app strips
those via `CitationSanitizer`; the raw wire format keeps them).

The judgement is the part no tooling reaches: **does each response tell a player something they
could not see on the board?** A grounded, fluent, correctly-retrieved answer that only restates the
position passes every automated check in this repo and still fails R-1.

### R-2 Chat monitoring: `composerId` and time-to-first-token — **human only**

P1-2 pre-answered part of this from live calls: **TTFT 10.9 s**, `composerId = llm-chat-v1`, so the
provider path is live rather than silently downgraded. What remains needs the app and the logs.

- [ ] Watch `composerId` and TTFT across ~10 real chat turns **in the app**.
- [ ] Confirm `chat-provider-failed` appears in `fly logs` when the template path fires.
- [ ] **Closes the last open question in P1-2:** look for `chat-provider-oneshot` vs
      `chat-provider-single-delta` in `fly logs`. One line decides whether the provider answers
      non-SSE or streams a single large delta. Everything else has been ruled out in code.
- [ ] Confirm which model the deployment runs — `COACH_LLM_MODEL` is a Fly secret, and the eval
      numbers are gemini-3.6-flash. If they differ, **no eval row describes the deployment.**
- [ ] Decide whether ~11 s with nothing on screen needs UI. A fallback that takes 20 s to produce
      boilerplate is worse UX than an error; so, arguably, is a success that looks identical to a
      hang.

### Found while collecting the R-1 samples — 2026-08-05

Ten openings × both surfaces against the deployment. These are **observations, not judgements** —
R-1 is still unreviewed — but each is a defect rather than a matter of taste, and two of them
partly answer R-2 already.

- [ ] **Apply `ModelOutputCleaner` to the chat route** (finding 1) — the one user-visible bug here.
- [ ] **Re-check P2-1 on the chat surface and on accepted turns** (finding 2).
- [ ] **Word-boundary the template fallback truncation** and decide whether a downgrade should be
      visible to the user at all (finding 3, feeds R-2's last checkbox).
- [ ] **Reseed after deploying `LineNarrator`**, then re-run `tools/collect_cloud_samples.sh` and
      re-review — R-1's verdict stands until that happens.
- [ ] `tools/collect_cloud_samples.sh` **duplicates `CitationSanitizer`'s rules in Python**. Keep the
      two in step, or the review sample stops matching what the app shows.
- [ ] Make `tools/collect_cloud_samples.sh` print each chat turn's terminal `composerId` and whether
      it ended in `done` or `fallback`. Without it, a sample cannot distinguish a provider answer
      from `TemplateChatComposer`, which obscured the duplicate-label and glued-word failures.

1. **Raw model deliberation reached the wire, and the validator passed it.** The Scandinavian chat
   turn streamed:

   ```
   * Let's make sure the ID is exactly ``.
    * Let's write:
    The provided sources do not mention if recapturing with the queen is bad, but they note that
    in the Scandinavian Defense, Black invites a broad white centre… [lichess-b-84-
   ```

   Terminated with `done`, `composerId = llm-chat-v1` — so `PositionChatValidator` **accepted** it.
   `LlmChatComposer` only strips `<think>` blocks and code fences; this model deliberates in plain
   prose (`Let's …`), which no fence catches. `:server`'s one-shot route has `ModelOutputCleaner`
   for exactly this and the chat route does not use it. **This is user-visible on the deployment
   right now.**
2. **The same answer ends mid-citation** (`[lichess-b-84-`, no closing bracket) and was still
   accepted. Note what this does to P2-1: that item was closed as *unsupported* on 0/2 opening-route
   rejections, and here is a mid-citation truncation in the **chat** route — on an *accepted* turn,
   where no rejection counter would ever see it. P2-1's counter watches the wrong surface and only
   the rejected half. n=1, so this reopens the question rather than settling it.
3. **Half the chat turns were not the model.** Of ten: five `llm-chat-v1`, three `done` with
   `template-chat-v1`, two `fallback`. That is R-2's silent-downgrade concern with a number on it —
   and the two fallbacks are cut mid-word (`"Italian G"`, `"French Defense is c"`) because the
   template truncates at `MAX_OUTPUT_CHARS` without a word boundary. A user cannot tell any of these
   apart from a real answer.

### From the first deploy + reseed — 2026-08-05

1. **`LineNarrator`'s weak branches are the majority, measured.** Branch distribution over all 3,803
   corpus rows:

   | Branch | Share |
   |---|---|
   | piece move (*"brings the knight to f3"*) | 31.1% |
   | generic (*"defined by X at move N"*) | 16.9% |
   | central pawn (*"claims central space with d4"*) | 11.6% |
   | fianchetto / capture / flank push / early queen / castles / check / king move | 24.6% |
   | base line — keeps the family claim | 14.1% |
   | too short for a line sentence | 1.9% |

   **Three rows in five get a sentence that describes a move rather than explaining an idea.** The
   cause is structural: `LineNarrator` sees a list of SAN strings and never a board, so `Nf3` can
   only become "a knight went to f3".

   - [ ] **Target the *useful* tier, not distinctness.** The R-1 second pass reclassifies most of
         these branches as low-value: distinct-but-descriptive was the wrong goal. A replacement
         sentence has to carry a plan ("prepare d4"), a trade-off ("accepts less space for a sound
         structure") or a consequence ("gives up castling").
   - [ ] **Fix by replaying the line, not by adding SAN patterns.** `:server` is JVM-only and
         `:chess-core` has a JVM target, so seeding can replay each line and derive *facts*:
         material balance (real gambit detection), developed minor pieces per side, castling status,
         central pawn count and tension. All computed — still inside "code detects, the model
         narrates". Cost: `:chess-core` generates SAN but does not parse it, so this needs a
         SAN→move matcher over the existing move generator. **Own PR**, so the next R-1 re-review can
         attribute the change.
   - **Do not extend the SAN heuristics further.** Each new pattern buys a few percent and moves
         closer to inventing meaning the moves do not prove.

2. **The seed opens one Postgres connection per row, and that is the likely root of both problems.**
   `PostgresPassageRepository.upsert` takes a fresh connection for each of the 3,803 rows — a cost
   `OpeningRetrievalGroundingTest` already worked around with a batched insert, noting it is "fine
   for the nightly seed job". It is not fine: the full run exceeds **28 minutes** in-container, and
   two runs died with `java.io.EOFException` in `doAuthentication` against the same
   `…-db.flycast:5432?sslmode=disable` URL the app uses successfully. Sustained connection churn
   through the Fly proxy is the best remaining explanation.

   Ruled out for the EOF: missing secret (present in the SSH session), cold database (3/3 checks
   passing), IPv6 preference (tested), and **stale credentials** — the app machine was restarted and
   came back healthy, which disproves the "surviving on pre-existing connections" theory.

   - [ ] **Batch the seed upserts** (one prepared statement, `addBatch`/`executeBatch` on a single
         connection), reusing what the test already does. Fixes the runtime and probably the EOF.
   - [ ] If the EOF survives batching, correlate against `fly logs -a compose-chess-opening-coach-db`
         at the failure timestamp.

3. **A killed seed leaves a partly-updated corpus, silently.** `SeedMain` upserts row by row and
   prints only a final `Seeded N` line, so an interrupted run has already rewritten an unknown
   fraction — and nothing distinguishes that state from "never ran". This cost real time: a run
   killed by a 110 s command timeout was read as a total failure when it had in fact written the new
   text, and a later run reported exit 143 that was **also** just a timeout (28 min), not a crash.
   - [ ] Log progress every N rows, and print the generator version, so a partial run is visible
         from the outside.
   - [ ] Add a completeness probe. Note the obvious one — query the API and look for old-shape text
         — **cannot work**: a base line legitimately leads with the family claim, and the response
         does not say how deep a row's move prefix is, so "not reseeded" and "base line" are
         indistinguishable from outside. It has to count rows in the database.

   Coverage of the 2026-08-05 reseed was ultimately established by *ordering*, not by sampling luck:
   `loadCorpus` walks the directory sorted (`a`, `b`, `c`, `concepts.md`, `d`, `e`), and `e.tsv` rows
   carry the new text — so everything before them was written.

### Opened while closing these

None blocking; each says what to measure before changing anything.

1. **The eval fixture passage is a generic backbone** — the same opening-principles prose for every
   case, bar one prepended concept line. It cannot distinguish a model that understands the position
   from one reciting principles, and it caps what any grounding scorer can prove. `CorpusBookIndex`
   can now supply a case's *real* corpus passage offline, so seeding the fixture from it is cheap.
2. **`max_tokens` truncation is invisible.** Billed output measured `max=2044` against a 2048
   ceiling: calls are being clipped. `finish_reason=length` is available and unrecorded, so a
   truncated answer that still validates is a silent quality loss.
3. **`billed output tokens` is collected but not gated.** A model change that triples per-call cost
   surfaces only if a human reads the scorecard note.
4. **`deployed-cloud` is scored on concept coverage only** — it cannot know which passage was
   retrieved. Returning resolved source ids in `OpeningExplainResponse` would let it be anchored like
   the local rows.
5. **`ProviderCostBudget.expectedOutputTokens` is one global constant** shared by both composers but
   calibrated from opening-route data. Chat answers are shorter; measure separately if chat spend
   matters.

---

## What the measurements found

Closed items, kept only for the conclusions that would otherwise have to be rediscovered. Each is
enforced by code and tests referenced below; the reasoning is in those files' comments.

**P0-2 — the outputs were paraphrases, not off-concept.** 2026-08-05, gemini-3.6-flash, 100 opening
cases: **99 accepted by the production validator, 1% fallback, 0 provider errors** — while the old
scorer called 91% of them ungrounded. A random sample of 12 gave 12 correct paraphrases and zero
answers about the wrong position; the misses were 70× `center`, 56× `development`, the two concepts
with the most natural paraphrases. This settled P0-1 as a **pure scorer problem, no prompt
component**, and inverted the plan's premise: the template's apparent advantage was the measurement.
*Two caveats travel with these numbers:* the fixture passage is generic (so this is not a quality
result — see R-1), and the harness's model need not be the deployment's (so a row is unquotable
without its `model=`).

**P0-1 — grounding is now two claims, and both can fail.** Verbatim containment is gone. Coverage is
paraphrase-tolerant via an auditable synonym table (`ConceptVocabulary`); anchoring requires ≥2
shared content words with the case's own passage, and is skipped only for `deployed-cloud`, which
cannot know what was retrieved. `EvalScorerTest` proves the pair the plan demanded: a grounded
paraphrase passes, a fluent off-position answer fails — plus verbatim quotation still passes, so the
template did not regress.

**P1-1 — cloud grounding gates CI.** New AUTOMATED `book-retrieval` row: all 100 golden cases must
resolve to their ECO from the move prefix, offline (no key, no Docker, no network). `CorpusBookIndex`
is a second implementation of the SQL book tier and is pinned to it by a cross-check in
`OpeningRetrievalGroundingTest` — an unpinned duplicate would go green while production regressed.
`BookRetrievalEvalTest` shows the row failing. Three adjacent bugs fixed: the scorecard note was
computed for every row and **printed for none that ran** (which is why a dead API key looked like a
model verdict), an uncollected row now says "absent, not clean" in the scorecard and the CI summary,
and **`:evals:test` ran in no workflow at all** — including the mutation test that proves the routing
sweep can go red.

**P1-2 — chat does not stream; the provider is why.** Live: TTFT **10.9 s**, then the whole answer as
a **single** `token` event, `done` 20 ms later, `composerId = llm-chat-v1`. Our SSE writer is ruled
out (only one `data:` line exists, so nothing is being coalesced) and `LlmChatComposer` is ruled out
by `ChatStreamingChunkCountTest` (it emits chunks whenever given deltas, across `<think>` blocks and
code fences). Assertions are on chunk *count* now — every prior test passes on a one-chunk stream,
which is why this went unnoticed. `ChatScreen`'s KDoc no longer claims token-by-token delivery; **the
published article still does.**

**P1-3 — the budget prices measured expected output.** `expectedOutputTokens = 1400`, from p50 1344 /
p90 2011 / max 2044 across 100 calls. The visible answer is ~100 tokens: **a thinking model's billed
deliberation is ~13× its reply**, so the plan's own "~75 tokens" was off by ~18×. Reading that
required its own fix — Gemini reports reasoning in `total_tokens` and nowhere else
(`prompt=2, completion=0, total=15`), so billed output is derived as `max(completion, total − prompt)`.
The default cap moved **0.2¢ → 1.5¢**, which is the consequence of pricing correctly, not a
relaxation: a correct estimate of a real call is ~0.25¢ (gpt-4.1-mini) to ~1.15¢ (gemini-3.6-flash),
and at 0.2¢ the correct estimate would refuse both — the original failure from the opposite
direction. The ceiling still binds via `max_tokens` and a 25× worst-case config stop. Tests now
observe the gate **admitting** a realistic request; the old ones only ever observed it rejecting.

**P2-1 — measured, hypothesis unsupported, ids left alone.** Source ids are 15–18 chars (median 17)
across all 3,803 corpus rows, so three citations do cost ~60 of 280 characters — 21%, as predicted.
But **0 of 2** validator rejections ended mid-citation; the one rejection in the clean run was
deliberation leaking into the answer. `citationTruncation` stays in the harness and reports the count
on every keyed run. n=2 is thin: this is *unsupported*, not refuted.

**P2-2 — the run is minutes, not ~20.** `mapCasesConcurrently` bounds concurrency at 8
(`EVAL_PROVIDER_CONCURRENCY`), bounded rather than unbounded because a 429 is recorded as
`provider-error` and would read as a quality failure. Ordering in the scorecard and the attempt log
is deterministic regardless of completion order (`EvalConcurrencyTest`), and each attempt carries
`case=<id>` — an accepted output nobody can locate cannot be adjudicated.

---

## Article impact

Owed outside this repo. Recorded here because the obligation otherwise lives only in the author's
head.

| Item | Owed | Why |
|---|---|---|
| P1-1 cloud retrieval gate | **Correction** to *Routing Modes Are Not a Routing Policy* | The article says the 100-case golden set covered on-device routes while the cloud routes had no equivalent gate. That is now false: the offline `book-retrieval` row checks all 100 golden cases in CI and is cross-checked against the production SQL book tier. |
| P1-2 chat streaming | **Correction** to *Routing Modes Are Not a Routing Policy* | The article states tokens reach the UI immediately, token-by-token, and justifies validating the accumulated stream on that basis. Measured deployment behaviour: one whole-answer event after 10.9 s TTFT. The endpoint and client support streaming, but the provider currently batches. Wrong, not stale. |
| P0-1 + P0-2 | **Update** to *I Stopped Eyeballing LLM Output* | Its "A Fallback Rate Is Not a Finding" section leaves this open and quotes the 0% → 42% jump. Honest replacement: with the validator unchanged, the provider composer is accepted on **99 of 100** cases, and the "ungrounded" figure was the scorer rewarding verbatim copying. The thesis survives — it was the harness again — but the verdict inverts. |
| P1-3 | **Correction** to *Routing Modes Are Not a Routing Policy* | The article repeatedly calls 0.2¢ the enforced cloud ceiling. The server's default is now **1.5¢**, priced from 1,400 measured expected output tokens; the client-side 0.2 value only decides whether cloud is permitted, not what a request may cost. |
| R-1 prose review | **Addendum** to *Routing Modes Are Not a Routing Policy* | The article's retrieval section reads like an ending. The stronger result is that 8/8 retrieval, a 99/100 validator pass rate, and CI grounding gates still did not make the prose worth showing: post-reseed human review rejected duplicate labels, parent/sibling-opening prose, explainer-echoing chat, and mostly move-describing line claims. |

### Suggested edits — *Routing Modes Are Not a Routing Policy*

These are corrections to the published piece, not a retroactive claim that its original design was
wrong.

1. **Five Surfaces, Three Privacy Classes** and **The Cloud Route** — replace each statement that
   the cloud surfaces have a "0.2¢ ceiling" with: *"The client policy's 0.2 value only permits the
   cloud route; the server enforces a separate per-request default of 1.5¢, estimated from 1,400
   measured expected output tokens."* Do not imply the two values are still linked.
2. **The Benchmark That Changed the Conclusion** — replace *"That covered the on-device surfaces.
   It did not cover the two cloud ones, which had no equivalent gate at all"* with: *"It initially
   covered only the on-device surfaces. The cloud path now has an offline `book-retrieval` CI row:
   all 100 golden cases must resolve from their move prefix, and the offline index is cross-checked
   against the production SQL tier."*
3. **From One-Shot to Streaming** — replace the claim that tokens "reach the UI immediately" and
   "token-by-token" with: *"The protocol and client support incremental SSE, and validation still
   runs on the accumulated response at turn end. The deployed provider currently batches: measured
   behaviour was about 10.9 s to first visible text, then one whole-answer event. That is a
   provider limitation, not evidence that the UI is delivering token-by-token."* Keep the
   cancellation claim only if it remains tested against an actual in-flight provider request.
4. **After Retrieval Is Arithmetic, Not Similarity** — add: *"This fixed retrieval correctness,
   not usefulness. After deployment and reseeding, a human review still rejected the output:
   duplicate labels, parent and sibling-opening prose, chat echoing the explainer, and line text
   that described moves rather than offering a plan, trade-off, or decision rule. Retrieval,
   validator acceptance and grounding CI are necessary evidence, not evidence that the player
   learned anything."*
5. **Optional but candid production footnote** — add that a reader sample exposed untagged model
   scratchpad prose that passed the chat validator. The live route now withholds leading note-shaped
   lines before they reach the UI; this is why raw-output inspection remains necessary even after
   validation. A fallback truncation was also changed to stop at word boundaries. The latter need
   not enter the article unless discussing fallback UX specifically.

**New material, and R-1 supplied the ending.** The thesis is no longer hypothetical: this pipeline
reached 8/8 retrieval accuracy, a 99/100 validator pass rate, a grounding scorer that survives
paraphrase, and a CI gate on all of it — and a human read ten samples and said *no*. The failures
were the opening's name printed twice, a cited passage listing sibling opening names, chat quoting
the explainer verbatim, and a corpus with ~500 claims spread across 3,803 rows. **Not one of those
is detectable by any faithfulness check, and every one is obvious to a reader in seconds.**

That is a stronger piece than "how to score grounding": *the guardrails were all green and the
product was unshippable*. It also complicates the two published articles' arc, which ends with the
harness fixed — the honest sequel is that fixing the harness bought correctness and not quality, and
that the hand-review the plan kept deferring found more in one sitting than three eval rows did.

## Fences

- **Do not weaken a validator to make a number improve.** P0-1 loosened a *scorer* after reading the
  outputs it was rejecting; the production validator was untouched, and its pass rate rose on its
  own. That asymmetry is the point.
- **A config failure is not a quality result.** The first keyed run reported 100% fallback; the cause
  was a mis-pasted API key (100 × HTTP 400 "Please pass a valid API key"). The harness now prints a
  redacted fingerprint (`[llm-route] key chars=… prefix=…`) so this costs seconds, not an afternoon.
- **A scorecard row is not quotable without its `model=`.** The harness reads `COACH_LLM_MODEL` from
  whoever runs it; the deployment holds its own in a Fly secret.
- **Do not quote `tools/verify_opening_retrieval.sh` as a result.** n=8, one call each; repeat runs on
  an unchanged build gave 5/8, 1/8, 2/8, 1/8 purely from provider flakiness. It is a wiring check.
  `:evals:run` is the citable number.
- **Change one thing per measurement.** A deliberation stripper and a prompt instruction shipped
  together once here and the pair regressed 4/8 → 1/8; neither could be attributed until they were
  separated (`ee1c7f2`, `94856e6`).
- **Do not simulate the human-only items.** R-1 and R-2 are human because their output is a
  judgement, not an artifact. An agent can produce a convincing "prose quality review" without
  reading anything a user would see. If the device or key is unavailable, leave the item open.
- **`:server:test` runs only in `ai-coach-evals.yml`**, which triggers on `server/**` paths and on PRs
  to `main`. Work on a branch is not covered until the PR is opened.
