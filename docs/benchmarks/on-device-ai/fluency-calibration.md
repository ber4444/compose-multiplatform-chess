# Fluency scorer calibration (B14)

How the reading-level bounds in `FluencyScorer.FluencySurface` were set, and how to redo it.

## The problem this fixes

The scorer originally applied one bound — Flesch-Kincaid grade ≤ 6.0 — to every route. Result:

| Route | Fluency violation | What it meant |
|---|---:|---|
| `local-template` (opening) | **100%** | Every case failed |
| `local-template-chat` | **0%** | Not measured at all — `scoreChat` computed the result and dropped it |

Both numbers were useless, for opposite reasons. A criterion that always fails carries no more
information than one that always passes — the same defect this phase was written to fix in
ferryman's `tone_and_structure`, which passed 120/120.

The 6.0 figure came from patient-education studies by analogy, and the plan flagged it: *"the
specific numbers will not transfer without our own calibration set."* They didn't.

## Why one bound can't work

The surfaces produce structurally different text **by design**:

- The **move coach** writes one or two short instructional sentences under a 300-char cap.
- The **opening explainer** quotes retrieved corpus passages and names openings — "Ruy Lopez",
  "Nimzo-Indian". Those are proper nouns; no rewrite makes them shorter, and they are not a
  comprehension barrier for someone reading about that opening.
- **Chat** answers a position question in bounded prose.

FK weights syllables-per-word at 11.8, so domain vocabulary dominates the score. Holding a passage
quote to the same bound as "Nf3 develops the knight" measures the genre, not the writing.

## Method

Bounds are **regression bounds, not aspirations**. Each is derived from what that surface's
*deterministic* composer actually produces, because that text is the shipped floor — the plan's
standing position is that the deterministic path is "the floor, not the plan". The regression worth
catching is a model whose output reads harder than the template it replaced.

Rule: **bound = p90 of the deterministic floor + 1.0 headroom**, rounded to 0.5.

p90 rather than max, so a single dense outlier doesn't inflate the bound into uselessness — chat's
max of 18.4 is exactly the kind of sentence the gate should flag. The 1.0 headroom keeps ordinary
variation from flapping CI.

Measured 2026-08-02 over the 100-case golden set (200 chat turns):

| Route | n | min | p50 | p90 | max | Bound set |
|---|---:|---:|---:|---:|---:|---:|
| `fake-generator` (move-coach shaped) | 100 | 5.2 | 5.2 | 5.2 | 5.2 | **6.5** |
| `local-template` (opening) | 100 | 11.2 | 12.4 | 12.4 | 12.8 | **13.5** |
| `local-template-chat` | 200 | 12.5 | 12.5 | 12.5 | 18.4 | **13.5** |

Resulting violation rates: opening 0.0%, chat 5.0%, move-coach 0.0%. Chat's 5% is the point — 10 of
200 turns genuinely exceed the bound, so the column now discriminates.

`MOVE_COACH` is deliberately the tightest bound. It is the beginner-facing surface where the grade-6
product goal actually applies, and its floor sits far below the others.

## On the syllable counter's accuracy

`countSyllables` is a vowel-group heuristic and is wrong on individual words in both directions
("safety" scores 3, not 2). **This does not undermine the bounds**, because the same approximate
function both sets the bound and evaluates against it — systematic bias appears on both sides and
cancels.

The consequence is that the absolute numbers are **ordinal, not real US grade levels**. Do not
publish "our coach reads at grade 6" from this column. If an absolute figure is ever needed, use a
pronunciation dictionary (CMUdict), not a better heuristic — and recalibrate afterwards, because
changing the measure invalidates every bound above.

## Recalibrating

1. `EVAL_CALIBRATION=1 ./gradlew :evals:run` — prints one `[calibration]` line per route with
   min/p50/p75/p90/p95/max.
2. Apply the rule above to each surface's deterministic floor row.
3. Update `FluencyScorer.FluencySurface`, this table, and the floor figures asserted in
   `FluencyScorerTest.surface bounds are ordered coach tightest…`.
4. Re-run and confirm no route sits at 0% or 100% purely because of the bound.

The test asserts headroom over the measured floors on purpose: if a composer's wording drifts past
its bound, that test fails. **Recalibrate — don't widen the bound to make it pass**, or the gate
stops meaning anything.

Recalibrate whenever a deterministic composer's wording changes, the corpus is reseeded, or
`countSyllables` changes. The `Reading grade` column in `evals/scorecard.md` exists so drift is
visible without rerunning this procedure: if a median moves toward its bound, the bound is stale.
