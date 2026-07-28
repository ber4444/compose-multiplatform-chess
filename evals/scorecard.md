# AI coach eval scorecard

> Candidate dataset: 100 total cases, 100 opening cases. Owner hand-review is still required before article publication.

| Route | Cases | Grounding violation | Retry | Fallback | Length violation | Collection |
|---|---:|---:|---:|---:|---:|---|
| fake-generator | 100 | 0.0% | 0.0% | 0.0% | 0.0% | automated |
| deterministic-fallback | 100 | 0.0% | 0.0% | 100.0% | 0.0% | automated |
| local-template | 100 | 0.0% | 0.0% | 0.0% | 0.0% | automated |
| local-template-chat | 200 | 0.0% | 0.0% | 0.0% | 0.0% | automated |
| deployed-cloud | — | — | — | — | — | optional (COACH_DEPLOYED_URL not set) |
| local-llm-compose | — | — | — | — | — | optional (COACH_LLM_API_KEY or token prices not set) |
| cactus-android | — | — | — | — | — | manual (hardware numbers not collected) |
| foundation-models-ios | — | — | — | — | — | manual (hardware numbers not collected) |

The scorer is rule-based: move cases use `MoveCoachResponseValidator`; opening cases require all `expectedConcepts`; multi-turn chat cases require at least one expected concept per turn (the no-drift check). No judge model is used.
