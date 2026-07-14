# Desktop + Wasm model-delivery decision

Status: **Decided — LiteRT-LM (Google AI Edge) ships on both, opt-in.** Desktop
uses the `litertlm-jvm` Maven artifact (native libs bundled in-jar); Wasm uses
`@litert-lm/core` (LiteRT-LM for Web) loaded from the jsdelivr CDN inside a module
Web Worker. Both backends plug into the existing `OnDeviceTextGenerator` seam —
the `DefaultAiCoachOrchestrator` → `MoveCoachManager` → `MoveCoachPanel` pipeline
is reused unchanged. Gated behind `CHESS_ENABLE_COACH=1` (desktop env var) and
`?coach=1` (wasm URL param); without the gate the coach panel stays `Hidden`
(the pre-feature default).

The model differs by target because the two runtimes have different supported-model
sets:

- **Desktop** (`litertlm-jvm`): **Qwen3-0.6B-int4** (~347 MB `.litertlm`,
  publicly downloadable from `litert-community/Qwen3-0.6B-int4`). `litertlm-jvm`
  loads any `.litertlm`, so the smaller Qwen3 is preferred.
- **Wasm** (`@litert-lm/core`): **gemma-4-E2B-it-web.litertlm** (~2 GB, from
  `litert-community/gemma-4-E2B-it-litert-lm`). This is one of only two models
  `@litert-lm/core` officially documents for web (the other is the E4B variant);
  non-`-web.litertlm` models are not guaranteed to load on WebGPU, so correctness
  wins over size for the opt-in web path.

Both are fetched on first launch and cached. Picked over `gemma3-270m` (the
Android Cactus model) because that HF repo became license-gated (401 on the
weights) after this feature was scoped.

## Premise correction

The original ask was to integrate "LiteRT.js" and "prebuilt LiteRT C++ binaries."
Neither is an LLM runtime:

- **`@litertjs/core` (LiteRT.js)** is a *tensor-level* runtime for `.tflite`
  models. Its documented API is `loadAndCompile(path, {accelerator})` →
  `model.run(Tensor)` → output `Tensor` — no tokenizer, no chat object, no
  `sendMessage`, no KV-cache, no sampler. The docs' own example is a 224×224
  image-classification input. Using it for text generation would mean
  reimplementing the LLM engine internals (SentencePiece tokenizer, autoregressive
  decode loop, KV-cache management, top-k sampling, detokenization) by hand in JS.
- **`litert_cc_sdk.zip`** (the "prebuilt LiteRT C++ binary") is headers + CMake
  glue only — no prebuilt `.so`, and **no LLM pipeline** (no tokenizer/KV-cache/
  sampler). It's the generic TFLite-style runtime core, not the LLM layer.

The LLM-capable sibling in the same Google AI Edge family is **LiteRT-LM** —
Google's production LLM orchestration layer (tokenizer + KV cache + sampler +
speculative decoding) that sits on top of the LiteRT core. We use LiteRT-LM on
both targets:

| Target | Package | API | How it's loaded |
|---|---|---|---|
| **Desktop (JVM)** | `com.google.ai.edge.litertlm:litertlm-jvm:0.14.0` | `Engine(EngineConfig(modelPath, Backend.CPU))` → `createConversation()` → `sendMessageAsync(String): Flow<Message>` | Maven dependency; native libs (`liblitertlm_jni.so` / `.dll`) bundled in-jar, auto-extracted by `NativeLibraryLoader` |
| **Wasm** | `@litert-lm/core@0.14.0` | `Engine.create({model: url})` → `createConversation()` → `sendMessageStreaming(text)` (async iterable) | ESM `import` from `cdn.jsdelivr.net` inside a module Web Worker spawned from a Blob URL |

## Decision rationale (desktop)

| Runtime | Verdict | Reason |
|---|---|---|
| **LiteRT-LM (`litertlm-jvm`)** | **Shipped** | Pure Maven dependency (no JNI/CMake work); native libs bundled for linux-x64/aarch64, darwin-arm64, win-x64; high-level `Engine`/`Conversation` Kotlin API with `Flow`-based streaming; same `.litertlm` model format as the web path. |
| LiteRT C++ SDK via JNI | Rejected | Headers-only (`litert_cc_sdk.zip` has no prebuilt `.so`); no LLM pipeline — would need building LiteRT-LM from source (Bazel) and a hand-written JNI bridge, months of work for no gain over the Maven artifact. |
| Cactus on JVM | Rejected | Cactus is Android-focused (ARM NEON kernels, Android `Context` initializer); no published desktop JVM coordinate. |
| llama.cpp via JNI | Rejected | Would replicate what `litertlm-jvm` already provides, with more glue. |

## Decision rationale (wasm)

| Runtime | Verdict | Reason |
|---|---|---|
| **LiteRT-LM for Web (`@litert-lm/core`)** | **Shipped** | LiteRT-branded LLM runtime; async-iterable streaming; loads any `.litertlm` model; runs in a module worker (off-main-thread). Early preview, but the cleanest LiteRT-family fit. |
| `@mediapipe/tasks-genai` LLM Inference | Considered, rejected | Mature, verified Gemma 3 270M `-web.task` support, official worker sample. Rejected only on branding — it's MediaPipe, not LiteRT, and the user asked for the LiteRT path. Would be the lower-risk swap if `@litert-lm/core` proves unstable. |
| `@litertjs/core` (literal "LiteRT.js") | Rejected | Tensor-level only (see premise correction). No LLM API. |
| ExecuTorch / ONNX Runtime Web | Rejected | No first-party Gemma/Qwen web artifacts; would require a custom conversion + glue pipeline. |

## Candidate paths

| Delivery path | App footprint | Update cadence | Offline after setup | Notes |
|---|---:|---|---|---|
| **Desktop: `litertlm-jvm` + HF-downloaded Qwen3** | +127 MB jar (native libs); ~347 MB model cached in `~/.chess-coach-models/` | model-source managed | yes after first-launch download | **Shipped.** `LitertLmModelStore.download()` streams with progress; atomic `.part`→rename guards against partial cache. |
| **Wasm: `@litert-lm/core` CDN + HF Gemma-4-E2B-it-web** | 0 bundled (CDN + HF fetched at runtime) | CDN/module managed | no (re-fetched each session, CDN-cached) | **Shipped.** Worker streams the model via `Engine.create({model: url})`. ~2 GB — large, but it's the only `@litert-lm/core`-documented web model. |
| Bundle model in desktop distribution | +347 MB install | app-release managed | yes immediately | Rejected — slows every install/package and dev iteration; download-on-first-launch mirrors Android Cactus. |
| Bundle model in webpack dist (wasm) | +2 GB dist | app-release managed | yes after load | Rejected — bloats the dev server + production payload; the largest vendored asset today is 7 MB (Stockfish wasm). |

## Graceful degradation

Both backends inherit the existing fallback ladder — no behavior regression on
unsupported hosts:

- **Wasm without WebGPU** (Firefox, Safari without flag, old hardware):
  `status()` returns `AiAvailability.Unavailable` *without* any network fetch
  (`navigator.gpu` probe first), so the orchestrator routes to
  `MoveCoachFallback`. The user still sees the deterministic rule-based
  explanation.
- **Intel Mac desktop** (darwin-x86_64): `litertlm-jvm` ships no native lib for
  it, so `Engine()` construction throws `UnsatisfiedLinkError`, caught in
  `ensureInitialized()` → `status()` returns `Error` → fallback.
- **Model download fails / corrupt cache**: `LitertLmModelStore` re-downloads if
  the cached file is `< 10 MB`; a download exception surfaces as `Error` → fallback.
- **Gate off** (`CHESS_ENABLE_COACH` unset / `?coach` absent): managers stay
  attached to a null orchestrator, panel stays `Hidden` — identical to pre-feature
  behavior.

## What ships today

- `LitertLmTextGenerator` (`onDeviceAi` desktopMain) — `Engine` lifecycle,
  single-thread dispatcher (mirrors `CactusTextGenerator`), no-op `close` to keep
  the model warm.
- `LitertLmModelStore` (`onDeviceAi` desktopMain) — HF download + cache with
  progress, atomic swap, `chess.coach.modelUrl` system-property override.
- `LitertLmWasmTextGenerator` + `LitertLmWasmInterop` (`onDeviceAi` wasmJsMain) —
  module-worker spawn from Blob URL, JSON-over-`postMessage` protocol, WebGPU
  probe, tiny hand-rolled JSON builders (no kotlinx-serialization on the hot path).
- Desktop/wasm `defaultOnDeviceTextGeneratorFactory()` actuals now return the
  LiteRT-LM generators (cached singletons) instead of `UnsupportedTextGenerator`.
- `Main.kt` (desktop + wasm) wire `MoveCoachManager` + `GameSummaryManager` into
  `AppRoot`, mirroring `MainActivity.attachMoveCoach`.

## Verification status

- ✅ Compiles on desktop (`:onDeviceAi:compileKotlinDesktop`, `:app:compileKotlinDesktop`).
- ✅ Compiles on wasm (`:onDeviceAi:compileKotlinWasmJs`, `:app:compileKotlinWasmJs`).
- ✅ Compiles on JS (`:onDeviceAi:compileKotlinJs`) — unchanged, still `UnsupportedTextGenerator`.
- ✅ Existing desktop tests pass (`:onDeviceAi:desktopTest`, `:chess-core:desktopTest`).
- ⏳ **Manual end-to-end not yet run** (see open questions). The litertlm-jvm API
  surface was verified by `javap` against the actual 0.14.0 jar; the
  `@litert-lm/core` + Qwen3 `.litertlm` combination is *plausible* but unverified
  on WebGPU (the package only officially documents Gemma 4 web models).

## Open questions / risks

- **`@litert-lm/core` is "early preview"**: the web package self-describes as an
  early preview. The desktop `litertlm-jvm` API is marked "Stable". If the web
  package proves unstable, the lower-risk swap is `@mediapipe/tasks-genai` LLM
  Inference (mature, has an official worker sample, verifies Gemma 3 270M
  `-web.task` at ~238 MB) — both would implement the same `OnDeviceTextGenerator`
  seam, so the swap is localized to `wasmJsMain`.
- **Wasm model size (~2 GB)**: `gemma-4-E2B-it-web` is the only `@litert-lm/core`-
  documented web model, but it's large. If a smaller `-web.litertlm` ships later
  (or `@litert-lm/core` expands its supported set), updating is a one-line change
  to `DEFAULT_MODEL_URL`.
- **Measured latency / throughput** not yet captured (cold start, tok/s, TTFT) on
  either target — fill in after the manual run.
- **Module worker from Blob URL**: Chrome supports this; if a CSP blocks it, fall
  back to a static `worker.js` under `app/src/wasmJsMain/resources/llm/`
  (Stockfish static-asset pattern).
- **litertlm-jvm pulls kotlin-reflect 2.2.21** transitively; the project is on
  Kotlin 2.3.x. Gradle resolves to the project's newer version — confirmed no
  binary incompatibility at compile time, but worth a runtime smoke test.
- **Desktop gate ergonomics**: `CHESS_ENABLE_COACH=1` is an env var (mirrors the
  Android `FLAG_DEBUGGABLE` debug gate). If this should be a persisted setting in
  the Settings screen instead, that's a follow-up — it would remove the env-var
  friction but make the download easier to trigger accidentally.
