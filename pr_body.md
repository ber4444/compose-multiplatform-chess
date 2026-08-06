## Overview
This PR introduces cloud diagnostic capabilities, allowing us to emit, verify, and retain sample diagnostics for the chat and opening pipelines. It also includes several documentation cleanups and resolves recent KMP build failures.

## Features
- **Cloud Diagnostics Wire Models**: Exposes wire models for cloud diagnostics.
- **Corpus State Verification**: Verifies the deployed corpus state.
- **Opening Diagnostics**: Produces and attaches opening diagnostics.
- **Chat Diagnostics**: Emits the final diagnostic event during chat.
- **Sample Retention**: Retains cloud sample diagnostics for further review.

## Tests & Evals
- **Eval Gates**: Gates cloud diagnostics in evals to ensure isolated testing.

## Documentation
- Merges `cloud-eval-honesty-followups.md` into the global chess plan.
- Removes obsolete desktop filament design specifications.
- Removes already implemented cloud diagnostics items from the plan.

## Fixes
- **Build Failure Resolution**: Fixes an ambiguous `CancellationException` instantiation in `ChatViewModel.kt` by correctly rethrowing the caught exception instead of creating a new ambiguous one.
- **Kotlin Native OOM Fix**: Increases Gradle heap memory limit (`-Xmx4096m`) to resolve `java.lang.OutOfMemoryError` encountered during iOS ARM64 framework linking (`:app:linkReleaseFrameworkIosArm64`).
