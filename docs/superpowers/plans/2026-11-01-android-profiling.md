# Android profiling and profiles

Goal: capture cold launch and common board/navigation flows, identify slow work from traces, and ship generated Baseline and Startup Profiles.

Use the existing `:macrobenchmark` producer and Android application consumer with AndroidX Baseline Profile plugin 1.5.0. Add Android-only tracing 2.0.5 (`tracing-wire` auto initialization) and named startup sections. Keep the core Compose-free and do not change renderer or engine bridges. Preserve existing local leak investigation changes.

- [x] Build and record the current profileable app using Android CLI `android-profiler`; retain the exact trace and device/build metadata. *Recorded on the `benchmark` build, API 37 arm64 emulator; the traces were not kept in the repo.*
- [x] Add Android tracing instrumentation, separate startup and interaction profile journeys, and uncompiled/profile-compiled startup benchmarks.
- [x] Generate real profiles on API 33+ and verify app-owned rules, startup-only scope, and packaged profile artifacts in an R8 release APK. *23,428 baseline and 17,563 startup rules; `assets/dexopt/baseline.prof` is packaged.*
- [x] Analyze traces and compare compilation modes on the same APK. Report emulator limitations and any unverified suspected bottlenecks. *Stockfish started inline in `onCreate` was 344 of 646 ms; it now starts once, off the main thread (traced first launch 646 → 315 ms). Uncompiled and profile-required time to first frame were equal on the emulator (median ~280 ms), so the profile's gain is unmeasured. The remaining large frame (414 ms) is SceneView building its engine during composition, in fenced 3D glue.*
- [x] Run repository-required cross-target build/check command and Android device tests. Record failures explicitly. *Passes on AGP 9.1.1 and again on 9.5.0.*

Verification: on-device profile collection, mandatory-baseline compilation benchmark, APK profile assets, R8 startup-layout report, and the repository CI command. Physical-device timing is a follow-up if only an emulator is available; emulator numbers are diagnostic observations rather than shipping performance claims.
