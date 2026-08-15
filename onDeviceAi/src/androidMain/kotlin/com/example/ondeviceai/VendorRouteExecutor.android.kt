package com.example.ondeviceai

/**
 * Android's AI runtime seam — now ML Kit only, and dormant in practice.
 *
 * Cactus was removed after every model in its catalog was benchmarked on real hardware and all of
 * them lost to the deterministic text, on latency, on truth, or both:
 * `gemma3-270m` echoed its own prompt, `qwen3-0.6` needed 20-36 s because every `qwen3-*` is a
 * reasoning model and `/no_think` is inert through Cactus, `gemma3-1b` spent 5-20 s on generic
 * waffle, and `lfm2-700m` was fluent and false ("Nh3 … immediately controls the center" about a
 * knight on the rim). On Game Summary — the surface with **no response validator at all** —
 * `gemma3-1b` invented a bishop sacrifice on move 1 and once answered in German, and all three runs
 * were accepted. See `docs/benchmarks/on-device-ai/android-model-latency-2026-08.md`.
 *
 * So Android's Move Coach and Game Summary are deterministic. That is not a downgrade: the coach
 * answers instantly with "Your bishop on b5 pins the knight on c6 against the king on e8", and the
 * summary composes the same turning points a model was given and could not use.
 *
 * **ML Kit reaches a real model, and the earlier "no AICore here" verdict was a client-config bug.**
 * A Pixel 10 Pro XL reports `AVAILABLE` with `baseModelName = nano-v3` under the Google sample's
 * default config and under `preference = FULL`. Only `preference = FAST` — the sole variant this
 * codebase ever constructed — answers `FEATURE_NOT_FOUND: Feature 645`, because FAST and FULL are
 * different base models and so different AICore feature ids. Measured at 444–734 ms init,
 * 488–574 ms to first token, 3.0–3.9 s complete. It costs no download; emulators still report
 * `Unavailable` and fall through to deterministic text.
 *
 * **Available is not the same as attached.** `MainActivity.attachOnDeviceAi()` holds the whole
 * wiring — probe, executor, both orchestrators — behind one `ATTACH_ON_DEVICE_AI` constant that is
 * `false`, so the coach and summary run deterministically on every device. The constant is inside
 * the probe call rather than outside it on purpose: on a device reporting `DOWNLOADABLE`,
 * [probeAvailableLocalVendors] awaits an AICore feature fetch, and "inactive" has to mean no
 * network, not merely no visible model.
 *
 * Whether nano-v3 beats the deterministic layer has now been measured per surface, and the answers
 * differ. **Move Coach: no** — 95/100 validator passes against ~89, but ~4.4 s to say what
 * `DeterministicCoach` says instantly, and a hand read finds invention the validator cannot catch.
 * **Game Summary: close enough to be a product decision** — 12/12 at ~12 s, citing exactly the
 * code-chosen turning points, once the bench harness and the prompt were fixed; what still argues
 * against it is that the surface has no response validator at all. See
 * `docs/benchmarks/on-device-ai/game-summary-2026-08.md`.
 */
actual class VendorRouteExecutor : AiRouteExecutor {
    actual override suspend fun execute(route: VendorRoute): OnDeviceTextGenerator? {
        return when (route) {
            // Cached per preference: a fresh instance per execute() would pay ML Kit's model setup
            // on every request. FAST and FULL are different models, hence the key.
            is VendorRoute.MlKitPrompt -> cachedMlKit.getOrPut(route.preference) {
                MlKitPromptGenerator(route.preference)
            }
            is VendorRoute.AppleFoundationModels -> error("iOS route on Android")
            is VendorRoute.LiteRtLm -> error("Desktop/Wasm route on Android")
            // The type stays in the published commonMain API — removing it would be a source break
            // for the React Native consumer — but Android can no longer serve it.
            is VendorRoute.CactusLocal -> null
        }
    }
}

/**
 * Process-wide. Deliberately never cleared — `release()` borrows and returns a generator, it does
 * not destroy one.
 */
private val cachedMlKit = java.util.concurrent.ConcurrentHashMap<ModelPreference, MlKitPromptGenerator>()
