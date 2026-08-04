# Follow-ups — cloud eval honesty and the two cloud surfaces

Scope: everything found while fixing the cloud retrieval path (commits `9b32f12`…`4947cc5`) that
was deliberately **not** fixed at the time, plus the two items from the original branch review that
are still open. Ordered by severity.

Context as of this revision. Book-first retrieval ships and scores 8/8 on the live corpus. The LLM
composer's "100% fallback" turned out to be four bugs in our own pipeline (sentence counter, non-null
`content`, a 90-token cap, and no way to tell the causes apart); after fixing them, **47 of the 48
provider responses that arrived passed the validator**. The remaining 53% were provider timeouts and
503s. `:server:test` now runs in CI with a skip-detector. See CLAUDE.md § *Cloud retrieval* and
§ *Why the provider LLM "failed"*.

The through-line in every item below: **an aggregate that cannot name its own cause is not
evidence.** Each one is a number we currently quote that measures something other than what its name
says.

---

## P0 — a metric that cannot be won

### P0-1 `EvalScorer.scoreOpening` scores copying, not grounding

`grounded = case.expectedConcepts.all { lower.contains(it.lowercase()) }` — verbatim containment.
97 of the 100 golden cases require the literal string `"development"`, 92 require `"center"`.
`TemplateComposer` quotes its source passage, which contains both words, so it scores **0% violations
by construction**; any paraphrase fails. The column is named for grounding and measures copying.

This is not academic: it is exactly the column that would be used to declare the deterministic
template the winner over a model — the same conclusion `docs`/the article already reached once on
numbers that turned out to be measuring the harness.

- [ ] Decide what grounding should mean here. Candidate: concept **coverage** via a small synonym
      set per concept (`center` ⊃ `centre`, `central`), or overlap against the retrieved passage
      rather than a fixed keyword list.
- [ ] Whatever replaces it, **prove it can fail and can pass**: add one fixture that is grounded and
      paraphrased (must pass) and one that is fluent but off-position (must fail). A criterion never
      observed failing is untested — see the article's own point about `tone_and_structure`.
- [ ] Re-run `:evals:run` and record the honest number; the current 42% figure is uninterpretable.

**Do not** simply relax the check until the LLM row looks good. The failure mode being guarded
against is real (a model citing a passage while discussing a different position); only the
*measurement* of it is wrong.

### P0-2 Adjudicate the 42% with the data we now capture

`ComposeAttempt.Accepted` carries its text as of `95fe26f`, so accepted outputs land in
`evals/build/llm-compose-attempts.txt`. Nobody has read them.

- [ ] Run `:evals:run` with a provider key (see README step 9 for the cost-cap arithmetic — the
      0.2¢ default rejects every call before the network at Gemini prices).
- [ ] Read the accepted outputs and classify: genuinely off-concept, or correct paraphrase the
      scorer cannot see? This determines whether P0-1 is purely a scorer fix or also a prompt issue.
- [ ] Record the finding in this file before changing the scorer, so the fix is driven by observed
      output rather than by what makes the number move.

---

## P1 — numbers that mislead, and a claim we may not be able to make

### P1-1 `local-llm-compose` is `OPTIONAL`, so cloud grounding never gates CI

`main()` fails the build only for `CollectionMode.AUTOMATED` rows with grounding violations. The
cloud row is `OPTIONAL` because it needs a provider key, which CI does not have. Net effect: the
on-device surfaces have a hard grounding gate and the two cloud surfaces have none — the precise gap
that let 8/8 wrong retrieval ship unnoticed.

- [ ] Gate what *can* be gated without a key: the deterministic `local-template` row already covers
      composition, but nothing asserts retrieval correctness. Promote an offline retrieval-grounding
      check (ECO resolved from moves) into the AUTOMATED set.
- [ ] Keep the provider-dependent row optional, but make its absence **visible** in the scorecard
      rather than silently blank — mirror the CI skip-detector added in `4947cc5`.

### P1-2 The chat route may not actually stream

Four live calls to `/v1/positions/chat/stream` each returned the whole answer as a **single**
`token` event, not token-by-token. The article describes token-by-token streaming as a user-facing
property, and `ChatViewModel` renders per-token for exactly that reason.

- [ ] Determine whether this is the provider batching, `LlmChatComposer`'s think-stripping state
      machine buffering to completion, or SSE flushing. The stripper holds text back while a
      `<think>` prefix is ambiguous — check whether it releases incrementally in practice.
- [ ] If it does not stream, either fix it or correct the claim. Do not leave both standing.
- [ ] Add an assertion on chunk **count** (> 1 for a multi-sentence answer), not just accumulated
      text — the existing tests would pass on a single-chunk stream.

### P1-3 `ProviderCostBudget.admits()` charges the token ceiling

It prices `maxOutputTokens` (2048) rather than expected output (~75 tokens for a compliant answer),
overestimating by roughly 11×. Consequence: the cap must be set ~10× above intended spend to permit
calls at all, so the configured number no longer communicates a budget. At $9/M output, the 0.2¢
default rejects every request *before the network*.

- [ ] Either price an expected-output estimate and keep the ceiling as a separate hard stop, or keep
      the current behaviour and rename the constant to say it is a worst-case bound.
- [ ] Whichever: add a test asserting a realistic request is admitted at the documented default, so
      a future price change that silently disables the composer fails loudly.

---

## P2 — smaller, but each one hides a real signal

### P2-1 Citation ids consume ~60 of the 280-character budget

Source ids run ~20 chars (`lichess-b-373-b20`); three cited sentences spend ~60 characters on
citations alone against a 280-char instruction and a 300-char validator cap. Plausibly behind the
residual "uncited sentence" rejections, where the model appears to run out of room mid-citation.

- [ ] Measure first: count rejections whose raw output ends mid-citation.
- [ ] If confirmed, shorten the ids at seed time (a stable short key per passage) rather than
      raising the caps — the 300-char limit exists because the panel sits under a chessboard.

### P2-2 `:evals:run` takes ~20 minutes

100 provider calls run sequentially, and `PROVIDER_TIMEOUT_MS` moved 5s → 20s (necessary: 5s was
timing out legitimate thinking-model responses and reporting them as quality failures).

- [ ] Parallelize the LLM route with bounded concurrency. It already runs outside `testApplication`,
      so the 60s ceiling does not apply.
- [ ] Keep ordering deterministic in the scorecard regardless of completion order.

---

## From the original branch review — still open

### R-1 Prose quality hand-review of the two cloud surfaces

The review asked for this and it has not been done. The corpus tautology is fixed (passages now lead
with an `EcoNarrator` claim instead of "X is classified as ECO Y"), but **nobody has read the
resulting output** for whether it is worth showing a user. Every automated check here verifies
faithfulness, and content-free text is trivially faithful — no validator can catch "grounded and
useless".

- [ ] Read ~10 Opening Explainer responses and ~10 Position Chat turns end to end.
- [ ] Judge usefulness, not correctness: does it tell the player something they could not see?
- [ ] `tools/verify_opening_retrieval.sh` prints the first 70 chars of each; that is enough to spot
      truncation but **not** enough for this review. Read full responses.

### R-2 Chat monitoring: `composerId` and time-to-first-token

Related to P1-2 but distinct: the concern is a *silent* downgrade. A provider timeout drops to
`TemplateChatComposer` and the user sees a plausible answer with no error, after a long wait.
`CHAT_PROVIDER_TIMEOUT_MS` is now 20s (was 30s) and failures log `chat-provider-failed`.

- [ ] Watch `composerId` and TTFT across ~10 real chat turns.
- [ ] Confirm `chat-provider-failed` appears in `fly logs` when the template path fires.
- [ ] A fallback that takes 20s to produce boilerplate is worse UX than an error — if that is what
      happens in practice, surface it in the UI instead of silently substituting.

---

## Fences

- **Do not weaken a validator to make a number improve.** Every gate here exists because of an
  observed failure. Fix the measurement, not the threshold.
- **Do not quote `tools/verify_opening_retrieval.sh` as a result.** n=8, one call each; repeat runs
  on an unchanged build gave 5/8, 1/8, 2/8, 1/8 purely from provider flakiness. It is a wiring
  check. `:evals:run` is the citable number.
- **Change one thing per measurement.** A deliberation stripper and a prompt instruction shipped
  together once here and the pair regressed 4/8 → 1/8; neither could be attributed until they were
  separated (`ee1c7f2`, `94856e6`).
- **`:server:test` runs only in `ai-coach-evals.yml`**, which triggers on `server/**` paths and on
  PRs to `main`. Work on a branch is not covered until the PR is opened.
