# Review fixes — hybrid inference vendor adoption

Scope: PR #106 (`feature/hybrid-inference-vendor-adoption`), PR #107 (docs), and the
blocked companion article. Ordered by severity. P0 blocks merge.

Sources folded in: the vendor-adoption plan (`hybrid-inference-vendor-adoption-plan.md`),
the article draft *Routing Modes Are Not a Routing Policy*, and review of #97 / #106 / #107.

Context as of this revision: `firebase-ai-ondevice` and `AiInferenceMode` have been
removed, and `FirebaseHybridGenerator` is now `FirebaseCloudGenerator` — a pure cloud
client with no mode parameter. Items below assume that state.

---

## P0 — blocks merge

### P0-1 Restore the `LOCAL_ONLY` proof

The 60-context sweep no longer proves what the article claims. Three assertions were
weakened from `assertEquals(Decision.RunOnDevice, ...)` to `assertIs<Decision.Route>(...)`,
which asserts *a* route rather than *which* route. `Route(FirebaseCloud(...))` passes.

**Why this is P0 and not P1.** Removing `firebase-ai-ondevice` also removed a backstop.
`InferenceMode.ONLY_ON_DEVICE` would have caught a `LOCAL_ONLY` routing bug in our own code
on Android even if our tests missed it. That vendor-side refusal is gone, so the sweep is
now the *only* thing enforcing the guarantee — and it was weakened in the same PR that
demonstrated exactly this bug class (P0-4). Defense-in-depth has to be rebuilt in-house.

- [x] Restore concrete route assertions at all three sites in `AiRoutePolicyDeciderTest`.
- [x] For every `LOCAL_ONLY` context, assert the emitted `VendorRoute` is specifically an
      on-device variant — not merely non-null.
- [x] Add a negative test: a `LOCAL_ONLY` policy that resolves to any cloud-capable route
      must fail loudly.
- [x] **Add a runtime guard, not just a CI-time one.** A green test proves the code was
      right when CI ran; it does not stop a bad build from shipping — which is precisely
      what `ONLY_ON_DEVICE` used to cover. Have `VendorRouteExecutor` assert that a
      `LOCAL_ONLY` policy never receives a cloud-capable generator, throwing in debug and
      falling back to the deterministic composer in release. This is the in-house
      replacement for the vendor-side refusal we gave up.

**Acceptance:** deliberately breaking the privacy branch in `resolveVendorRoute` makes the
sweep red *and* trips the runtime guard. Verify by temporarily introducing the bug.

### P0-2 Move route resolution back into `commonMain`

`resolveVendorRoute` is currently an `expect fun` with real logic in
`VendorRouteExecutor.android.kt`. `commonTest` links whichever `actual` the test target
provides, so the Android resolver is untested by the sweep — `desktopTest` proves things
about the desktop resolver.

- [x] Make `resolveVendorRoute` a pure function in `commonMain` over `AiRoutePolicy` and
      `AiContextSnapshot`. No `expect`/`actual`.
- [x] Leave only generator *construction* in the platform `actual` — `VendorRouteExecutor`
      maps an already-decided `VendorRoute` to an SDK object and does nothing else.
- [x] Move the ML Kit availability fallback (`if (mlkit.status() is Available) mlkit else
      getCactus()`) out of the executor. Availability is a context input; feed it into
      `AiContextSnapshot` so the decider owns the fallback and the sweep can cover it.

**Acceptance:** all routing decisions are reachable and asserted from `commonTest` on every
target.

### P0-3 Remove the duplicated privacy invariant

`effectiveOfflineOnly` (`requireOffline || LOCAL_ONLY || OFFLINE_ONLY`) is computed in both
`AiRoutePolicyDecider` and the Android `resolveVendorRoute`. Two copies that can drift.
This is the precedence bug tracked on `fix/vendor-route-cloud-guard`.

- [x] Single definition in `commonMain`. Delete the copy.
- [x] Fold `fix/vendor-route-cloud-guard` into this work rather than shipping the hardening
      separately — #107 defers the structural fix, but P0-1 and P0-2 make it cheap, and the
      deferred state leaves the article's central claim untested.

### P0-4 Fix the decider's short-circuit ordering

```kotlin
return when {
    vendorRoute != null -> Decision.Route(vendorRoute)   // ← wins before every gate
    effectiveOfflineOnly -> ...
    cloudAllowedByPolicy && context.isNetworkAvailable -> Decision.RunCloud
```

`cloudAllowedByPolicy`, `isNetworkAvailable`, foreground status, and the cost ceiling are
never consulted on the `Route` branch. The Android resolver gates only on
`isDeviceModelAvailable`.

- [x] Run all policy gates *before* route resolution, or make resolution take the gate
      results as inputs and return `null` when they forbid the route.
- [x] Extend the sweep to assert the emitted route respects network, foreground, thermal,
      and cost-ceiling constraints — not just privacy class.

### P0-5 Install App Check, don't just declare it

`firebase-appcheck-playintegrity` and `-debug` are in `onDeviceAi/build.gradle.kts`, but
nothing in the diff initializes a provider and `:server` doesn't verify tokens. The README
now publishes `https://compose-chess-opening-coach.fly.dev` with `min_machines_running = 1`
— a documented, unauthenticated LLM endpoint.

- [x] Install the Play Integrity provider on release builds and the debug provider locally.
- [x] Verify the token server-side; reject unattested requests.
- [x] Document the debug-token allow-listing step in the README so it isn't tribal
      knowledge.
- [x] Add an iOS App Attest implementation behind an `AttestationProvider` interface —
      App Check is a Firebase/Android primitive and the clients aren't all Android.
- [x] Record the Desktop and Web posture explicitly, even if the answer is "open."

**Interim:** if this can't land with the PR, unpublish the URL from the README or put the
service behind a shared secret until it can.

### P0-6 Decide what `FirebaseCloudGenerator` is for

As a pure cloud client it bypasses `:server`, and therefore bypasses pgvector retrieval,
the grounding validators, and the cost ceiling. There are now two cloud paths with
different guarantees.

- [x] Answer explicitly: which intents use it, and what grounds their output?
- [x] Default recommendation — delete it, and make "cloud" always mean `:server`. That
      keeps one cloud path with one set of guarantees and one enforced ceiling.
- [x] If it stays, route it through the same validators and ceiling, and say why a second
      path exists.

---

## P1 — should not ship as-is

### P1-1 Stale SDK surface

Strong signal the implementing agent worked from training priors rather than current docs,
which is exactly what Phase 0 existed to prevent.

- [x] `firebase-vertexai` is the superseded "Vertex AI in Firebase" artifact; migrate to
      the current Firebase AI Logic artifact.
- [x] `firebase-bom:33.7.0` is roughly two years stale. Bump and re-verify.
- [x] `gemini-1.5-flash` is a 2024-era model. Select a current one.
- [x] Re-verify every alpha/beta pin against live docs: `genai-prompt:1.0.0-beta3`,
      `genai-schema:1.0.0-alpha1`, `genai-schema-compiler:1.0.0-alpha1`.

### P1-2 Make structured output real, or drop the claim

The KSP `genai-schema-compiler` is wired in Gradle, but the runtime path is
`kotlinx.serialization` plus markdown-fence stripping. If constrained decoding were active
you would not receive ```` ```json ````. The fence-strip is a string-matching hack replacing
the string-matching retry loop that was deleted.

- [x] Either make `@Generable` / `@Guide` constrained decoding actually active on the ML Kit
      path and delete the fence-strip, or remove the KSP wiring and state plainly that
      parsing is `kotlinx.serialization` over a JSON mime type.
- [x] Keep `MoveCoachResponseValidator` running after deserialization either way. Schema
      conformance is not grounding.
- [x] Keep the fenced-response regression test in `DefaultAiCoachOrchestratorTest` — the
      Foundation Models and Cactus paths still need it.

### P1-3 Type the `VendorRoute` payloads

`MlKitPrompt(val preference: String = "FAST")` and friends are stringly-typed. The now-removed
`FirebaseHybrid(mode = "HYBRID")` was never a valid `InferenceMode` value — typed fields
would have caught that at compile time.

- [x] Replace `String` fields with enums (`ModelPreference`, model id value class, etc.).
- [x] Make the `when` in `VendorRouteExecutor` exhaustive without an `else -> null` arm.

### P1-4 Verify the coroutines pin survived

#101 forced coroutines `1.11.0` to fix the silent LiteRT-LM init crash. #106 adds a
`resolutionStrategy` block in `androidApp/build.gradle.kts` forcing `concurrent-futures`
and `error_prone_annotations` — with no coroutines force.

- [x] Run `./gradlew :app:dependencies` and `:androidApp:dependencies`; confirm 1.11.0 wins.
- [x] Re-run the LiteRT-LM init path on Desktop to confirm the crash hasn't returned.
- [x] Add a dependency-verification check so a future Firebase/ML Kit bump can't silently
      undo it.

### P1-5 Dead code in `getCactus()`

```kotlin
if (!isCactusInitialized()) {
    // Assume context was initialized earlier, otherwise this will throw
}
```

- [x] Either throw a diagnostic error or remove the branch. An empty `if` with a shrug in it
      is a latent crash with no message.

### P1-6 `minSdk` 24 → 26 is unremarked

- [x] Record why (presumably an ML Kit or Firebase floor) and confirm it's intended.
- [x] Note the tension: Cactus is justified as the coverage floor for devices without
      Gemini Nano, and this raises that floor.

---

## P2 — hygiene and accuracy

- [x] **Cactus is not a llama.cpp wrapper.** It moved off GGUF to its own `.cact` format
      with hand-written ARM CPU kernels at v1. Correct this in the #106 PR body, code
      comments, and every doc that repeats it.
- [x] **Use Cactus `local` mode only.** Its hybrid tier is paid; the cost ceiling stays in
      our code.
- [x] **Split the PR.** 103 files mixing a 25-file docs purge, `.gitignore` changes, and an
      architectural refactor is hard to review. At minimum, separate the purge.
- [x] **CI is red.** Both #106 and #107 report `mergeable_state: unstable`.
- [x] **Phase-number collision.** `docs/plans/` now holds two plans with independent
      numbering — Phase 1 is `MoveAssessment` in one and "widen the seam" in the other;
      Phase 6 is optional corpus bundling in one and Ktor 3.5 server work in the other.
      Prefix them `RAG-n` and `VA-n`. A bare "do Phase 6" handed to an agent is a coin flip.

---

## P3 — later phases, not this PR

Carried forward from the vendor-adoption plan so nothing is lost.

**VA Phase 3 — iOS**
- [ ] Adopt the Language Model protocol and Dynamic Profiles; the current path is the older
      iOS 26 session API.
- [ ] Model route selection as profiles — on-device for `LOCAL_ONLY`, one permitting Private
      Cloud Compute, one permitting a third-party provider.
- [ ] PCC is free under two million first-time App Store downloads. Keep enforcing the cost
      ceiling regardless; a free tier is a threshold, not a guarantee.
- [ ] Note in comments: custom weights are Core AI, not Foundation Models.

**VA Phase 4 — Desktop and Web** (Phase 0 reconnaissance still open)
- [ ] Does LiteRT-LM's native reasoning/thinking-channel support replace the
      `<think>`-stripping bypass driver? Record redundant / partial / still-required.
- [ ] Were the SIGTRAP long-generation crashes fixed upstream? This is a *different* bug
      from #101's coroutines init crash — do not conflate them.
- [ ] Confirm the Wasm package identity: is `@litert-lm/core` still shipping, or has
      LiteRT-LM.js superseded it, and does "early preview" still apply?
- [ ] Enable Gemma 4 Multi-Token Prediction (vendor reports up to 2.2x); measure locally.
- [ ] Evaluate but don't adopt LiteRT-LM's Swift/macOS API as an iOS replacement — it
      consolidates runtimes but reintroduces bundled weights. Record as a tradeoff.
- [ ] Risk: `.cact` artifacts may need reconversion on a Cactus major bump. Pin the
      converter alongside the runtime.
- [ ] Verify ML Kit's own route on real AICore hardware (e.g. Pixel 8/9 Pro). Currently only the Cactus fallback has been exercised (on a Fold3), so we don't know if Gemini Nano has its own output quirks similar to Cactus.

**VA Phase 6 — server**
- [ ] Bump Ktor to 3.5.x; watch for MockEngine timeout regressions from 3.4.3.
- [ ] Add SSE heartbeats via 3.5's `eventProvider` so a dead connection surfaces as a
      stalled heartbeat rather than a hung `Flow`.
- [ ] Keep `respondBytesWriter` — `sse {}` is still GET-only as of 3.5. Pin that claim to
      the version in a comment so it gets rechecked on the next bump.
- [ ] Keep the hand-written `openapi.yaml` as the client contract. Optionally use
      `.describe {}` for internal endpoints. Do not replace the committed spec with a
      generated one — a spec derived from the routing tree cannot detect server drift.
- [x] Server-side output sanitization (streaming chat leaks): dynamically strip `<think>`
      chain-of-thought blocks from the `LlmChatComposer` stream before they reach the validator.

**VA Phase 7 — evals**
- [ ] Add route-selection assertions distinct from output-quality scoring.
- [ ] Re-run the 100-case set and the 200-turn drift route; regenerate the scorecard.
- [ ] Hand-verify golden cases whose expected output shifted with a model change.

**Unclaimed but cheap**
- [ ] Try the AICore developer-preview prompt-iteration workflow on the ML Kit path.
      Google reported taking a response from ~13s to under 2s by iterating prompts there.
- [ ] Read `android/ai-samples` → `jetpacker` as a reference for ML Kit and AI Logic call
      sites.
- [ ] Cactus **Needle** (26M-param tool-calling, MIT) — no use case today; note it if
      on-device tool calling ever enters scope.
- [ ] Evaluate ecosystem updates: Kotlin 2.4.0, Compose Multiplatform 1.12.0-beta01, Compose Hot Reload's experimental MCP server, and Ktor Koog plugin for agentic services.
- [ ] Check if Part 5 of Google's Android AI blog series (agentic booking assistant, A2UI + ADK) has published.

---

## Article gate

*Routing Modes Are Not a Routing Policy* is blocked. These are content defects, not
polish.

- [x] **The two-layer enforcement claim is now false.** The draft says "our policy refuses
      to emit a cloud route, and the vendor refuses to take one." With
      `firebase-ai-ondevice` removed, there is no vendor-side refusal. Rewrite as
      single-layer, and make the honest case for the removal: the on-device leg duplicated
      ML Kit, the enforcement benefit was Android-only, and a declared-but-unused
      dependency is worse than none. Do not use "it ruins the seam" — platform-specific
      transports inside an `actual` are what the seam is for.
- [x] **The pgvector section is invalidated by #107.** The draft defends
      opening-theory-over-pgvector as the deliberate choice. #107 shows that corpus makes
      "what went wrong in my game" *unrepresentable* under a validator requiring a citation
      plus >=2 shared content words. The surviving claim is narrower: owning the corpus is
      what makes the validator scoreable. The specific corpus does not survive.
- [x] **The reason-faithfulness claim is contradicted twice** — by #97's Known-gap 2
      (the thinking model leaking reasoning into content) and by
      `move-coach-quality-axes.md` recording reason-faithfulness as an *unchecked* axis.
      The draft's "ensuring the final emitted output strictly adheres to the requested
      format" cannot stand.
- [x] **Cactus is not a llama.cpp wrapper** (see P2).
- [x] **Gemini Nano reach correction** — over 140 million devices, Nano 4 on the Gemma 4
      architecture. The original "only top-tier flagships" rejection is expired; the
      surviving reason for not making it the cross-platform default is that it's
      Android-only.
- [ ] Resolve every `[VERIFY]` and `[MEASURE]` marker from Phase 0 findings and measured
      benchmarks.
- [ ] Confirm no sentence describes something not in the repo and run at least once.

---

## Follow-up articles (separate work, not this repo)

- **App-as-MCP-server via AppFunctions.** Inverts the `ferryman-mcp` host work. The
  differentiated angle: KDoc compiles into the tool schema and affects agent execution
  accuracy, so *grade the KDoc* with the existing eval harness. Constraints: alpha API,
  `@RequiresApi(36)`, ADB verification on Android 17+.
- **Router evals.** Now partly absorbed by the main article's evals section, so it needs a
  narrower angle: a reusable pattern for proving routing invariants, extracted from this
  repo rather than a retelling of it.
- **ML Kit Speech Recognition** as a fourth column in the `kmp-videos` scorecard alongside
  Deepgram, Soniox, and AssemblyAI. Basic mode is API 31+ on most devices; advanced mode
  uses Gemini Nano and is Pixel 10 only. Different project.
