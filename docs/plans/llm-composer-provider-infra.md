# Plan: Provider-shaped `LlmComposer` + eval route

Targets the chess repo's `:server` module. It is the provider-shaping half of the opening-explainer
cloud route: make the existing `LlmComposer` fully testable and add an LLM-composed eval row alongside
the template-composed one, without coupling to any specific provider.

## Context for the agent

The `LlmComposer` (enabled only when `COACH_LLM_API_KEY` is set) was previously specified abstractly.
This plan makes it concrete and **provider-shaped** — an OpenAI-compatible chat-completions client
whose base URL, model, key, and per-token prices all come from env vars. No provider-specific
configuration is committed; swapping providers is a config change, not a code change.

The eval side gets a second route: a `local-llm-compose` row alongside `local-template`, so the
two-row comparison (does LLM composition measurably beat the deterministic template on the judge
criteria, at what latency/cost) is a concrete eval finding when a provider is configured. The route
is OPTIONAL — when `COACH_LLM_API_KEY` + token prices aren't set it shows as optional, so the CI
grounding gate never depends on a live LLM provider.

## Why this is provider-neutral, not GLM-shaped

This plan was originally drafted to put z.ai's GLM behind the composer, ahead of a cross-repo
multi-provider eval run. That run has since happened (ferryman-mcp PR #10, merged 2026-07-17) and
**answered the question this plan was waiting on**:

- zai-glm ranked **last** on company-research (68%) and **tied-last** on chess (52%);
- on the chess skill it cost **~10×** gemini ($0.0117 vs $0.0012 per case) at **~24×** the latency
  (76529 ms vs 3134 ms).

There is no eval case for GLM as this app's composer. The provider-shaping, the testability, and the
optional eval route are what survived — landed here as permanent, vendor-neutral infrastructure. Any
future concrete provider (an OpenAI-compatible hosted open-model endpoint, or a local Ollama/vLLM
serving an open model — the stronger fit for the local-first framing) is a config change plus one
scorecard re-run. If the model family changes between runs, mark pre- and post-swap rows as
non-comparable in the scorecard rather than mixing them silently.

## Hard rules

- **Read the chosen provider's live API docs first.** Do not hardcode a base URL, model name, or auth
  scheme from memory. Record what you found (URL, model, per-token price, date checked) in the PR
  description.
- **Keys and URLs from env only** (`COACH_LLM_API_KEY`, `COACH_LLM_API_URL`, `COACH_LLM_MODEL`,
  `COACH_LLM_INPUT_USD_PER_MILLION`, `COACH_LLM_OUTPUT_USD_PER_MILLION`); nothing provider-specific
  committed. The code is provider-shaped (OpenAI-compatible client), not tied to any vendor —
  swapping providers later must be a config change.
- **The deterministic default stands.** `TemplateComposer` remains the server's default; LLM
  composition activates only when the env vars are present. The decider tests proving the move coach
  can never route to cloud are untouched.
- **The cost budget is enforced, not decorative.** `maxUsdCents = 0.2` per request translates to a
  hard token cap computed from the recorded per-token price; the composer refuses (and falls back to
  template) rather than exceeding it.
- **A judge never grades its own family.** In the cross-repo eval harness, when the evaluated route
  is one model family, the judge is a different family.

## Success command

`./gradlew :server:test` (including the new composer tests) and one manual end-to-end request
against the deployed service with the LLM env vars set for whichever provider is configured.

## M1 — chess server `LlmComposer` (provider-shaped) — DONE

- [x] `LlmComposer` runs against a minimal OpenAI-compatible chat-completions client: base URL,
  model, key from env. Retrieved passages go in the prompt; response passes through the same
  validation rules as everything else; validation failure → `TemplateComposer` fallback, logged
  with a reason.
- [x] Token cap from the cost budget as above. Tests: a fake HTTP engine covering success,
  validation-failure fallback, budget-exceeded refusal, and missing-env (composer not even
  constructed).
- [x] Update `evals/` so the scorecard gains an `LLM-composed` row alongside the template-composed
  row.

## M2 — provider matrix — ANSWERED CROSS-REPO

The multi-provider eval run this plan was sequenced around was executed in ferryman-mcp PR #10
(merged 2026-07-17) and produced a concrete verdict: z.ai's GLM ranked last on company-research and
tied-last on chess at ~10× gemini's cost and ~24× its latency. No GLM-specific work is warranted
here. Any future provider re-uses the provider-shaped client landed in M1 unchanged.

## M3 — judge diversity wiring — ANSWERED CROSS-REPO

The "judge never grades its own family" rule (judge model family ≠ evaluated model family) is
implemented in the cross-repo harness (`judge_scorer.py`'s provider-exclusion). No chess-repo
change is needed — this repo has no judge of its own.
