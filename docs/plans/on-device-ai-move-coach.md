# Plan: Cross-platform on-device AI move coach

**Status:** proposed
**Scope:** new KMP on-device AI module, Android/iOS inference actuals, shared routing policy, chess-app UI wiring, benchmark publication tables
**Target feature:** Stockfish chooses the move; an on-device model explains it as a concise natural-language chess coach, fully offline for the move-coach flow.
**Verified against:** branch `3d`, repository source-set hierarchy, official platform docs current as of 2026-06-16, and the companion article "Intelligent Clients: The 2026 AI-Native Mobile Stack".

---

## 1. Product shape

Add a "Move Coach" panel to the chess app:

1. The existing `ChessEngine`/Stockfish path still chooses Black's move and can also evaluate candidate moves.
2. The app builds a small, deterministic explanation context from the current position:
   - FEN before the move
   - engine best move in UCI and SAN-like display text
   - shallow engine evaluation before/after if available (the *after* value is a second engine call per coached move — bound its depth or skip it on slower devices)
   - tactical tags produced by deterministic code when cheap: capture, check, castle, promotion, material swing, defended/undefended piece
3. A local language model turns that context into one short explanation:
   - "Stockfish likes ... because ..."
   - maximum 2 sentences (kept consistent with the §8.3 validator)
   - no made-up engine depth, ratings, or opening names unless those fields are present in the input
4. If the local model is unavailable, slow, overheated, quota-blocked, or fails structural validation, the app shows a deterministic fallback explanation built from the same tags.

The first shipped move-coach flow is **offline-only**. The cross-platform routing policy still models cloud routes, because the architecture should be ready for future non-sensitive AI requests, but chess move explanations are classified `LOCAL_ONLY`, so the policy must choose on-device or fallback, never cloud.

### Product pitch: an Intelligent Client, not a chatbot

Frame the feature as a concrete "Intelligent Client" example: the mobile app is no longer just displaying backend data or passing a prompt to a server. It owns a local decision loop:

- deterministic chess engine decides the best move
- KMP business logic packages the smallest useful explanation context
- route policy decides where inference is allowed to run for this request
- platform inference actuals translate the request into Android/iOS model APIs
- validation keeps the language model grounded in app-provided facts
- deterministic fallback preserves the product experience when AI is unavailable

That pitch fits this repo better than a generic "AI chess app" story. The model does not become the game engine; the client becomes smart enough to choose when and how a model may explain the engine.

---

## 2. Source facts (and roadmap assumptions) this plan depends on

> Bullets with a source URL are verified against primary platform docs as of 2026-06-16. Bullets tagged **(roadmap assumption)** come from the companion 2026-stack article rather than primary docs — treat them as forward-looking and re-verify before any reach the published Medium piece.

Android:

- ML Kit GenAI APIs run on-device through AICore/Gemini Nano, process input/output locally, work without reliable internet after model availability, and add no per-call server cost. They also enforce per-app quota and foreground-only usage. Source: <https://developers.google.com/ml-kit/genai>
- ML Kit Prompt API is beta, supports custom text/multimodal prompts, exposes `checkStatus()`, downloadable/available/unavailable states, streaming, `warmup()`, and optional generation parameters. Source: <https://developers.google.com/ml-kit/genai/prompt/android/get-started>
- LiteRT is Google's on-device ML/GenAI framework. LiteRT-LM is a cross-platform language-model runtime with CPU/GPU and NPU paths. Its NPU guide lists `.litertlm` Gemma model artifacts and vendor paths for Google Tensor, Qualcomm, MediaTek, and Intel. Source: <https://developers.google.com/edge/litert/next/litert_lm_npu>
- LiteRT `CompiledModel` has an Android Kotlin API for accelerator-first inference with CPU/GPU/NPU options. Source: <https://developers.google.com/edge/litert/next/android_kotlin>
- Google AI Edge Portal benchmarks LiteRT models across a large fleet of Android devices and reports metrics such as average latency, peak memory, initialization time, hardware specs, accelerator allocation, and device-level variation. It is in private preview for allowlisted Google Cloud customers. Source: <https://developers.google.com/edge/ai-edge-portal>
- **(roadmap assumption)** As of the 2026 mobile stack described in the companion article, Android's system-model path is Gemini Nano in AICore via ML Kit GenAI, while custom/fine-tuned model paths can use LiteRT-LM, ExecuTorch, or ONNX. LiteRT is the preferred Google-aligned path for this plan, and Firebase ML is a candidate model-delivery mechanism for reducing binary size and keeping models updated.
- Optimized model availability matters: the spike should check Hugging Face for LiteRT/ONNX/ExecuTorch optimized artifacts and, where needed, verify export from PyTorch via `optimum-cli export tflite`, `onnx`, or `executorch`.

iOS:

- Apple Foundation Models framework gives Swift apps access to the on-device model behind Apple Intelligence. Apple describes it as on-device, offline-capable, built into the OS, and not increasing app size. Source: <https://developer.apple.com/videos/play/wwdc2025/286/>
- The WWDC25 overview describes a 3B-parameter, 2-bit quantized device-scale model and explicitly frames it for summarization, extraction, classification, and content generation rather than world knowledge or advanced reasoning. The move coach fits because chess facts come from Stockfish and deterministic app context; the model only explains. Source: <https://developer.apple.com/videos/play/wwdc2025/286/>
- Foundation Models supports availability checks, structured/guided generation, streaming, tool calling, stateful sessions, and error handling for guardrails, unsupported language, and context-window issues. Source: <https://developer.apple.com/videos/play/wwdc2025/286/>
- **(roadmap assumption — not in the cited WWDC25 source; verify before publishing)** As of the 2026 mobile stack described in the companion article, Foundation Models is the LLM path and Core ML remains the custom/fine-tuned model path. AFM 3 adds image input, AFM 3 Core Advanced is a more capable sparse on-device option on the most capable Apple silicon, and the public `LanguageModel` protocol makes `LanguageModelSession` provider-swappable across the local model, Private Cloud Compute, and third-party frontier providers.

Orchestration:

- Koog is JetBrains' Kotlin AI-agent framework. Its docs describe KMP deployment across JS, WasmJS, Android, and iOS targets; its repository README describes multiplatform agent support, LLM switching/rerouting, graph workflows, streaming, tool usage, tracing, and available providers. Source: <https://docs.koog.ai/> and <https://github.com/JetBrains/koog>
- Firebase AI Logic is a candidate mobile-native serverless/hybrid route with App Check protections; Cactus and Xybrid are additional hybrid-inference options to evaluate for non-`LOCAL_ONLY` requests.
- Cloudflare Agents are a candidate backend orchestrator for heavier multi-step workflows where wall-clock inference waiting should not dominate CPU-time billing.
- AppFunctions allows apps to become on-device MCP servers, and A2UI-style generated UI is a future direction for agent-driven widgets. These are not required for the first move coach, but they are relevant future extension points for exposing chess analysis or coach actions beyond this app.

Model identity note:

- Android can implement the literal "Gemma explains" path through LiteRT-LM with Gemma `.litertlm` artifacts. ML Kit GenAI uses Gemini Nano. iOS uses Foundation Models/AFM through Apple's API, including the 2026 provider-swappable `LanguageModel` direction. The cross-platform product language should be "on-device move coach"; Android-specific copy may say Gemma when that route is active.

---

## 3. Module shape

Add a small KMP module:

```text
:onDeviceAi
  commonMain
  androidMain
  iosMain
  commonTest
  androidUnitTest / androidDeviceTest
  iosSimulatorArm64Test
```

Do not put this directly into `:app` at first. The chess app should consume a narrow API and stay testable with fakes. The module owns:

- semantic request/response models
- routing policy
- inference capability detection
- platform inference actuals
- prompt rendering
- structured response validation
- benchmark result schema

The app owns:

- chess-specific context extraction
- UI state
- deterministic fallback explanation
- when to show and cancel coach requests

---

## 4. Common API

### 4.1 Semantic model

```kotlin
enum class PrivacyClass {
    LOCAL_ONLY,        // never leaves device
    USER_PRIVATE,      // cloud only with explicit feature consent
    PUBLIC_OR_SYNTHETIC
}

data class LatencyBudget(
    val firstTokenMs: Long,
    val completeMs: Long,
)

data class CostBudget(
    val maxUsdCents: Double,
)

data class AiRoutePolicy(
    val privacyClass: PrivacyClass,
    val latencyBudget: LatencyBudget,
    val costBudget: CostBudget,
    val allowCloud: Boolean,
    val requireOffline: Boolean,
)

data class MoveCoachRequest(
    val fenBefore: String,
    val bestMoveUci: String,
    val bestMoveDisplay: String,
    val sideToMove: String,
    val evaluationBeforeCp: Int?,
    val evaluationAfterCp: Int?,
    val deterministicTags: List<String>,
    val policy: AiRoutePolicy = AiRoutePolicies.moveCoachOffline,
)

data class MoveCoachExplanation(
    val headline: String,
    val explanation: String,
    val confidence: ExplanationConfidence,
    val route: AiRoute,
    val metrics: AiInferenceMetrics,
)

// Result of AiCoachOrchestrator.explainMove — mirrors the MoveCoachUiState
// outcomes in §8.1 (Ready / Fallback / Error).
sealed interface MoveCoachResult {
    data class Success(val explanation: MoveCoachExplanation) : MoveCoachResult
    data class FellBack(val text: String, val reason: String) : MoveCoachResult
    data class Failed(val message: String) : MoveCoachResult
}
```

### 4.2 Inference facade

Use interfaces, not raw `expect` calls, for testability and lifecycle ownership:

```kotlin
interface OnDeviceTextGenerator {
    suspend fun status(): AiAvailability
    suspend fun warmup()
    fun generate(request: AiGenerationRequest): Flow<AiTokenOrFinal>
    suspend fun close()
}

interface AiCoachOrchestrator {
    suspend fun explainMove(request: MoveCoachRequest): MoveCoachResult
}
```

Use `expect` only at the leaf where platform code creates the default binding:

```kotlin
expect fun defaultOnDeviceTextGeneratorFactory(): OnDeviceTextGeneratorFactory
```

This keeps common code fakeable while still allowing `androidMain` and `iosMain` to translate to platform SDKs.

---

## 5. Routing policy

The routing policy is common Kotlin and should be unit-tested exhaustively.

### Inputs

- `PrivacyClass`
- `LatencyBudget`
- `CostBudget`
- device/network availability
- model availability
- current thermal/performance state when exposed by the platform
- app foreground/background state
- benchmark-derived device profile
- user setting: "offline only", "prefer local", "allow cloud"

### Routes

```kotlin
sealed interface AiRoute {
    data object OnDevice : AiRoute
    data object Cloud : AiRoute
    data class Fallback(val reason: String) : AiRoute
}
```

### Required policy behavior

| Request class | Local available | Local unavailable | Cloud allowed | Result |
|---|---:|---:|---:|---|
| Move coach (`LOCAL_ONLY`, `requireOffline=true`) | yes | no | any | on-device or fallback only |
| User-private analysis | yes | no | yes + consent | on-device if budget passes, otherwise cloud |
| Public/synthetic prompt | yes | no | yes | choose cheapest route meeting latency |
| Any request with `maxUsdCents=0` | yes | no | yes | on-device or fallback |
| Any request while app backgrounded on Android ML Kit | any | any | any | no ML Kit call; fallback or defer |

The move coach's default policy:

```kotlin
object AiRoutePolicies {
    val moveCoachOffline = AiRoutePolicy(
        privacyClass = PrivacyClass.LOCAL_ONLY,
        latencyBudget = LatencyBudget(firstTokenMs = 900, completeMs = 3500),
        costBudget = CostBudget(maxUsdCents = 0.0),
        allowCloud = false,
        requireOffline = true,
    )
}
```

> The `900`/`3500` ms budget is **provisional** — chosen before any device measurement. Cold NPU/model init alone can exceed `firstTokenMs = 900`. Treat these as placeholders until §9.2's benchmark-derived thresholds replace them, and do not quote them in the article as if they were measured.

### Koog integration

Use Koog for the orchestration shape when it is mature on the target artifacts used by this repo:

- a graph node gathers chess context
- a policy node chooses `OnDevice`, `Cloud`, or `Fallback`
- an on-device node calls `OnDeviceTextGenerator`
- a validation node checks structured output and factual constraints
- a fallback node returns deterministic text
- tracing records route, latency, token count, and fallback reason

If Koog's Android artifact availability or binary size is not acceptable at implementation time, keep the same common policy and expose it through a small hand-written orchestrator first. The Koog graph can remain the server/JVM/test reference until mobile artifacts are verified.

### Hybrid/cloud extension points

The offline move coach must not use cloud inference, but the policy should stay general enough to support future non-sensitive requests. Candidate cloud routes to evaluate later:

- Firebase AI Logic for mobile-native client-to-model calls, App Check protection, and hybrid on-device/cloud inference policies
- Cactus or Xybrid for alternate hybrid local/cloud inference routing
- Private Cloud Compute or provider-backed Foundation Models sessions on Apple platforms through the 2026 `LanguageModel` protocol when appropriate for the request class
- a backend orchestrator, with Cloudflare Agents as the first candidate, for heavy multi-step workflows, tool access, or large context windows

Keep those routes behind explicit user settings, privacy classification, cost budgets, and audit traces. They are extension points, not dependencies of the first coach milestone.

---

## 6. Android implementation

### 6.1 Preferred Android path: LiteRT-LM + Gemma

Use LiteRT-LM for the literal Gemma coach:

- model: smallest instruction-tuned Gemma `.litertlm` artifact that meets output quality and memory budgets
- prompt: text-only, short context, low max output tokens
- streaming: yes, if JNI/Kotlin binding supports it cleanly
- accelerator preference: NPU when supported and benchmarked, GPU/CPU fallback only if latency and thermal budgets pass

Risks:

- `.litertlm` assets are SoC-specific for NPU acceleration in the current LiteRT-LM guide.
- Packaging Gemma may materially increase app size unless delivered dynamically.
- Runtime library packaging may require native assets and ABI-specific build work.

### 6.1.1 Model delivery spike

The recent "Intelligent Clients" article raises the right delivery question: on-device AI is not only an inference API choice, it is also a packaging and update strategy.

Spike these Android options before committing to a default:

| Delivery path | App size | Update cadence | Offline after setup | Notes |
|---|---:|---|---|---|
| AICore/ML Kit system model | low | OS/Play services managed | yes after model availability | Gemini Nano path, not Gemma |
| bundled LiteRT-LM Gemma asset | high | app release | yes | simplest control, worst binary growth |
| Play Feature / asset delivery | medium base APK | Play-managed | yes after install | good for optional coach panel |
| Firebase ML / remote model delivery | low base APK | remote model updates | yes after download | verify LiteRT-LM/Gemma compatibility and policy |
| Hugging Face optimized artifact | varies | model-source dependent | yes after packaging/download | check LiteRT, ONNX, and ExecuTorch variants |
| PyTorch export pipeline | varies | app-owned conversion | yes after packaging/download | verify `optimum-cli export tflite` / ONNX / ExecuTorch quality |
| no local model | none | none | fallback only | always-supported baseline |

The output of this spike should be a written decision plus APK/IPA size impact, install-time behavior, and offline-after-download behavior.

### 6.2 Android fallback path: ML Kit GenAI Prompt API

Use ML Kit Prompt API when:

- Gemma/LiteRT-LM is not bundled yet
- the user is on a supported Gemini Nano device
- the feature can accept "local model explains" rather than "Gemma explains"

Implementation notes:

- Add `com.google.mlkit:genai-prompt:1.0.0-beta2` (verify the current beta at implementation — the version churns) only in `androidMain`/Android wrapper experiments.
- Call `checkStatus()` and handle `AVAILABLE`, `DOWNLOADABLE`, `DOWNLOADING`, and `UNAVAILABLE`.
- Call `warmup()` opportunistically when the coach panel opens or before the engine starts thinking.
- Handle `BUSY`, battery quota, and foreground-only constraints with backoff/defer/fallback.
- Use streaming for perceived latency, but cap output aggressively.

### 6.3 Android benchmark gate

Before publishing or shipping beyond an experiment:

1. Upload the exact LiteRT model/config to Google AI Edge Portal.
2. Select target device lists by tier, RAM, chipset, and NPU support.
3. Run CPU, GPU, and NPU configurations where applicable.
4. Export results and commit summarized variance tables under `docs/benchmarks/on-device-ai/`.
5. Block enabling Gemma by default unless p90 and thermal behavior meet the route budgets.

---

## 7. iOS implementation

Use Foundation Models from Swift and expose it to Kotlin/Native through the existing iOS app bridge pattern. The 2026 stack makes the provider choice more interesting than a single hard-coded local session: `LanguageModelSession` can target the local AFM model, Private Cloud Compute, or provider-backed frontier models through the public `LanguageModel` protocol. The move coach route still chooses local/offline only, but the facade should be shaped so future request classes can swap providers without changing common KMP callers.

### 7.1 Swift facade

Add an iOS Swift class in `iosApp`:

```swift
final class FoundationMoveCoach {
    func availability() async -> FoundationCoachAvailability
    func warmup() async
    func explain(_ request: FoundationMoveCoachRequest) async throws -> FoundationMoveCoachResponse
}
```

Use `LanguageModelSession`, short instructions, structured output if available, and streaming snapshots when useful. Keep custom instructions static; never interpolate raw user text into instructions.

Model selection inside the facade:

- default move coach: local Foundation Models/AFM session
- multimodal future coach request: AFM 3 image-capable path, if the UI later includes board screenshots or visual annotations
- high-complexity non-local request: provider-swappable `LanguageModel` session only when the common route policy permits cloud/private-cloud inference

### 7.2 Kotlin/Native bridge

In `iosMain`, implement `OnDeviceTextGenerator` by calling the Swift facade. Keep Swift model types tiny and convert at the boundary. The Kotlin common API should never expose Swift or Foundation Models types.

### 7.3 iOS benchmark gate

AI Edge Portal is Android/LiteRT-focused, so iOS needs a companion benchmark path:

- Instruments Foundation Models profiling template for model request latency.
- device matrix: at minimum one A-series iPhone and one M-series iPad/Mac target if supported.
- measure cold start, warm first token, complete response, memory pressure, and thermal state before/after.
- commit tables beside Android results with the same schema.

### 7.4 Custom-model escape hatch

Foundation Models is the preferred iOS LLM path for this feature because it is system-provided and avoids shipping a model in the app. If the product later requires a specific custom or fine-tuned model, treat that as a separate Core ML spike:

- convert candidate model to Core ML format
- verify Neural Engine/GPU/CPU placement
- measure app size and memory pressure
- compare quality and latency against Foundation Models
- keep the same `OnDeviceTextGenerator` common interface

Do not mix this into M5 unless Foundation Models cannot meet the move-coach requirements.

---

## 8. Chess-app integration

### 8.1 View model state

Add fields to `GameUiState` or a small `MoveCoachUiState`:

```kotlin
sealed interface MoveCoachUiState {
    data object Hidden : MoveCoachUiState
    data object Unavailable : MoveCoachUiState
    data class Loading(val move: String) : MoveCoachUiState
    data class Streaming(val move: String, val text: String) : MoveCoachUiState
    data class Ready(val explanation: MoveCoachExplanation) : MoveCoachUiState
    data class Fallback(val text: String, val reason: String) : MoveCoachUiState
    data class Error(val message: String) : MoveCoachUiState
}
```

### 8.2 Trigger timing

Best first trigger:

- after Stockfish returns Black's move and before/while the move animation plays
- cancel stale coach jobs if the game resets, promotion is pending, or another move starts
- never block move application on language generation

### 8.3 Prompt contract

The prompt must be small and grounded:

```text
You are a chess coach for a casual player.
Explain only the provided move. Do not name openings or engine depth unless present.
Use at most 2 sentences.

Position FEN: {fen}
Best move: {bestMoveDisplay} ({bestMoveUci})
Side to move: {sideToMove}
Evaluation before: {evaluationBefore}
Evaluation after: {evaluationAfter}
Tags: {deterministicTags}
```

Validation:

- output length <= 360 chars
- mentions the move or moved piece
- no forbidden phrases: "I think Stockfish", "probably depth", fake opening names (note: this list is English-only — a localized coach needs a per-locale variant of the forbidden-phrase/grounding checks)
- if validation fails once, retry with stricter prompt; if it fails again, fallback

---

## 9. Benchmark artifacts and variance tables

Create:

```text
docs/benchmarks/on-device-ai/
  android-ai-edge-portal-summary.md
  android-ai-edge-portal-raw-export.csv        # if allowed by preview terms
  ios-foundation-models-instruments-summary.md
  move-coach-benchmark-schema.md
```

### 9.1 Required table schema

| Platform | Device | SoC | RAM | OS | Runtime | Model | Accelerator | Compile mode | Cold init p50/p90 | First token p50/p90 | Complete p50/p90 | Peak memory MB | Thermal delta | Fallback rate | Notes |
|---|---|---|---:|---|---|---|---|---|---:|---:|---:|---:|---|---:|---|
| Android | TBD | TBD | TBD | TBD | LiteRT-LM | Gemma TBD | NPU | AOT/JIT | TBD | TBD | TBD | TBD | TBD | TBD | AI Edge Portal |
| Android | TBD | TBD | TBD | TBD | ML Kit Prompt | Gemini Nano | AICore | system | TBD | TBD | TBD | TBD | TBD | TBD | Device-local run |
| iOS | TBD | TBD | TBD | TBD | Foundation Models | Apple on-device | system | system | TBD | TBD | TBD | TBD | TBD | TBD | Instruments |

Do not publish the Medium sequel until these rows contain measured values. The article should show variance, not only best-case latency.

### 9.2 Route thresholds derived from benchmarks

After benchmark data exists, produce:

| Device class | Default route | Disable reason | First-token budget | Complete budget | Notes |
|---|---|---|---:|---:|---|
| Android high tier NPU | LiteRT-LM Gemma | p90 > budget or thermal high | TBD | TBD | From AI Edge Portal |
| Android supported AICore only | ML Kit Prompt | quota/busy/background | TBD | TBD | Gemini Nano path |
| iOS Apple Intelligence available | Foundation Models | unavailable/region/guardrail | TBD | TBD | From Instruments |
| Unsupported mobile | Deterministic fallback | no local model | 0 | 0 | Always works |

---

## 10. Tests

### commonTest

- policy chooses on-device/fallback for `LOCAL_ONLY` even when cloud is configured
- policy chooses cloud only when privacy, cost, latency, and user settings allow it
- prompt builder includes only whitelisted chess fields
- response validator rejects too-long, ungrounded, or format-broken responses
- fallback explanation covers capture/check/castle/promotion/material tags

### androidDeviceTest

- fake generator wires into the app and streams text
- ML Kit status mapping handles available/downloadable/unavailable/busy/background
- LiteRT path behind an instrumentation flag on supported devices

### iosSimulatorArm64Test

- fake Swift/Kotlin bridge maps availability and responses
- UI remains responsive while explanation is loading
- Foundation Models smoke test is opt-in and skips when unavailable

---

## 11. Milestones

- **M0 - Architecture spike.** Verify Koog artifacts on Android/iOS for this repo's Kotlin version; choose Koog graph vs hand-written policy for M1.
- **M1 - Module skeleton.** Add `:onDeviceAi`, common models, policy, prompt builder, fake generator, tests.
- **M2 - Chess integration.** Add move-coach UI, deterministic context extraction, fallback text, cancellation behavior.
- **M3 - Android ML Kit path.** Implement Prompt API actual and status handling. Ship behind a debug flag.
- **M4 - Android Gemma path.** Implement LiteRT-LM/Gemma proof of concept, packaging plan, and route integration.
- **M5 - iOS Foundation Models path.** Add Swift facade and Kotlin bridge, availability handling, and smoke tests.
- **M6 - Benchmarks.** Run AI Edge Portal for Android and Instruments for iOS. Commit variance tables.
- **M7 - Publishable article/demo.** Record GIFs, fill measured tables, write final Medium post.

---

## 12. Open questions

- Is "Gemma" required in product copy, or is "local AI coach" acceptable cross-platform? If Gemma must be literal on iOS, Foundation Models is not that path.
- Will model assets be bundled, Play Feature delivered, downloaded after consent, or left to system services where possible?
- What is the minimum supported Android device tier once benchmark variance is known?
- Does Koog mobile artifact size and initialization cost fit a game app, or should Koog remain a backend/test orchestrator until it is lighter for mobile?
- Are AI Edge Portal private-preview exports allowed to be published verbatim, or should the article publish summarized tables only?
- Should the *first shipped* coach narrow to one inference path (ML Kit Prompt **or** Foundation Models) + deterministic fallback, deferring the Gemma/LiteRT-LM path (M4) and Koog until a smaller end-to-end slice ships? M0–M7 across two inference stacks plus dual benchmarking is large for a side-project game.
