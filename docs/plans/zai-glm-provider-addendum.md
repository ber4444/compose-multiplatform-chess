# Plan: z.ai/GLM as a Concrete Provider (Cross-Repo Addendum)

Targets: the chess repo's `:server` module (after `opening-explainer-cloud-route.md` M1 lands)
and the OpenClaw repo (after `openclaw-eval-harness-plan.md` M1 lands). Suggested location: a
copy in each repo's `docs/plans/`.

## Context for the agent

Two existing plans reference an LLM provider abstractly: the chess server's `LlmComposer`
("enabled only when `COACH_LLM_API_KEY` is set") and OpenClaw's multi-provider eval matrix
("every provider the router can reach"). The repo owner has z.ai API credits to use, which
makes GLM the natural first concrete provider for both — and provider diversity is itself a
finding: the eval matrix and the judge-vs-judged separation only demonstrate anything if at
least two genuinely different model families are wired.

**Deadline and sequencing:** the z.ai credits expire **July 19, 2026**. Therefore run M2 (the
OpenClaw provider matrix) first — it depends only on the harness plan's M1 scaffolding, not on
the chess server — so the credits produce the multi-provider scorecard before they lapse. M1
(the chess-server composer) has no deadline: build it provider-shaped as specified and debut it
on the post-expiry provider below.

**After July 19:** swap `COACH_LLM_BASE_URL` / `COACH_LLM_MODEL` / key to either (a) an
OpenAI-compatible hosted open-model provider (OpenRouter, Together, Fireworks, DeepInfra —
prefer one serving GLM's open-weight releases so the model family, and thus the eval numbers'
comparability, is preserved), or (b) a local Ollama/vLLM endpoint serving an open model — zero
marginal cost, and the stronger fit for OpenClaw's local-first framing. Because everything in
this plan is env-configured, this is a config change plus one scorecard re-run; if the model
family changes, mark pre- and post-swap rows as non-comparable in the scorecard rather than
mixing them silently.

## Hard rules

- **Read the current z.ai API docs first.** Do not hardcode a base URL, model name, or auth
  scheme from memory — z.ai exposes OpenAI-compatible endpoints, but the exact base URL, current
  GLM model identifiers, and pricing must come from their live documentation at implementation
  time. Record what you found (URL, model, per-token price, date checked) in the PR description.
- **Keys and URLs from env only** (`COACH_LLM_API_KEY`, `COACH_LLM_BASE_URL`,
  `COACH_LLM_MODEL`); nothing provider-specific committed. The code is provider-shaped
  (OpenAI-compatible client), not z.ai-shaped — swapping providers later must be a config
  change.
- **The deterministic default stands.** `TemplateComposer` remains the server's default; GLM
  composition activates only when the env vars are present. The decider tests proving the move
  coach can never route to cloud are untouched.
- **The cost budget is enforced, not decorative.** `maxUsdCents = 0.2` per request translates
  to a hard token cap computed from the recorded per-token price; the composer refuses (and
  falls back to template) rather than exceeding it.
- **A judge never grades its own family.** In the OpenClaw harness, when the evaluated route is
  GLM, the judge is a non-GLM model, and vice versa.

## Success command

Chess repo: `./gradlew :server:test` (including the new composer tests) and one manual
end-to-end request against the deployed service with the GLM env vars set.
OpenClaw repo: `python eval_harness/run_scorecard.py --all-providers` with GLM appearing as a
scored provider column.

## M1 — chess server `LlmComposer` (GLM-backed)

- Implement `LlmComposer` against a minimal OpenAI-compatible chat-completions client (Ktor
  client, no heavyweight SDK): base URL, model, key from env. Retrieved passages go in the
  prompt; response passes through the same validation rules as everything else; validation
  failure → `TemplateComposer` fallback, logged with a reason.
- Token cap from the cost budget as above. Tests: a fake HTTP engine covering success,
  validation-failure fallback, budget-exceeded refusal, and missing-env (composer not even
  constructed).
- Update `evals/` so the scorecard gains a `Ktor RAG + GLM compose` row alongside the
  template-composed row — the two-row comparison (does LLM composition measurably beat the
  template on the judge criteria, at what latency/cost) is exactly the kind of concrete
  eval finding the articles are hungry for.

## M2 — OpenClaw provider matrix

- Add GLM (via z.ai) to OpenClaw's provider-routing config following whatever pattern existing
  providers use (M0 of the harness plan already required locating that config).
- `--all-providers` now includes GLM; scorecard gains its column with score, latency, and cost
  (cost computed from the recorded pricing, marked with the date checked).

## M3 — judge diversity wiring

- Extend `judge_scorer.py` with a provider-exclusion rule: judge model family ≠ evaluated model
  family. Re-run the judge-stability sanity check (3–5 repeats on one case) with the GLM judge
  before trusting its scores.

## Article hooks (write only after real runs)

- OpenClaw article, section 6: the provider table now has at least two real model families —
  the section's whole argument depends on that.
- Coach article, evals section: one sentence comparing template vs. GLM composition on the
  opening explainer, with the measured delta, once the scorecard has it. Same honesty gate as
  everything else: no numbers before runs.
