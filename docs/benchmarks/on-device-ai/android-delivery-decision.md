# Android model-delivery decision (§6.1.1 spike)

Status: **Decided — Cactus ships.** The §6.1.1 spike evaluated five
runtimes and selected Cactus (`com.cactuscompute:cactus:1.4.1-beta`) over the
alternatives listed below. The integration lives in
`onDeviceAi/src/androidMain/.../cactus/` and is wired directly into the debug
coach path (no reflection gate — the artifact is on Maven Central and resolves
cleanly). The `gemma3-270m` model (~172 MB) is downloaded from Hugging Face by
Cactus on first launch; no model is bundled in the APK (debug APK ~258 MB, down
from 651 MB with the bundled LiteRT-LM model).

## Decision rationale

| Runtime | Verdict | Reason |
|---|---|---|
| **Cactus (llama.cpp)** | **Shipped** | Resolves from Maven Central, CPU-only (no per-SoC dispatch libraries), model download handled by the library, ~1–2 s cold start. |
| LiteRT-LM | Rejected | Slow (7–9 s cold init on `gemma3-270m`) and the streaming path crashed during the spike. The published Maven coordinate for `com.google.ai.edge.litertlm` never resolved (probed `0.0.x`–`0.2.x` on 2026-06-16; all 404), forcing a reflection-gated PoC. |
| ML Kit GenAI Prompt API (AICore) | Rejected for default | Gemini Nano path, not Gemma; AICore availability is narrow (recent Pixel/Samsung only). Kept as an optional higher-tier route, not the default. |
| ExecuTorch | Rejected | Requires authoring a `.pte` conversion pipeline for Gemma; no first-party Gemma `.pte` artifact was available. |
| ONNX Runtime | Rejected | No published Android GenAI Maven artifact at spike time (`com.microsoft.onnxruntime.genai` had no stable Android coordinate). |

## Candidate paths (per plan §6.1.1)

| Delivery path | App size | Update cadence | Offline after setup | Notes |
|---|---:|---|---|---|
| AICore/ML Kit system model | low | OS/Play services managed | yes after model availability | Gemini Nano path, not Gemma. Optional higher-tier route only (`MlKitPromptTextGenerator` compiles). |
| **Cactus + HF-downloaded Gemma** | **low base APK** | **model-source managed** | **yes after first-launch download** | **M3 ships this path.** ~172 MB `gemma3-270m` fetched by Cactus into `filesDir`; debug APK ~258 MB. |
| Play Feature / asset delivery | medium base APK | Play-managed | yes after install | Superseded by Cactus's self-managed download for the coach use case. |
| Firebase ML / remote model delivery | low base APK | remote model updates | yes after download | Not pursued — Cactus's HF download already keeps the base APK small. |
| Hugging Face optimized artifact | varies | model-source dependent | yes after packaging/download | This is what Cactus consumes (GGUF). |
| PyTorch export pipeline | varies | app-owned conversion | yes after packaging/download | Not pursued — ExecuTorch/ONNX conversion cost was the blocker, not the model source. |
| no local model | none | none | fallback only | always-supported baseline. **No platform ships this as its default anymore** — desktop + wasm now use LiteRT-LM (see `desktop-wasm-litert-lm.md`); only the JS target stays on it. |

## Spike output

Measured values from the Cactus spike: debug APK **258 MB** (down from 651 MB
bundled-LiteRT-LM), install-time **no model unpack** (download deferred to first
launch), offline-after-download **yes**, end-to-end first-token latency
**~1–2 s cold init** (down from 7–9 s with LiteRT-LM). The LiteRT-LM reflection
gate (`chess.coach.litert.enabled`, `defaultLitertLmModelPath()`,
`LiteRtLmTextGenerator`) has been removed along with `MoveCoachModelAsset.kt`
and `AndroidCoachWiring`.

## What compiles today

- `CactusTextGenerator` (onDeviceAi `androidMain`) — wires `cactus.Context` +
  `Model` + `LLM` against the resolved Maven artifact; returns `Unavailable`
  only if model download fails.
- `MlKitPromptTextGenerator` (ML Kit GenAI Prompt API, `com.google.mlkit:genai-prompt:1.0.0-beta2`)
  compiles against the verified API surface and remains as an optional
  higher-tier route.

## Open questions

- Should the higher-tier AICore/ML Kit Prompt route be enabled ahead of Cactus
  on devices that report AICore availability, to reduce first-launch data usage?
- What is the right cache-eviction policy for the downloaded `qwen3-0.6` GGUF
  in `filesDir` (currently never evicted)?
- Should iOS move off Foundation Models onto the shared Cactus KMP module?
  (Not done in M3 — iOS stays on Foundation Models; Cactus is Android-only for
  now but lives in a KMP library so the swap is feasible later.)
