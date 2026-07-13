# Plan: Opening-Explainer Cloud Route + Eval Harness

Target branch: `on-device-ai-move-coach` (PR #50). Suggested location for this file: `docs/plans/opening-explainer-cloud-route.md`.

## Context for the agent

PR #50 already ships an offline-only on-device move coach: the `:onDeviceAi` KMP module
(`com.example.ondeviceai`) owns `AiRoutePolicy`, `AiRoutePolicyDecider`, `AiRoute`,
`DefaultAiCoachOrchestrator`, `MoveCoachPromptBuilder`, `MoveCoachResponseValidator`,
`MoveCoachFallback`, `OnDeviceTextGenerator`, and `FakeTextGenerator`. Android runs Cactus
(llama.cpp, gemma3-270m); iOS runs Foundation Models; desktop/web fall back to deterministic text.

This plan adds the one feature that is **allowed** to leave the device — an opening explainer —
so the policy's `allowCloud` and cost-budget fields are exercised by real code, plus the eval
harness that scores every generator against a golden set. Read `AGENTS.md` and `CLAUDE.md`
first; the core↔app boundary fences there are binding for this work too.

## Hard rules

- **Never weaken `MoveCoachResponseValidator`** (or its tests) to make an eval or a cloud
  response pass. The validator is the spec; failing output falls back.
- **The move coach stays offline-only.** `requireOffline = true` and `allowCloud = false` on the
  move-coach policy must not change. The cloud route serves the opening explainer exclusively.
- **No platform or network types in `:onDeviceAi` commonMain** beyond a Ktor-client interface
  boundary. The routing/validation layer stays test-fakeable.
- **No secrets in the repo.** Database URLs, provider API keys, and deploy tokens come from env
  vars only. Committing a `.env`, key, or connection string is a failed milestone.
- **`:coachApi` stays dependency-minimal**: `kotlinx-serialization` only. No Ktor, no coroutines,
  no chess-core. It is a wire-model module, nothing else.
- **Two endpoints, one Postgres.** Do not add queues, extra services, gateways, or caching tiers.
  Smallness is the point.

## Success command

```bash
./gradlew :coachApi:build :server:test :onDeviceAi:desktopTest && ./gradlew :evals:run
```

All green, plus: `server/openapi.yaml` committed, `evals/scorecard.md` regenerated, and (human
step) the Fly.io URL responding on `/health`.

## M0 — `:coachApi` wire models

New KMP module, package `com.example.coachapi`. Targets: every target `:app` compiles for, plus
`jvm` (the server consumes it as a plain JVM dependency).

- `OpeningExplainRequest(fen: String, movesSan: List<String>, eco: String?, locale: String?)`
- `Passage(sourceId: String, title: String, text: String)`
- `OpeningExplainResponse(text: String, passages: List<Passage>, composerId: String)`
- `ApiError(code: String, message: String)`
- All `@Serializable`. A round-trip serialization test in `commonTest` per type.
- Register in `settings.gradle.kts`; `:app` and `:server` depend on it. Nothing in the payloads
  may identify a user — FEN, SAN, ECO, locale only. Add a comment saying so; it is the
  `PUBLIC_OR_SYNTHETIC` contract.

## M1 — `server/` Ktor service

New JVM-only Gradle module `:server` (Ktor, Netty engine, kotlinx-serialization content
negotiation, HikariCP + JDBC).

- **Endpoints:** `POST /v1/openings/explain` (body `OpeningExplainRequest`, returns
  `OpeningExplainResponse`) and `GET /health`.
- **Storage:** Postgres with `pgvector`. One table `passages(id, source_id, title, text,
  embedding vector(384))`. Schema in `server/src/main/resources/schema.sql`, applied
  idempotently on startup.
- **Corpus + seeding:** `server/corpus/` holds the source material — the ECO openings TSVs from
  lichess-org/chess-openings (CC0; keep attribution in `THIRD_PARTY_NOTICES.md`) plus curated
  concept notes as markdown. A `:server:seed` Gradle task (main class `SeedMain`) chunks,
  embeds, and upserts.
- **Embeddings:** `all-MiniLM-L6-v2` via ONNX Runtime JVM (`com.microsoft.onnxruntime:onnxruntime`),
  384-dim, used identically at seed time and query time. Wrap it behind an `Embedder` interface
  with a deterministic fake for tests so `server:test` never downloads a model.
- **Retrieval:** embed the request (ECO name + last N SAN moves as the query), `ORDER BY
  embedding <=> $1 LIMIT 4`.
- **Composition:** `TextComposer` interface, two impls. `TemplateComposer` (default): stitches
  retrieved passages into 2–3 grounded sentences deterministically — zero model cost, always
  available. `LlmComposer`: calls a provider API, enabled only when `COACH_LLM_API_KEY` is set;
  passages go in the prompt, and the response is checked by the same validation rules as the
  on-device coach (port the checks or expose them JVM-side). If the LLM output fails validation,
  fall back to `TemplateComposer` — never return unvalidated prose.
- **Tests:** route tests with `testApplication`, fake `Embedder`, and Testcontainers Postgres
  (`pgvector/pgvector` image) for the retrieval query; a serialization contract test that
  round-trips `:coachApi` types through the running route.

## M2 — OpenAPI contract + deploy scaffolding

- Hand-write `server/openapi.yaml` as the source of truth for the two endpoints; add a server
  test that validates real responses against the spec (e.g. swagger-request-validator). The spec
  is reviewed in PRs like code.
- `server/Dockerfile` (multi-stage, JRE 21 runtime) and `server/fly.toml` (min machines 0 is
  fine; cold starts are part of the article's honesty). `DATABASE_URL` from Fly Postgres or
  Neon via secrets.
- **Human step (agent stops here):** `fly launch --no-deploy`, `fly secrets set DATABASE_URL=…`,
  `fly deploy`, then run the seed task against the prod DB and put the base URL in `README.md`.
  The agent prepares everything up to, but not including, commands that need credentials.

## M3 — wire the cloud route into the app

- Add `AiRoutePolicy.openingExplainer` in `:onDeviceAi` commonMain: `PUBLIC_OR_SYNTHETIC`,
  `allowCloud = true`, `requireOffline = false`, `firstTokenMs = 2500`, `completeMs = 8000`,
  `maxUsdCents = 0.2`.
- Extend `AiRoute` with a `Cloud` case; extend `AiRoutePolicyDecider` so `Cloud` is reachable
  **only** when the policy allows it — add decider tests proving the move-coach policy can
  never route to `Cloud` (this is the test that makes the article's claim true).
- `OpeningExplainerClient` in commonMain as an interface; Ktor-client implementation injected
  from `:app` (base URL from build config / `local.properties`, never hardcoded to prod).
  Offline or non-2xx → the existing deterministic-fallback path, surfaced as a normal product
  state.
- UI: post-game panel in `:app` showing the explanation with source titles. Follow the existing
  coach-panel pattern; respect the fences (no Compose or network types leak into `:chess-core`).

## M4 — `evals/` golden set + scorecard

- `evals/golden/` — ~100 JSON cases: `fen`, `bestMoveUci`, `tags`, and for opening cases
  `eco` + `expectedConcepts`. Start by generating candidates from the existing perft/canonical
  positions plus a scripted sample of real games; hand-check before committing (human review
  step — the agent proposes, the owner prunes).
- Scorer = the existing `MoveCoachResponseValidator` plus concept-coverage checks for openings.
  Rule-based only; no judge model in v1.
- `:evals:run` Gradle task executes every case against each available generator —
  `FakeTextGenerator`, the deterministic fallback, `TemplateComposer` via a local server
  instance, and (when reachable) the deployed URL — and writes `evals/scorecard.md` with
  grounding-violation, retry, fallback, and length-violation rates per route.
- CI: a job that runs `:evals:run` against fake + fallback + local-server routes on every PR
  touching `:onDeviceAi`, `:coachApi`, `:server`, or any prompt text; a grounding-violation
  regression fails the build. On-device numbers (Cactus, Foundation Models) are collected
  manually on hardware and pasted into the scorecard — mark them as such.

## M5 — On-device rules Q&A (function calling + on-device RAG)

A second `LOCAL_ONLY` capability, distinct from both the move coach (no retrieval) and the
opening explainer (cloud retrieval): a bundled, on-device corpus the model can query via tool
calling when a player asks a rules question the deterministic tags don't cover.

- `rulesCorpus/` — a few dozen chunked passages summarizing the FIDE Laws of Chess, checked in
  as app assets (public-domain/CC-summarized text; record the source in
  `THIRD_PARTY_NOTICES.md`). Pre-embed at build time into a small flat vector file — no database,
  no server, small enough for cosine-similarity scan over a few dozen vectors.
- `RuleLookupTool` interface in `:onDeviceAi` commonMain: `suspend fun lookup(query: String):
  List<RulePassage>`. Add `AiRoutePolicy.rulesQaOffline` (`LOCAL_ONLY`, `requireOffline = true`,
  `allowCloud = false`) and decider tests proving this policy, like the move-coach one, can never
  route to `Cloud`.
- **iOS:** implement as a real Foundation Models `Tool` conformance — the session invokes it
  mid-response; this is native tool calling, not a workaround. Query-time embedding via
  `NLEmbedding` (on-device, no model to bundle).
- **Android:** Cactus/llama.cpp has no native tool-calling contract for a model this small. Ship
  the honest fallback: prompt the model to emit a `{"tool": "lookup_rule", "query": "..."}` JSON
  envelope when it doesn't know an answer, run the actual lookup in Kotlin, feed the passage back
  in a second turn. Name this as structured-output prompting in code comments and in the article
  — don't dress it up as native function calling on Android.
- Query-time embedding on Android needs a small bundled sentence-embedding model (not the
  generation model) — evaluate a compact ONNX/TFLite embedding model before committing; if none
  fits the size budget, a keyword/BM25 fallback over the same corpus is an acceptable v1 rather
  than blocking the milestone on model search.
- Validator: same pattern as `MoveCoachResponseValidator` — an answer that doesn't cite a
  retrieved passage ID is rejected and falls back to a static rules-summary string.
- Tests: fake `RuleLookupTool` in commonTest exercising the decider and validator; a small fixed
  query set (10–15 rules questions) checked against the real bundled corpus on each platform.

## Verification matrix

| Check | Command |
|---|---|
| Wire models round-trip | `./gradlew :coachApi:allTests` |
| Server routes + retrieval | `./gradlew :server:test` (Testcontainers) |
| Contract matches spec | included in `:server:test` |
| Move coach still offline-only | `./gradlew :onDeviceAi:desktopTest` (decider tests) |
| Rules Q&A still offline-only | `./gradlew :onDeviceAi:desktopTest` (decider tests, M5) |
| Eval scorecard regenerates | `./gradlew :evals:run` |
| Existing suites untouched | `./gradlew :app:desktopTest :chess-core:desktopTest` |

## Article gate (do not skip)

The "cloud route" and "evals" sections of the draft article ship only when: the service is
deployed and answering, `openapi.yaml` is committed, and `evals/scorecard.md` has real numbers.
The "Rules on tap" section (M5) ships only when the branch exists with the decider tests green
on both platforms — it is explicitly marked in the article as a design sketch until then; don't
promote it to shipped-feature prose early.

The article's Android runtime section was already corrected to describe the actual evaluation
journey (ML Kit rejected on AICore device coverage; LiteRT-LM rejected on model size/cold
start/streaming crash; ONNX ruled out; ExecuTorch evaluated; Cactus shipped) — no further edit
needed there unless M5 or a future runtime swap changes the story again.
