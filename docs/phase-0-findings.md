# Phase 0 Findings

This document records the answers to the reconnaissance questions posed in Phase 0 of the `hybrid-inference-vendor-adoption-plan.md`.

## 1. LiteRT-LM Reasoning/Thinking Channel Support
**Question:** Check LiteRT-LM release notes for reasoning/thinking-channel support. Determine whether `ReasonFaithfulnessBypassDriver` (the `<think>`-stripping layer) is now redundant.
**Finding:** Yes, LiteRT-LM v0.14.0 adds native support for reasoning channels. The `ReasonFaithfulnessBypassDriver` is now **redundant** for new `.litertlm` reasoning models, but remains required for non-LiteRT-LM runtimes (llama.cpp, local APIs). 

## 2. LiteRT-LM Native SIGTRAP Crashes
**Question:** Check LiteRT-LM release notes and issue tracker for the native SIGTRAP crashes on long generations that the native bridge was hardened against. Determine whether the upstream fix supersedes our hardening.
**Finding:** It is *likely* fixed upstream, but requires manual verification during Phase 4 soak testing before removing our native bridge hardening.

## 3. Wasm Package Identity
**Question:** Confirm the Wasm package identity. Determine whether `@litert-lm/core` is still the shipping package, whether "early preview" still applies, and whether a migration is required.
**Finding:** Yes, `@litert-lm/core` is still the shipping package. No package migration is required.
