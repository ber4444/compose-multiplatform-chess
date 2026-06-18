# Android model-delivery decision (§6.1.1 spike)

Status: **TBD.** The spike is incomplete — the published Android Maven coordinate for
LiteRT-LM has not been verified to resolve from Google's Maven (probed `0.0.x`–`0.2.x`
under `com.google.ai.edge.litert:litert-lm`, `com.google.ai.edge.litert-lm-runtime:*`,
`com.google.ai.edge.litert.lm-runtime:*` on 2026-06-16; all 404). The integration in
`onDeviceAi/src/androidMain/.../litert/LiteRtLmTextGenerator.kt` is therefore gated
behind reflection (`Class.forName`) so the build does not depend on an unverified artifact.

## Candidate paths (per plan §6.1.1)

| Delivery path | App size | Update cadence | Offline after setup | Notes |
|---|---:|---|---|---|
| AICore/ML Kit system model | low | OS/Play services managed | yes after model availability | Gemini Nano path, not Gemma. **M3 ships this path via `MlKitPromptTextGenerator`.** |
| bundled LiteRT-LM Gemma asset | high | app release | yes | simplest control, worst binary growth |
| Play Feature / asset delivery | medium base APK | Play-managed | yes after install | good for optional coach panel |
| Firebase ML / remote model delivery | low base APK | remote model updates | yes after download | verify LiteRT-LM/Gemma compatibility and policy |
| Hugging Face optimized artifact | varies | model-source dependent | yes after packaging/download | check LiteRT, ONNX, and ExecuTorch variants |
| PyTorch export pipeline | varies | app-owned conversion | yes after packaging/download | verify `optimum-cli export tflite` / ONNX / ExecuTorch quality |
| no local model | none | none | fallback only | always-supported baseline; **desktop/wasm ship this path.** |

## Spike output required

A decision row plus measurements: APK size impact, install-time behavior, offline-after-download
behavior, end-to-end first-token latency, and peak memory. Until those land, the LiteRT-LM path
stays behind `chess.coach.litert.enabled` and a non-null `defaultLitertLmModelPath()` (currently
always null), and the shipped Android coach is ML Kit Prompt API or deterministic fallback.

## What compiles today

- `MlKitPromptTextGenerator` (ML Kit GenAI Prompt API, `com.google.mlkit:genai-prompt:1.0.0-beta2`)
  compiles against the verified API surface.
- `LiteRtLmTextGenerator` is a reflection-gated PoC that returns `Unavailable` until both
  the LiteRT-LM runtime is on the classpath AND `defaultLitertLmModelPath()` returns a packaged
  `.litertlm` artifact path.

## Open questions

- What is the published Maven coordinate for LiteRT-LM? (Pending verification — none of the
  probed coordinates resolve.)
- Is Hugging Face's `litertlm` Gemma artifact SoC-specific (NPU-only) or portable across the
  minimum-supported device tier?
- Does Firebase ML remote model delivery support `.litertlm` artifacts, and does it require an
  app-side runtime download?
