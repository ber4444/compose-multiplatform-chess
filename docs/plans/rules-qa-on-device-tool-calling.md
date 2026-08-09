# Rules Q&A — make it answer, then make the model earn its place

Scope: the Android Rules Q&A path (`StructuredOutputRulesQaAnswerer`, `DefaultRulesQaOrchestrator`,
`BundledRuleLookupTool`, the `OnDeviceTextGenerator` seam) plus the two runtime upgrades requested
after PR #131: **native Cactus tool calling** and a **structured answer envelope** that ML Kit can
serve too.

Status: written after PR #131's fixes did **not** resolve the on-device failure, and updated after
commits `cb8e260`, `831ebce`, and `729b45d` on `ai-coach-debug-and-fixes`. These commits implemented
part of the plan, but the primary goal (first launch answers from the corpus) is **not** met. Ordered
by severity. P0 items are why the feature is dead; P1 is the requested runtime work; P2 is cleanup.

---

## Where it stands

The retrieval floor was added inside `StructuredOutputRulesQaAnswerer`, but `DefaultRulesQaOrchestrator.answer`
short-circuits on `Decision.FallBack` before the answerer is called. Thus, `lookupTool.lookup(question)`
never runs while `probeAvailableLocalVendors()` is empty — i.e. during the first-launch download, which
is device criterion #1. The first-launch path is unchanged.

## The reported failure

Question typed on device: **"Game is a draw when only kings remain?"**
Answer rendered: *"I couldn't verify that rule from the offline reference…"* + `Offline reference fallback`.

That string is `RulesQaFallback.TEXT`. It is emitted from exactly one place —
`DefaultRulesQaOrchestrator.fallback(...)` — reached from six distinct conditions. **The screen
renders all six identically**, which is the reason four rounds of fixes landed on the wrong one.

## What is NOT broken (measured, not assumed)

Retrieval. The BM25 scorer was re-implemented exactly (tokenizer, stemmer, stopwords, K1/B, the
3× title weighting) and run against the real `passages.tsv`:

```
QUERY: 'Game is a draw when only kings remain?'   terms: [game, draw, only, king, remain]
   9.079  draw-dead-position     <-- correct, by a 52% margin
   5.979  draw-agreement
   4.874  board-goal
```

`RuleLookupToolTest` already pins this exact sentence, and it passes. **A green retrieval suite and
a dead feature coexisted for four commits** because retrieval was never the failing step. The
commits that mapped `2`→`two`, added `only kings remain` to the passage text, and boosted title
weight were all tuning a step that already worked.

**Rule for this area: before touching the corpus or the scorer, prove retrieval is the failing
step.** `docs/` now carries the script-equivalent above; reproduce it, don't re-guess.

---

## P0 — why it is still dead

### P0-1 The 20 s timeout discards a completed retrieval

`RulesQa.kt:131` wraps the **entire** answerer call:

```kotlin
withTimeoutOrNull(AiRoutePolicies.rulesQaOffline.latencyBudget.completeMs) { answerer.answer(...) }
   ?: return fallback(FallbackReason.Timeout)
```

`completeMs = 20_000` (`AiRoutePolicy.kt:82`). The Android answerer runs **two** model turns — 80
tokens, then 160 tokens over a ~1 000-character passage prompt. On a 270M CPU model at 15–30 tok/s,
plus prefill, plus a cold ML Kit/AICore session, 20 s is not a comfortable ceiling. When it trips,
the successful lookup sitting in memory is destroyed along with the model turns.

This is the same defect class PR #131 fixed one layer down, still present one layer up: **a model
failure erasing deterministic work.** #131 cannot cover it — the timeout is outside the answerer.

- [x] **Partial:** Split the budget: retrieval runs outside any timeout (it is pure CPU over 30 rows,
      sub-ms), only generation is bounded.
- [x] **Partial:** On timeout, return the **retrieved passage**, not `RulesQaFallback`.
      `RulesQaGrounding.composeFromPassages` already produces exactly this; the orchestrator needs the
      passages to call it.
- [x] **Partial:** `RulesQaModelOutput` gains the passages (not just their ids) so the orchestrator can
      compose a grounded answer without a second lookup. Additive field, default empty.
- [x] **Partial:** Test: an answerer that never returns still yields a cited answer, not the fallback.

*Evidence: `RulesQaModelOutput.retrievedPassages` exists (`RulesQa.kt:10`) and `DefaultRulesQaOrchestrator`
composes from it on validation failure. But the timeout moved into `StructuredOutputRulesQaAnswerer` and
is applied twice, both with `rulesQaOffline.latencyBudget.completeMs` — worst case ≈40 s, looser than before.*

### P0-2 The answerer is resolved once, before Cactus exists

`AppRoot.kt:99`:

```kotlin
val rulesQaAnswerer = remember { defaultRulesQaAnswerer(createBundledRuleLookupTool()) }
```

`remember` with no key — evaluated at **first composition**, never again. The Android `actual`
(`DefaultRulesQaAnswerer.android.kt:6`) returns non-null only `if (isCactusInitialized())`, and
Cactus initialises asynchronously after launch (on first run, behind a ~200 MB download).

So on any cold start where composition wins the race, `rulesQaAnswerer` is `null` for the entire
process lifetime: `RulesQaStateHolder(null)` → `RulesQaUiState.Unavailable`, and the screen reports
itself unavailable no matter how long the user waits. Restarting the app "fixes" it, which is
exactly the shape of a bug that looks intermittent.

- [x] **Partial:** Make availability observable rather than sampled once — resolve the answerer lazily
      per ask, or key the `remember` on an initialisation `StateFlow`.
- [x] **Partial:** Test: an answerer that is null at composition and non-null later must become usable
      without an app restart.

*Evidence: `DefaultRulesQaAnswerer.android.kt` no longer gates on `isCactusInitialized()`, so the screen is
never permanently `Unavailable`. The `remember { … }` in `AppRoot.kt` is unchanged, but no longer matters
because the `actual` is unconditional.*

### P0-3 The availability gate and the route disagree

`defaultRulesQaAnswerer` gates on `isCactusInitialized()` **only**. But
`probeAvailableLocalVendors()` (`AiRoute.android.kt:6-15`) offers **ML Kit first**:

```kotlin
if (mlKit.status() is AiAvailability.Available) vendors.add(VendorRoute.MlKitPrompt(FAST))
if (isCactusInitialized())                      vendors.add(VendorRoute.CactusLocal())
```

Two consequences, both live:

1. On an AICore device where ML Kit is available but Cactus has not initialised, Rules Q&A reports
   **Unavailable** while a perfectly good local route exists.
2. When both are present the decider picks **ML Kit**, so `StructuredOutputRulesQaAnswerer` — whose
   prompts, token caps and JSON envelope were all tuned against Cactus — actually runs on Gemma Nano.
   The class name has been describing the wrong runtime.

- [ ] **Not done:** Gate the answerer on "any local vendor is available", not on Cactus specifically.
- [ ] **Not done:** Decide deliberately which runtime Rules Q&A should prefer, and record why.

*Evidence: Answerer is ungated, but nothing records which runtime Rules Q&A should prefer, and the class
name/KDoc still say Cactus. It is still open because the priority is unresolved.*

### P0-4 Six causes, one indistinguishable string

`RulesQaResult.FellBack` carries a typed `FallbackReason`, and `RulesQaStateHolder:35` throws it
away (`isFallback = true`). No log line reaches the user, and the six causes — no route, timeout,
validation, empty question, context failure, generation error — render identically. That is why
this took four attempts: **the UI could not distinguish "the model fumbled" from "nothing was
found", and neither could anyone reading a bug report.**

- [x] **Partial / Regressed:** Surface the reason in debug builds (the taxonomy already exists —
      `FallbackPresentation` in `:app` does this for the move coach; reuse it).
- [x] **Partial / Regressed:** Log the reason at warn with the question, so `adb logcat -s RulesQa`
      answers "which gate?".

*Evidence: `RulesQaUiState.Ready.fallbackReason` is now typed and `RulesQaScreen` reads `FallbackPresentation`.
But it renders `"Offline reference fallback"` for **every** reason including `Silent`, prints the literal
`"Silent"` instead of the cause, gates the suffix on `LocalProUnlockOverride` (a paywall signal), and there
is no warn log with the question at the orchestrator's fallback path.*

### P0-5 The retrieval floor sits below the routing gate

- [x] Run the lookup on `Decision.FallBack` and return `RulesQaGrounding.composeFromPassages(...)`
      when non-empty. Currently the `Decision.FallBack -> fallback(decision.reason)` arm combined with
      `if (isCactusInitialized()) vendors.add(...)` ensures lookup never runs while vendors list is empty.
- [x] Add a zero-vendor `commonTest`.

### P0-6 Debug diagnostics keyed off `LocalProUnlockOverride`

- [x] Use a dedicated debug signal instead of a paywall signal.
- [x] Honour `FallbackPresentation.Silent` with no chrome.
- [x] Render `reason.description` instead of the presentation name.
- [x] Add the missing warn log with the question.

---

## P1 — the requested runtime upgrades

### P1-1 Native Cactus tool calling (replaces the JSON-envelope prompt)

**Confirmed available** in `com.cactuscompute:cactus:1.4.1-beta` (read from the published sources
jar, not assumed):

```kotlin
data class CactusCompletionParams(..., val tools: List<CactusTool> = emptyList(),
                                       val forceTools: Boolean? = null)
data class CactusCompletionResult(..., val toolCalls: List<ToolCall>? = emptyList())
data class ToolCall(val name: String, val arguments: Map<String, String>)
fun createTool(name: String, description: String, parameters: Map<String, ToolParameter>): CactusTool
```

The SDK parses the call itself. That deletes `LOOKUP_ENVELOPE`, the `\}`-escaping Android/ICU
hazard, the `find()`-vs-`matchEntire()` "tolerate AI babble" patch, and the whole class of
malformed-JSON failures — replacing prompt archaeology with an API.

**Honest assessment of the value.** After PR #131 retrieval no longer depends on this turn at all;
it is a *refinement*. Tool calling makes a step that can no longer break the feature more reliable.
Worth doing for correctness and for the vendor-parity story, but it is **not** what unblocks the
user — P0 is.

- [x] **Partial:** Gate on `CactusModel.supports_tool_calling` from `getModels()`; fall back to the
      current prompt path when false. Do **not** assume `gemma3-270m` qualifies — verify on device
      and record the answer here.
- [x] **Partial:** `forceTools = true` for the lookup turn.
- [x] **Partial:** Keep the question-based lookup as the primary query regardless (P0 principle).

*Evidence: `CactusTextGenerator` maps `AiToolSpec` → `createTool`, sets `forceTools`, emits
`AiTokenOrFinal.ToolCall`, and resolves `supports_tool_calling` from `getModels()` inside
`ensureInitialized()`. The probe result is **never logged**, so the plan's open question is still
unanswerable from a device log; and because `warmup()` returns before the download completes, the
flag can still be `false` at the first ask.*

### P1-2 Structured answer envelope (the turn that users actually see)

> **Partly overtaken by events.** On-device logging confirmed the exact failure —
> `model wording refused (answer does not cite a retrieved passage id)` — i.e. the model produced a
> correct, in-budget answer and lost it purely on the citation format. Rather than build the envelope
> first, `RulesQaResponseValidator` now accepts an **uncited answer that is anchored** to a retrieved
> passage by content-word overlap, and derives the citation from that overlap. This mirrors
> `PositionChatValidator`, which had already concluded that overlap is the real grounding check and
> the id the weak one. Re-measure before building the envelope: it is now a quality upgrade over a
> working path, not a fix for a broken one.

The second turn used to require a literal `[draw-dead-position]` inside prose or
`RulesQaResponseValidator` rejected it. A 270M model will not do that reliably, so after #131 the
user usually saw the raw passage rather than a phrased answer. Correct, but flat.

Replace the demand with a schema:

```json
{"answer": "With only kings left neither side can force mate.", "cites": ["draw-dead-position"]}
```

Kotlin renders the citation, so the model never has to reproduce an id. This is the change that
raises answer *quality*, and it is the one **ML Kit can serve too** — `genai-prompt` is a structured
output API, not a tool-calling one, so this is the only path to parity across both Android runtimes.

- [ ] **Deferred:** Decode with `kotlinx.serialization`, matching the house rule in CLAUDE.md (no KSP
      schema compiler, plain JSON examples in the prompt).
- [ ] **Deferred:** Validate `cites` against the retrieved ids — a hallucinated id must fail closed to
      `RulesQaGrounding`, never render.
- [ ] **Deferred:** Keep `RulesQaGrounding` as the floor. The envelope raises the ceiling; it must not
      become a seventh way to lose an answer.

*Rationale: The answer turn still asks the model to reproduce `[id]` in prose. Defensible (the plan
itself downgraded this to a quality upgrade) but must be marked deferred rather than left ambiguous.*

### P1-3 The seam change both need

`AiGenerationRequest` has no `tools` field and `AiTokenOrFinal` no tool-call channel
(`OnDeviceTextGenerator.kt`). Both additions are default-valued and therefore source- and
binary-compatible for the React Native consumer and for the four runtimes that ignore them.

```kotlin
data class AiGenerationRequest(..., val tools: List<AiToolSpec> = emptyList())
sealed interface AiTokenOrFinal { ...; data class ToolCall(val name: String, val arguments: Map<String, String>) }
```

- [x] **Partial:** `AiToolSpec` must be vendor-neutral — no Cactus types in `commonMain`, same rule that
      keeps RevenueCat out of it.
- [x] **Partial:** LiteRT-LM (desktop/wasm), Foundation Models (iOS) and `UnsupportedTextGenerator` ignore
      `tools` and never emit `ToolCall`; assert that in `commonTest`.
- [x] **Partial:** Land with the `on-device-ai-v*` version bump already queued for #129/#130.

*Evidence: `AiToolSpec`/`AiToolParameter`/`AiTokenOrFinal.ToolCall` landed vendor-neutrally in
`OnDeviceTextGenerator.kt`; `is ToolCall -> {}` arms added across `AntiRepetitionGuard`,
`DefaultAiCoachOrchestrator`, `DefaultGameSummaryOrchestrator`, `EvalMain`, `EvalLiteRtDriver`.
Missing: the `commonTest` assertion that LiteRT-LM / Foundation Models / `UnsupportedTextGenerator`
never emit `ToolCall`, and the `on-device-ai-v*` bump.*

### P1-4 Tool-calling capability belongs on the seam

- [x] Move the check `(generator as? CactusTextGenerator)?.supportsToolCalling` (which violates P1-3's
      vendor-neutrality) to `OnDeviceTextGenerator.supportsTools` (default `false`).
- [x] Override it in `CactusTextGenerator` and resolve it during `awaitWarmup()` so the first ask after
      a cold start is not falsely `false`.
- [x] Log the probe result to answer the document's own open question.

### P1-5 The 20 s budget is spent twice

- [x] Split the 20 s budget (refinement gets a small slice, answer gets the remainder) or track a shared
      deadline. Both `withTimeoutOrNull` calls currently use `rulesQaOffline.latencyBudget.completeMs`,
      giving ~40 s worst case.

### P1-6 `parseLookupQuery` brace-slicing is fragile

- [x] Harden `parseLookupQuery`: `substringAfter("{")`/`substringBeforeLast("}")` mis-parses nested
      objects and concatenates two objects; the `output.indexOf("{") == -1` branch is dead. Prescribe a
      scanned balanced-object span or a non-greedy regex.

---

## P2 — cleanup

### P2-1 `androidMain` has no test source set

`StructuredOutputRulesQaAnswerer` — the file containing both original failure gates — has **no
tests, and nowhere to put them**. Everything testable was retrieval, so retrieval is what got tested
and tuned while the untested choreography around it stayed broken.

- [x] **Done:** Add `androidUnitTest` to `:onDeviceAi`, or keep pushing decisions into `commonMain` where
      `commonTest` reaches them. (via `androidHostTest` target in `onDeviceAi/build.gradle.kts` and
      `StructuredOutputRulesQaAnswererTest.kt`)
- [x] **Done:** Cover, with a fake generator: model returns prose / empty / malformed JSON / a hallucinated
      id / never returns. Each must still answer from the corpus. (Covered by the 4 new tests)

*Evidence: `onDeviceAi/build.gradle.kts` adds an `androidHostTest` target; `:ondeviceai:testAndroidHostTest`
runs green (4 new tests, picked up by `:ondeviceai:check`). Coverage is incomplete — see backlog.*

### P2-2 `MlKitPromptGenerator` drops three request fields

`generate()` ignores `maxOutputTokens`, `temperature` and `stopSequences`
(`MlKitPromptGenerator.kt:56-76`). On the ML Kit route the 600-char validator cap is therefore
enforced only after the fact, so a rambling answer is silently downgraded to passage text.

- [x] **Partial:** Map the fields onto `generationConfig`, or document why they cannot be.
- [x] **Partial:** `AiInferenceMetrics(0L, 0L, fullText.length, …)` reports **characters as tokenCount**; the
      benchmark JSONL in `docs/benchmarks/on-device-ai/` consumes that field.

*Evidence: `temperature` / `maxOutputTokens` now map onto the generation config. `stopSequences` still
dropped with no comment; `AiInferenceMetrics` still reports characters as `tokenCount`.*

### P2-3 Docs and naming drift

- [ ] Update stale `StructuredOutputRulesQaAnswerer` KDoc ("not native function calling") and class name.
- [ ] Update `CLAUDE.md`: it still documents Rules Q&A as gated on `isCactusInitialized()`. Note: drift is
      recorded here rather than fixed, to keep the change set to one file.
- [ ] Fix unused imports (`VendorRouteExecutor`, `AiContextSnapshot`, `AiUserSetting`) vs. fully-qualified
      inline usages (`withTimeoutOrNull`, `AiToolSpec`, `FallbackPresentation`, `LocalProUnlockOverride`) in
      the answerer and `RulesQaScreen`.

### P2-4 Test-dependency and source-set hygiene

- [ ] Move `io.mockk:mockk:1.13.10` hardcoded instead of `gradle/libs.versions.toml`.
- [ ] Rename `src/androidTest/kotlin` to `src/androidHostTest/kotlin` (collides with AGP's instrumented-test
      convention) and drop the `kotlin.srcDir` line.
- [ ] Fill test gaps: hallucinated-id, empty-output, and zero-vendor cases.
- [ ] Fix test name: `test invalid json envelope falls back to question passages` does not assert what its
      name claims.

### P2-5 Unrelated changes to split out

- [ ] Split out the Gradle wrapper bump (9.3.1 → 9.7.0).
- [ ] Split out the Move Coach prompt/assessment rewrite (`MoveCoachRequest.moveClassName`/`motifs`/`centipawnLoss`)
      into its own commit with an eval note, as it changes coach output for every user.

### P2-6 Published-API version bump and release note

- [ ] Bump `on-device-ai-v*`.
- [ ] Add a release note that an exhaustive `when` over `AiTokenOrFinal` breaks for consumers because
      `:onDeviceAi` is published.

---

## Verification protocol

**Automated:**
`:ondeviceai:testAndroidHostTest` exists and is green (4 new tests, picked up by `:ondeviceai:check`).
Note that `:ondeviceai:check :app:check` was **not** run in full, and none of it exercises the device path.

**Device Protocol (outstanding):**
On device, `adb uninstall io.github.ber4444.chess` first — the P0-2 race only appears on a cold start:

1. **First launch, model still downloading.** Open Rules, ask the question. Must answer from the corpus
   (or state it is still preparing) — never "Unavailable", never the generic fallback. *(Still fails until
   P0-5 lands).*
2. **The reported question.** "Game is a draw when only kings remain?" must return the dead-position rule.
   Also try "only kings left" and "game ends in draw if only two kings remain".
3. **Which gate.** `adb logcat -s RulesQa` must name the reason whenever a fallback renders.
4. **Airplane mode.** LOCAL_ONLY must behave identically; nothing here may touch the network.
5. **Tool-call support.** Log `supports_tool_calling` for the loaded model and record it in P1-1 above —
   the whole tool-calling branch is conditional on it. *(Probe implemented, result unlogged).*
6. **AICore vs not.** Repeat on a device with ML Kit available and one without; P0-3 means these are
   genuinely different code paths.

## Suggested order

1. Retrieval floor above the routing decision + zero-vendor test.
2. Capability on the seam resolved at warmup.
3. Real debug signal, honour `Silent`, log the reason.
4. Split the budget and harden the parser.
5. Split the unrelated commits, refresh `CLAUDE.md` and the KDoc, tick or explicitly defer the remaining boxes.

## Open questions

- Does `gemma3-270m` report `supports_tool_calling = true`? The probe is implemented but the result is unlogged.
- Should Rules Q&A prefer ML Kit (more capable, narrow device support) or Cactus (universal, 270M)? The
  answerer is ungated, making this choice urgent.
- Is a 20 s budget right at all once retrieval is outside it? (Superseded by P1-5's double-spend).
