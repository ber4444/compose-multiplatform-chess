# Building a Cross-Platform On-Device AI Move Coach for a Compose Multiplatform Chess App

*How we added an offline AI chess coach to a KMP game — what worked, what didn't, and why the "obvious" choices were wrong.*

---

## The pitch

Our chess app already runs Stockfish on-device to pick Black's moves. The player plays White; the engine plays Black. But after each Stockfish move, the player is left staring at the board wondering *why* that move.

We wanted a move coach: a short, grounded, natural-language explanation of each engine move, generated entirely on-device. No cloud calls, no per-user API costs, no latency from a remote model. The phone becomes an *intelligent client* — it owns the decision loop from engine move to explanation.

This article covers what we built, the runtimes we evaluated (and rejected), and the architecture that lets us swap inference backends per-platform without touching game logic.

## The architecture

We added a new KMP module — `:onDeviceAi` — between the game engine and the UI. The module owns:

- **Semantic request/response models** — `MoveCoachRequest` (FEN, best move, tactical tags, evaluation) and `MoveCoachExplanation` (headline, body, confidence, metrics)
- **Routing policy** — pure Kotlin that decides on-device vs. cloud vs. fallback based on privacy class, latency budget, thermal state, foreground/background, and user settings
- **Prompt builder** — assembles a grounded prompt from the request's whitelisted chess fields, never interpolating user input into the system instruction
- **Response validator** — rejects too-long, ungrounded, or forbidden-phrase responses, with a retry-then-fallback chain
- **Deterministic fallback** — rule-based explanation built from the same tactical tags, so the panel always shows *something* useful even when the model is unavailable
- **Orchestrator** — drives the flow: route → generate → validate → retry-or-fallback, all as a Kotlin `Flow`

The chess app owns:
- **Context extraction** — derives tactical tags (capture, check, castle, promotion, develops, center-control, king-safety, opening) from before/after game states
- **UI state** — a sealed `MoveCoachUiState` that drives a Compose panel
- **Timing** — fires the coach after Black's move; cancels stale jobs on reset or new White move

### Why a separate module?

The chess game logic shouldn't know about language models. The `:onDeviceAi` module exposes interfaces (`OnDeviceTextGenerator`, `AiCoachOrchestrator`) that platform code implements. Common code is fully testable with fakes; platform glue is thin and frozen.

## The runtime journey

This is where most of our time went. The "obvious" choices were wrong.

### ML Kit GenAI Prompt API (AICore / Gemini Nano) — rejected

Our first Android path was ML Kit's GenAI Prompt API (`com.google.mlkit:genai-prompt:1.0.0-beta2`). It's the official Google path for on-device GenAI, uses Gemini Nano through AICore, and would have been instant on supported devices.

The problem: AICore is only available on a narrow set of flagship devices (Pixel 9/10, select Samsung S25/S26, some OnePlus/OPPO flagships). On most Android phones — including most Samsungs — it simply isn't there. The device support list reads like a who's-who of $1000+ phones from the last 18 months.

**Verdict:** too narrow. Dropped.

### LiteRT-LM with bundled Gemma — tried, rejected

We then tried LiteRT-LM (`com.google.ai.edge.litertlm:litertlm-android:0.13.1`) with a bundled Gemma `.litertlm` model. This works on any device with sufficient RAM — no AICore dependency.

The problems stacked up:

| Issue | Impact |
|---|---|
| 557 MB model bundled in APK | APK grew to 651 MB |
| 7-9 second cold start | Model load (557 MB from disk) + GPU kernel compilation (1330 OpenCL nodes) |
| Streaming SIGSEGV at 0.13.1 | `sendMessageAsync` Flow spawns a native callback thread that null-pointer-dereferences ~6ms after creation |
| Deadlock on rapid moves | When a coach job is cancelled mid-inference, the native `sendMessage()` call (blocking JNI, not cancellable) keeps running. The next move's call blocks waiting for the engine to finish. Repeat forever. |

We worked around the streaming crash by switching to the synchronous `sendMessage()` API. We worked around the deadlock by serializing all native calls through a single-threaded dispatcher. We worked around the cold start by pre-initializing during a loading phase.

But the fundamental problem remained: loading 557 MB from disk into memory takes time, and there's no smaller `.litertlm` Gemma available.

**Verdict:** works, but painfully slow and crash-prone. Replaced.

### ONNX Runtime — ruled out

ONNX Runtime has excellent Android support for general ML inference, and there are ONNX-format Gemma models on Hugging Face (`onnx-community/gemma-3-1b-it-ONNX` with 770+ downloads).

But the LLM-specific package — `onnxruntime-genai-android` — doesn't exist on Maven. Without it, you'd need to hand-implement tokenization (SentencePiece/BPE on JVM), KV cache management, and the autoregressive generation loop. That's multi-week work that LiteRT-LM, Cactus, and ExecuTorch exist to spare you from.

**Verdict:** not viable for Android LLM without significant manual plumbing.

### ExecuTorch — viable, but needs model conversion

ExecuTorch (`org.pytorch:executorch-android:1.3.1`) is PyTorch's on-device runtime, published on Maven with `LlmModule` (streaming generation, tokenization, KV cache) and `LlmCallback` in the AAR. It has conversion scripts for Gemma, SmolLM2, Qwen3, Phi, and many others.

The barrier: you need a `.pte` model file (PyTorch Export format). Pre-converted files exist on Hugging Face but are gated. Converting one yourself requires the PyTorch + ExecuTorch Python toolchain. The native library (8.3 MB) is smaller than LiteRT-LM's (14.9 MB).

**Verdict:** mature and production-ready, but requires model conversion work. Good long-term option.

### Cactus (llama.cpp) — chosen

Cactus (`com.cactuscompute:cactus:1.4.1-beta`) is a KMP wrapper around llama.cpp — the same CPU-optimized inference engine that powers Ollama, LM Studio, and most mobile LLM apps. It's published on Maven Central and works on both Android and iOS from a single KMP dependency.

Why we chose it:

| Factor | Cactus | LiteRT-LM (replaced) |
|---|---|---|
| Model size | 200 MB (`gemma3-270m`) | 557 MB (`gemma3-1b-it-int4`) |
| Cold start | ~1-2s (CPU, no GPU compilation) | 7-9s (model load + OpenCL compilation) |
| APK size | 258 MB (model downloaded at runtime) | 651 MB (model bundled) |
| Streaming | llama.cpp (battle-tested) | SIGSEGV at 0.13.1 |
| Model download | Built-in from Hugging Face | Manual asset bundling |
| KMP | Yes (Android + iOS from one library) | No (platform-specific) |
| Hybrid mode | Built-in `LOCAL_FIRST` (cloud fallback) | No |

The tradeoff: Cactus is beta (72 GitHub stars, small team), telemetry is on by default (we disable it), and NPU acceleration requires a Pro key. But the CPU path alone — llama.cpp's ARM NEON-optimized kernels with a 270M model — is dramatically faster than what we had.

### iOS: Apple Foundation Models — kept

On iOS, we use Apple's Foundation Models framework (the on-device model behind Apple Intelligence). This is instant — the model ships with the OS as part of iOS 26, so there's no loading, no compilation, no download. The app just opens a `LanguageModelSession` and generates.

The Swift facade (`FoundationMoveCoach.swift`) wraps `LanguageModelSession` with static coach instructions and short output. A Kotlin bridge (`FoundationModelsBridge`) adopts a common `OnDeviceTextGenerator` interface so the shared orchestrator can drive it without knowing it's Foundation Models.

Foundation Models requires iOS 26+ and Apple Intelligence enabled. On older devices or with Apple Intelligence off, the orchestrator falls back to deterministic text.

## What the prompt looks like

The prompt is intentionally narrow and grounded. The model isn't asked to reason about chess — Stockfish already did that. It's asked to *rephrase app-supplied facts* in natural language.

**System instruction:**
```
You are a chess coach explaining a single move to a casual player.
Say WHY the move is good in 1-2 short sentences.
Be specific: mention the piece, the square, and what it does.
Do not mention openings by name, engine depth, or ratings.

Good: "Nf3 develops the knight and controls the central e5/d4 squares."
Bad: "This is a good move that improves the position."
```

**User prompt:**
```
Move: Knight g1→f3
Key points: develops a piece, controls the center, opening-phase move

Explain this move in 1-2 sentences:
```

The "Key points" come from deterministic tags that the chess app derives from the before/after game state: `develops` (minor piece leaving the back rank), `center-control` (destination is a central square), `king-safety` (king moves), `capture`, `check`, `castle`, `promotion`, `material-swing`, `pawn-push`, `opening` (first 10 fullmoves).

The validator accepts responses that mention chess vocabulary (piece names, tactical terms) or the move's UCI squares — so "Develops the knight toward the center" passes even without mentioning "f3" literally.

## What the fallback looks like

When the model is unavailable (slow device, beta not initialized, Apple Intelligence off, validation failed twice), the orchestrator produces a deterministic explanation from the same tags:

```
Nf3 develops a piece to an active square. It fights for the center.
```

```
Qh5 delivers checkmate.
```

```
O-O castles kingside, tucking the king to safety.
```

This fallback is what makes the feature ship-able: the panel always shows *something* coach-shaped, even on devices that have no local model at all (desktop, web).

## The routing policy

The routing policy is pure Kotlin, exhaustively unit-tested, and models three routes: `OnDevice`, `Cloud`, and `Fallback`. The move coach is classified `LOCAL_ONLY` — it never uses cloud, even though the policy supports it for future request classes.

Key decisions the policy makes:

| Condition | Route |
|---|---|
| App backgrounded | Fallback (ML Kit/AICore blocks background inference) |
| Thermal state CRITICAL | Fallback (or Cloud if allowed) |
| `LOCAL_ONLY` + model available | OnDevice |
| `LOCAL_ONLY` + model unavailable | Fallback |
| `maxUsdCents = 0` | OnDevice or Fallback (never Cloud) |

The policy is designed to be general — future request classes (`USER_PRIVATE`, `PUBLIC_OR_SYNTHETIC`) can opt into cloud routing by flipping `allowCloud = true` and providing a non-zero cost budget. The move coach's `LOCAL_ONLY` classification is a product decision, not an architectural constraint.

## What we'd do differently

1. **Start with Cactus.** We spent days on LiteRT-LM's streaming crash, deadlock, and cold-start optimization before researching alternatives. A 30-minute Maven Central probe would have surfaced Cactus and saved that time.

2. **Wire engine evaluations into the prompt.** The prompt has slots for `evaluationBeforeCp` / `evaluationAfterCp` but they're hardcoded null. Wiring the Stockfish `evaluate(fen)` call would give the model (and the fallback) more concrete grounding.

3. **Benchmark before shipping.** We have a benchmark schema (`docs/benchmarks/on-device-ai/`) but no measured values. The latency budgets (5s first-token, 20s complete) are provisional. Real device benchmarks should drive the thresholds before any release-ship.

4. **Consider Cactus for iOS too.** Foundation Models is instant but requires iOS 26 + Apple Intelligence. Cactus is KMP — the same library could provide on-device inference on iOS for devices that don't have Apple Intelligence, unifying the codebase.

## Try it

The code is on the `on-device-ai-move-coach` branch. The chess app launches into 3D mode; after Stockfish returns Black's first move, the coach panel appears at the bottom of the screen with a 2-sentence explanation.

- **Android**: `./gradlew :androidApp:installDebug` — Cactus downloads `gemma3-270m` (~200 MB) on first launch
- **iOS**: Build from Xcode — Foundation Models requires iOS 26 + Apple Intelligence enabled in Settings

Both platforms fall back to deterministic rule-based text when the model is unavailable. The architecture supports hybrid cloud routing for future non-`LOCAL_ONLY` request classes.
