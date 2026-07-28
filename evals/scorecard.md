# AI coach eval scorecard

> Candidate dataset: 100 opening cases (each carries an `eco` field and `expectedConcepts`). Owner hand-review is still required before article publication.

| Route | Cases | Grounding violation | Retry | Fallback | Length violation | Collection |
|---|---:|---:|---:|---:|---:|---|
| fake-generator | 100 | 0.0% | 0.0% | 0.0% | 0.0% | automated |
| deterministic-fallback | 100 | 0.0% | 0.0% | 100.0% | 0.0% | automated |
| local-template | 100 | 0.0% | 0.0% | 0.0% | 0.0% | automated |
| deployed-cloud | — | — | — | — | — | optional (COACH_DEPLOYED_URL not set) |
| local-llm-compose | 100 | 0.0% | 0.0% | 89.0% | 0.0% | optional |
| cactus-android | — | — | — | — | — | manual (hardware numbers not collected) |
| foundation-models-ios | — | — | — | — | — | manual (hardware numbers not collected) |

The scorer is rule-based: move cases use `MoveCoachResponseValidator`; opening cases require all `expectedConcepts`. No judge model is used.
