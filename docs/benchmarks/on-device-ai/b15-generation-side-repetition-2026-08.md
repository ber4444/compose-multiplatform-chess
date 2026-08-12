# B15 generation-side repetition control: no surviving runtime exposes one (2026-08-12)

Status: **Investigated and closed.** No code path in this doc is theoretical — every claim below is
read from the actual pinned SDK (decompiled `.class`/`.aar` for the JVM/Android artifacts, the exact
tagged source for the JS one, and public framework docs for Foundation Models), not from a model card
or a GitHub README.

## Why this exists

`MoveCoachResponseValidator.deduplicateSentences` (post-hoc, #114) and `withAntiRepetitionGuard`
(post-hoc, cuts a completion when an n-gram reoccurs, #125) are the *validator* and *generation*
halves that were believed shipped. The plan re-sequenced a third piece here: stopping repetition at
**sampling time** — a repetition/frequency penalty or logit-level n-gram block passed into the
model's own decode loop — rather than trimming the delivered text afterwards. This is the
investigation of whether that third piece is buildable at all, runtime by runtime.

## The four surviving runtimes, checked against their actual pinned API

| Runtime | Where | API checked | Exposes sampling-time repetition control? |
|---|---|---|---|
| Apple Foundation Models | `iosMain` (`FoundationMoveCoach.swift`) | `GenerationOptions` / `GenerationOptions.SamplingMode` (public framework, iOS 26) | **No.** `sampling` is `.greedy`, `.random(top:seed:)` (top-k), or `.random(probabilityThreshold:seed:)` (top-p); plus `temperature`, `maximumResponseTokens`, and (iOS 27 beta) `toolCallingMode`. No repetition/frequency penalty field exists in the framework. |
| LiteRT-LM (desktop) | `desktopMain` (`LitertLmTextGenerator.kt`) | `com.google.ai.edge.litertlm.SamplerConfig`, `litertlm-jvm:0.14.0` (the exact pinned dependency — decompiled the real jar, not read a changelog) | **No.** Constructor is `(topK: Int, topP: Double, temperature: Double, seed: Int)`. No fifth parameter of any kind. |
| LiteRT-LM for Web | `wasmJsMain` (`LitertLmWasmTextGenerator.kt`) | `@litert-lm/core`'s `SamplerParameters` TS interface, at the **exact tag `v0.14.0`** matching the CDN URL this project loads (`cdn.jsdelivr.net/npm/@litert-lm/core@0.14.0/+esm`) | **No.** `{ type?: SamplerType /* TOP_K \| TOP_P \| GREEDY */, k?, p?, temperature?, seed? }`. Same four knobs as the JVM binding, no fifth. |
| ML Kit GenAI Prompt API | `androidMain` (`MlKitPromptGenerator.kt`) — wired but dormant, reports `Unavailable` on every device tested (see `android-model-latency-2026-08.md`) | `com.google.mlkit.genai.prompt.GenerateContentRequest.Builder`, `genai-prompt:1.0.0-beta4` (decompiled the real AAR) | **No.** Exposes `temperature`, `seed`, `topK`, `candidateCount`, `maxOutputTokens`, `promptPrefix`, `cachedContextName`, `enableThinking`. No `topP`, no repetition/frequency penalty, no stop-sequence setter (the existing code comment about the missing stop-sequence setter was already correct). |

**Conclusion: zero of the four expose a repetition or frequency penalty, or an n-gram block, at the
sampling level.** All four give some combination of top-k / top-p / temperature / seed — which
changes *how much* the model explores, not a targeted penalty against reusing a token or phrase it
already produced. Turning temperature up is not a substitute: it was tried informally during the
Android catalog benchmark (`android-model-latency-2026-08.md`) and traded "repeats itself" for
"drifts off the position," which is worse for a grounded coach.

`AntiRepetitionGuard.kt`'s own doc comment already said this — *"no local runtime exposes a sampler
hook through its Kotlin API"* — written when #125 shipped. This investigation is what confirms that
sentence against the actual SDKs rather than assuming it still holds four features later, and it
turned up one place where the codebase was quietly disagreeing with its own conclusion (next
section).

## Two bugs found while checking, both fixed here

1. **A field that lied about doing something.** `AiGenerationRequest.repetitionPenalty` carried the
   doc comment *"Sampler-level repetition penalty, for the runtimes whose API exposes one (wasm
   today)"* and `LitertLmWasmTextGenerator` dutifully sent it to the worker as
   `"repetitionPenalty" to request.repetitionPenalty.toString()`. But `workerScript()`'s
   `msg.type === 'generate'` handler never reads `msg.repetitionPenalty` — it destructures
   `modelUrl`, `systemPrompt`, and `userPrompt` only. The value was serialized, shipped across the
   `postMessage` boundary, and silently dropped. Per the table above, there was never anything on the
   other end to receive it: `@litert-lm/core@0.14.0` has no such sampler field at all, so the comment
   was never true. Removed the field (a published-API removal — see the PR description for the
   version bump) and the dead postMessage key.
2. **The post-hoc guard wasn't actually applied everywhere.** iOS (`FoundationModelsBridge.kt`),
   desktop (`LitertLmTextGenerator.kt`), and wasm (`LitertLmWasmTextGenerator.kt`) all pipe their
   `generate()` flow through `.withAntiRepetitionGuard(...)`. `MlKitPromptGenerator.kt` (Android)
   did not — the n-gram/stop-sequence truncation that is the actual shipped feature here was only
   wired on three of the four runtimes. Fixed by adding the same `.withAntiRepetitionGuard(...)`
   call. This has not been observable on-device (ML Kit reports `Unavailable` on every device tested
   per `android-model-latency-2026-08.md`), but it is one line and it closes the same class of gap
   this investigation exists to find, so it is fixed rather than left for whenever ML Kit next gets
   checked.

## A third thing found, fixed, but not about repetition specifically

While checking the wasm request path end to end, `LitertLmWasmInterop.kt`'s worker script turned out
to read only `msg.modelUrl`, `msg.systemPrompt`, and `msg.userPrompt` from the `postMessage` payload
— `msg.temperature` and `msg.maxTokens` were also serialized and sent (same pattern as the dead
`repetitionPenalty` key above) and also silently dropped, so every wasm generation ran on
`@litert-lm/core`'s engine defaults regardless of what `AiGenerationRequest` asked for. Fixed by
building a real `sessionConfig: { samplerParams: { temperature }, maxOutputTokens }` in the worker's
`conversationConfig` (the `SamplerParameters`/`SessionConfig` shape from the table above — the only
fields that exist to set). This is config plumbing, not a repetition finding, but it was found by the
same "read the actual message handler, not the call site" check this investigation is built on, and
leaving it broken while writing up "here's what each runtime's config surface actually does" would
have been dishonest by omission.

**Not fixed, same flavor, left alone:** `FoundationModelsBridge.generate()` (the Kotlin protocol
`FoundationModelsBridge.kt` implements) doesn't carry `temperature` across the Kotlin/Swift boundary
at all — `FoundationMoveCoach.swift` hardcodes `GenerationOptions(temperature: 0.2, ...)`. Same
category of gap as the wasm one, but fixing it means changing a cross-language interop signature
(the Kotlin `interface` and its Swift `NSObject` adopter together), which is a larger, separately
verifiable change and not part of what this investigation was asked to do. Left as a known gap for
whoever next touches `FoundationMoveCoachBridge.swift`.

## What is *not* being built here

No new config knob. The task set an explicit bar for this investigation — don't invent a field to
have something to ship — and the honest result clears that bar in the other direction: the post-hoc
`withAntiRepetitionGuard` (n-gram cut + stop sequences) plus `MoveCoachResponseValidator
.deduplicateSentences` **is** the whole repetition-control feature, on every runtime this project
ships to. B15 is closed in `~/Downloads/chess plan.md` on that basis.

## Reproducing

```bash
# LiteRT-LM JVM sampler shape (desktop):
unzip -p ~/.gradle/caches/modules-2/files-2.1/com.google.ai.edge.litertlm/litertlm-jvm/0.14.0/*/litertlm-jvm-0.14.0.jar \
  com/google/ai/edge/litertlm/SamplerConfig.class > /tmp/SamplerConfig.class
javap /tmp/SamplerConfig.class   # -> (topK: Int, topP: Double, temperature: Double, seed: Int)

# LiteRT-LM for Web sampler shape (wasm), at the exact pinned tag:
curl -sL https://raw.githubusercontent.com/google-ai-edge/LiteRT-LM/v0.14.0/js/packages/core/src/session_config.ts \
  | grep -A6 'interface SamplerParameters'

# ML Kit GenAI Prompt API request builder (Android):
unzip -p ~/.gradle/caches/modules-2/files-2.1/com.google.mlkit/genai-prompt/1.0.0-beta4/*/genai-prompt-1.0.0-beta4.aar classes.jar > /tmp/genai.jar
unzip -p /tmp/genai.jar 'com/google/mlkit/genai/prompt/GenerateContentRequest$Builder.class' > /tmp/Req.class
javap /tmp/Req.class
```

Apple's `GenerationOptions`/`GenerationOptions.SamplingMode` is checked against the public
`developer.apple.com/documentation/foundationmodels` reference rather than a local artifact — there
is no on-disk SDK jar/npm package to decompile for a system framework.
