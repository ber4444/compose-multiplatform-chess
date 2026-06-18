# Android AI Edge Portal summary

Source: Google AI Edge Portal (private preview as of 2026-06-16).
Per plan §9 / §6.3 the published coach must include this summary before LiteRT-LM Gemma is
enabled by default on Android. **Status: TBD.**

## Plan

1. Upload the exact LiteRT model + GenerationConfig to AI Edge Portal.
2. Select target device lists by tier, RAM, chipset, NPU support.
3. Run CPU, GPU, and NPU configurations where applicable.
4. Export summarized variance tables (per §9.1 schema) and commit here.
5. Use the p90 + thermal results to derive route thresholds (per §9.2 schema) and update
   `AiRoutePolicies.moveCoachOffline` and `DeviceProfile` tiers in the module.

## Variance table

| Platform | Device | SoC | RAM | OS | Runtime | Model | Accelerator | Compile mode | Cold init p50/p90 | First token p50/p90 | Complete p50/p90 | Peak memory MB | Thermal delta | Fallback rate | Notes |
|---|---|---|---:|---|---|---|---|---|---:|---:|---:|---:|---|---:|---|
| Android | TBD | TBD | TBD | TBD | LiteRT-LM | Gemma TBD | NPU | AOT/JIT | TBD | TBD | TBD | TBD | TBD | TBD | TBD |

## Notes on preview terms

Plan §12 open question: are AI Edge Portal private-preview exports allowed to be published
verbatim, or should the article/this doc publish summarized tables only? Do not commit a
raw CSV export until the preview terms are reviewed.
