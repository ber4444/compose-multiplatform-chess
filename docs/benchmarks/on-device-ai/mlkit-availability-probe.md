# ML Kit availability probe — re-testing the #131 verdict

## Why this exists

PR #131 concluded that ML Kit / AICore is unusable on Android. The availability evidence was one
error string from a Pixel 10 Pro XL:

```
[ErrorCode 606] FEATURE_NOT_FOUND: Feature 645 is not available
```

That was measured through exactly one client configuration — the one `MlKitPromptGenerator` builds:

```kotlin
modelConfig {
    releaseStage = ModelReleaseStage.STABLE
    preference = ModelPreference.FAST
}
```

Google's quickstart never builds that. `OpenPromptActivity.initGenerator()` in
[`googlesamples/mlkit@master:android/genai`](https://github.com/googlesamples/mlkit/tree/master/android/genai)
is:

```kotlin
val generationConfig = generationConfig { }
generativeModel = Generation.getClient(generationConfig)
```

An empty config. Grepping the entire sample for `modelConfig`, `ModelPreference`,
`ModelReleaseStage` or `releaseStage` returns **zero hits**.

`ModelReleaseStage.STABLE` is
[documented as the default](https://developers.google.com/ml-kit/genai/prompt/android/select-model),
so that half of our config is a no-op. `preference` is not: FAST and FULL select different base
models and therefore different AICore feature ids. The sample's own bug report for a default-config
client ([googlesamples/mlkit#985](https://github.com/googlesamples/mlkit/issues/985)) shows feature
**636**, not 645 — a different id, and so a different provisioning question.

The device matters too. The
[Prompt API launch post](https://developer.android.com/blog/posts/ml-kit-s-prompt-api-unlock-custom-on-device-gemini-nano-experiences)
says Gemini Nano v3 shipped on the Pixel 10 series and suggests non-Pixel-10 users prototype with
Gemma 3n locally. A Pixel 10 Pro XL is the device that *should* work, which points at the client
config rather than the hardware.

## The question

**Does the default configuration report `AVAILABLE` on the device where `preference = FAST` reported
`FEATURE_NOT_FOUND: Feature 645`?**

- **Yes** → the availability half of #131 is a client-config bug, not a device verdict.
  `MlKitPromptGenerator` should drop its `modelConfig`, and the Android coach's "no model exists"
  premise needs revisiting.
- **No, every variant is UNAVAILABLE** → #131 stands, on considerably stronger evidence than one
  configuration.

Note this is only about *availability*. #131's separate quality finding — that the Cactus catalogue
models lost to the deterministic text on latency and on truth — was measured against a different
runtime and is untouched by this.

## What the probe does

`MlKitAvailabilityDiagnostic.kt` (in `:onDeviceAi` androidMain) runs the sample's sequence verbatim
for each variant, in the sample's order:

1. build the client
2. `getBaseModelName()` — the sample surfaces this in its debug bar
3. `checkStatus()`
4. `AVAILABLE` → done; `UNAVAILABLE` → done; **anything else** (`DOWNLOADABLE` *and* `DOWNLOADING`)
   → `download()`, collected to termination so `DownloadCompleted` / `DownloadFailed` is awaited
5. `checkStatus()` again, plus `getTokenLimit()` if it came back `AVAILABLE`
6. `close()` the client

Variants:

| Variant | Config | Note |
|---|---|---|
| `sample-default` | `generationConfig { }` | the Google sample, verbatim — never tried in #131 |
| `preference=FAST` | `STABLE` + `FAST` | what ships today; the only config #131 measured |

Each variant is reported independently — a variant that throws does not abort the others, so a
failure in the first cannot hide the result of the second.

## Running it

Debug builds only (`FLAG_DEBUGGABLE`), driven by an intent extra on `MainActivity`, mirroring the
existing `bench_iterations` hook.

```bash
./gradlew :androidApp:installDebug

adb logcat -c
adb shell am start -n io.github.ber4444.chess/com.example.myapplication.MainActivity \
    --ez mlkit_diagnostic true
adb logcat -s MlKitDiag
```

The activity runs the probe and calls `finish()`, so nothing else in the app initialises. The report
is also written to `files/bench/mlkit-availability.txt` inside the app sandbox, which survives the
`finish()`:

```bash
adb exec-out run-as io.github.ber4444.chess cat files/bench/mlkit-availability.txt
```

Pull the file rather than relying on scrollback if you only get one shot at a cold, freshly
provisioned device — the first run is the interesting one.

## Reading the result

```
--- variant: sample-default (no modelConfig)
    baseModelName: <name or n/a>
    checkStatus:   AVAILABLE | DOWNLOADABLE | DOWNLOADING | UNAVAILABLE | threw: …
    download():
      - started (N bytes)
      - completed
    checkStatus after download: …
    tokenLimit:    …
    VERDICT: AVAILABLE | not available
```

The number to watch is the **feature id inside a thrown `GenAiException`**. If `sample-default` and
`preference=FAST` both fail but cite *different* feature ids, the device is provisioned for neither
and the ids tell you which two it was asked for. If they cite the *same* id, the `modelConfig`
hypothesis is dead and #131's conclusion holds.

`FEATURE_NOT_FOUND` immediately after a device reset is also a known false negative — AICore needs
time to pull its server-side configuration after setup or a reset. If the device was recently wiped,
leave it online for a while and re-run before recording anything.

## Recording the answer

Append the outcome to `android-model-latency-2026-08.md` and update the ML Kit paragraphs in
`CLAUDE.md` and `VendorRouteExecutor.android.kt`. Then **delete this probe** — it is a measurement
rig, not a feature, and `MlKitProbeVariant` / `MlKitProbeReport` are public API on a published
module for as long as it stays.

## Caveat on scope

Even a green result here does not light up the Android coach on its own. `probeAvailableLocalVendors()`
has three callers on Android and all three are under `app/src/androidMain/.../bench/`; `MainActivity`
builds `MoveCoachManager` and `GameSummaryManager` with no orchestrator and never constructs a
`VendorRouteExecutor`. Wiring that up is a separate change, and it should not be made until this
probe says there is something to wire.
