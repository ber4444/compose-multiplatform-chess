# Cloud-eval follow-ups

This is the forward-looking work list for the opening explainer and position chat. It records the
agreed strategy and acceptance criteria; implementation history belongs in commits and the PR.

## Remaining state

- The production database has **not** yet been reseeded with the deployed image.
- The deployed service must be health-checked, reseeded with the current corpus, and sampled again
  before the new prose can be evaluated. A sample is not evidence until the response is known to
  come from the current image and corpus.
- R-1 remains open until a fresh sample passes the usefulness review below. Grounding, citation
  overlap, and validator acceptance are necessary but not sufficient.

## Acceptance bar

For every sampled answer, verify all of the following:

1. It answers the user's question or the explainer's stated purpose.
2. It adds an implication, intent, trade-off, plan, consequence, or condition—not only a board
   observation or opening label.
3. It is specific to the resolved line; strict-prefix ancestor and unrelated sibling material are
   absent unless the question explicitly asks for family context.
4. It is readable: no duplicate labels, repeated sentences, collapsed spaces, boilerplate, or
   mid-word truncation.
5. Every factual claim is supported by the retrieved passage or by a deterministic board feature.

The owner must re-read a fresh sample after each retrieval, corpus, or composer change. The sample
must include both Opening Explainer and Position Chat, including adversarial questions.

## Pre-merge / deployment checklist

- [ ] Deploy the image and confirm `/health` and the running release version.
- [ ] Reseed the corpus from the image that is serving traffic.
- [ ] Verify corpus completeness by database row count and seed version; do not infer completeness
      from API text. The seed input is walked in sorted order, so the final file's inserted rows can
      be used as an additional coverage check.
- [ ] Run `tools/collect_cloud_samples.sh <base-url> <output-directory>` and retain the generated directory containing the raw JSON payloads, raw chat SSE, and `summary.json`.
- [ ] Review the `summary.json` against the acceptance bar and record representative failures.
- [ ] Re-run the deployed 8-opening retrieval probe and the server test suite.

## R-1 implementation strategy

### 1. Retrieve the most specific line

- Retrieve line-specific plan/idea material next.
- Permit one same-family contrast only when it directly answers the question.
- Permit a family passage only for an explicit family-level question.

### 2. Store answerable player insights

Change the primary retrieval unit from a generic ECO row to a sourced, line-specific insight card.
Cards should support these fields where applicable:

`choice_reason`, `central_tradeoff`, `white_plan`, `black_plan`, `piece_purpose`,
`common_misconception`, `comparison_to`, `typical_response`, and provenance (line/ECO/source).

The generator may select one or two non-visible facts. Board-visible move narration is supporting
metadata, never the complete answer.

### 3. Derive board facts by replay

Use chess-core/SAN replay to derive facts such as material changes, gambits, castling rights,
central-pawn structure and tension, space, open files, bishop scope, developed minor pieces,
prepared pawn breaks, pins, and direct targets. Keep this deterministic layer separate from prose.
Do not grow a second SAN-pattern heuristic system in the server.

### 4. Route chat by answer shape

Classify the question before composing and require the corresponding structure:

| Shape | Minimum response |
|---|---|
| Why is X popular? | choice → trade-off → characteristic plan |
| What is piece X doing? | immediate target → role → follow-up plan |
| Should I worry about X? | direct yes/no → condition → response |
| Is pawn X free? | direct answer → cost → likely continuation |
| How does X differ from Y? | shared objective → structural difference → trade-off |
| What is the problem piece? | piece → restriction → standard remedy |
| What is the plan? | future action, target, or condition |

Add deterministic minimum-shape checks before display: polarity in the first clause for yes/no
questions; both sides/structures for comparisons; a future action for plan questions; and benefit
plus trade-off for why questions. These checks reject non-answers; they do not claim that an answer
is strategically correct.

### 5. Keep chat query-aware

Position Chat must answer the user's question, not quote the Opening Explainer's first sentence.
Provider prompts must not reward copying source wording merely to satisfy overlap checks. Preserve
the grounding contract while making the retrieved insight and the question explicit inputs to the
composer.

### 6. Keep presentation and observability correct

- Make label/title ownership explicit so only one layer renders an opening label.
- Preserve spaces and sentence boundaries through SSE chunking and final assembly.
- Keep word-boundary truncation and deliberation cleanup covered by tests.
- Make the collector print `composerId`, fallback/validator reason, finish reason, and `done`.
- Ensure terminal composer/fallback output is distinguishable from a stream that simply ended.
- Capture raw provider output for successful and rejected responses.

## Evaluation and monitoring

- Keep faithfulness, retrieval correctness, and usefulness as separate dimensions.
- Retain the deterministic grounding gate and mutation tests; do not weaken them to improve pass
  rates.
- Add sampled human usefulness review using the acceptance bar above. A high validator rate cannot
  close R-1 by itself.
- Track provider, model, prompt version, retrieval ids, token usage, latency, finish reason, and
  fallback category.
- Treat the server's request cap as per-request estimation, not cumulative spend; keep policy and
  enforcement values visible and tested.
- Keep cloud CI coverage enabled for server, eval, and corpus changes. A skipped retrieval gate is
  not a passing gate.

## Article updates to make after the next sample

- In **Routing Modes Are Not a Routing Policy**, state the enforced server cap and distinguish it
  from the client policy declaration; describe provider-batched streaming and the remaining
  usefulness limitation. Do not claim the cloud prose is user-ready until R-1 closes.

## Explicitly out of scope for this PR

- Full insight-card corpus migration and authoring.
- Strict-prefix retrieval redesign and query-shape classifier implementation.
- Replay-based feature extraction beyond the existing line-narration corrections.
- A new LLM judge or a validator relaxation.
- Article publication or external corrections; update those after the fresh sample supplies exact
  measurements.
