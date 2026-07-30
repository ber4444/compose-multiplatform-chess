# Unifying the move coach and position chat on one retrieval-grounded path

> Status: proposed. Written after on-device verification of the Cactus move coach on a Galaxy Z
> Fold3 (SM-F926U, gemma3-270m). No code changes beyond the fixes already on
> `feat/position-chat-streaming` are assumed.

## What we observed

Two coached moves on real hardware, with the model confirmed running on-device (Cactus logged
`Context initialized successfully` and two completions, no fallback):

| Attempt | Panel text | Verdict |
|---|---|---|
| Before the echo fix | `Good: "Nf3 develops the knight and controls the central e5/d4 squares."` + `Good: "This is a good move that improves the position."` | Both sentences are **prompt scaffolding, verbatim** — style example #1 and the old `Bad:` counter-example. Nf3 was never played. |
| After the echo fix | `The move is Pawn c7 to C6.` | Correct and move-specific, but a **restatement of the user prompt's `Move:` line**, not an explanation. |

The deterministic fallback for that same move produces `Engine choice: c6. It gains space and opens
lines.` — which answers *why*. **The model lost to the templates.**

## Root cause: content in context, not model capability

The same model — literally the same object — serves both the coach and rules Q&A:

```kotlin
// onDeviceAi/src/androidMain/.../OnDeviceTextGeneratorFactory.android.kt
cachedGenerator ?: CactusTextGenerator().also { cachedGenerator = it }
```

A `@Volatile` singleton, warm across moves. `DefaultRulesQaAnswerer.android.kt` and
`DefaultAiCoachOrchestrator` both resolve through it. Yet rules Q&A is viable and the coach is not.
The difference is not the model:

- **Coach** — the context holds the move, the tags, and style examples. The *content* ("why is this
  good") must come from the model's weights. A 270M model has none, so it emits the nearest matching
  text in context: the examples.
- **Rules Q&A** — the context holds retrieved passages that *contain* the answer. The model only
  rephrases. That is a task 270M can do.

The coach asks a 270M model to reason. It cannot. Retrieval is what closes the gap, and the
retriever costs nothing: `BundledRuleLookupTool` is a pure-Kotlin BM25 scan, deliberately chosen so
that "every lookup [stays] local and dependency-free" without a second model in the binary.

## Proposal

Collapse the coach and chat onto one shape:

```text
query → retrieve (BM25, no model) → ground → generate (shared 270M) → validate → deterministic floor
```

They differ only in where the query comes from: the coach builds it automatically from the
deterministic tags; chat takes the user's question. Everything downstream is shared, including the
validator that already rejects prompt echoes and ungrounded output
(`MoveCoachResponseValidator`) and the floor that always describes the real move
(`MoveCoachFallback`, fed by `MoveCoachContextExtractor.deterministicTags`, whose check/checkmate
flags come from the perft-verified move generator).

### Phase 1 — Make the per-move line a retrieval turn

Reuse the two-turn structured-output pattern already proven in
`StructuredOutputRulesQaAnswerer`: Kotlin runs the lookup, the model only rephrases the passages.

- Build the BM25 query from data we already compute: the move (SAN/UCI), the piece, and the
  `deterministicTags` list.
- Pass retrieved passages *plus* the tags into the prompt. Drop "say WHY" phrasing — the prompt
  becomes a rewrite instruction, matching what `MoveCoachContextExtractor`'s own docstring already
  claims the design is: *"The model is never asked to derive these — it only rephrases them."*
- Keep `MoveCoachResponseValidator` as the gate and `MoveCoachFallback` as the floor. No new
  failure modes: an ungrounded or echoed turn falls back exactly as it does today.

No new model, no new download, no new native dependency. The generator is already resident.

### Phase 2 — Bundle a position/opening corpus

Today's bundled corpus is 31 *rules* passages — nothing about positions. The server carries ~3,800
lines of ECO opening corpus (`server/corpus/{a,b,c,d,e}.tsv`) behind
`OnnxMiniLmEmbedder` + Postgres. Distill a subset into the same generated-passages form
`BundledRuleLookupTool` already consumes.

Size is the constraint to measure, not the architecture — the retrieval seam is unchanged.

### Phase 3 — Phase-gate, and wire chat's offline route

Retrieval quality splits sharply by game phase:

- **Opening** — strong. Moves have crisp lexical identity (`1.e4 c6` → Caro-Kann), so BM25 finds
  real theory. `TAG_OPENING` (`fullmoveNumber <= 10`) already tells us when we are here.
- **Middlegame** — weak. "Why is this knight move good *in this position*" is not a lexical lookup;
  no corpus contains the position. Retrieval returns noise and the model has nothing to ground on.

So: retrieval-grounded line in the opening, deterministic templates after. This mirrors how a human
coach actually behaves — book knowledge early, general principles later.

The same bundled retriever then unblocks chat offline. `DefaultPositionChat` currently emits
`FALLBACK_NO_CHAT_MODEL` for on-device routes — that is a **policy** choice
(`AiRoutePolicies.positionChat` is cloud-only), not an architectural limit. The server's composer
already takes passages as a parameter:

```kotlin
fun streamCompose(request: PositionChatRequest, passages: List<Passage>): Flow<ChatChunk>
```

It does not care whether they came from Postgres or a bundled TSV.

## Decision gate — does the model beat the templates at all?

This plan is falsifiable, and the harness to falsify it already exists. `evals/scorecard.md` scores
routes on grounding violation / retry / fallback / length, and the **`cactus-android` row is empty**
("manual — hardware numbers not collected"). Fill it, and compare against the `local-template` and
`deterministic-fallback` rows that are already populated.

Ship Phase 1 only if the retrieval-grounded route beats `local-template` on grounding violation and
fallback rate. Judgement quality still needs owner hand-review — the scorecard header says as much —
but a route that cannot beat a template on the automated metrics is not worth 200 MB.

**Kill criteria.** If the grounded route does not beat the templates, remove the model from the
per-move line entirely and keep the deterministic one-liner: instant, offline, no battery cost,
always correct. Cactus stays in the build for the RAG paths (rules Q&A, and chat offline if Phase 3
lands), so the download still earns its place — it just stops pretending to be a chess commentator.

## Non-goals

- **Replacing the per-move line with chat outright.** They are different interaction models: the
  coach is push (fires automatically, zero user effort), chat is pull (the user must know what to
  ask). A casual player often does not. Keep an automatic line; make chat the depth surface.
- **A second on-device model.** Embeddings were rejected once already on size grounds; that
  reasoning still holds. If BM25 is not good enough, the answer is a better corpus or the cloud
  route, not a second runtime.
- **Upgrading to gemma3-1b in this plan.** That is a separate, measurable experiment. Phase 1 is
  worth doing first precisely because it is free — if grounding fixes the output at 270M, the
  larger model is unnecessary.

## Risks

- **270M may produce mush even when grounded.** Rules Q&A suggests otherwise, but that is a
  crisper task. The decision gate exists for this reason.
- **Corpus size vs. APK size.** Phase 2 is the only phase with a real budget question.
- **Retrieval collisions.** Style example #1 is about Nf3, a very common move; the echo detector
  will reject a legitimate Nf3 explanation that matches it. Low harm (falls back to templates), but
  swapping the examples to rarer moves would reduce it.
