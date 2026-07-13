# Perft CI Completion

The CI shell from PR #49's M4 already existed, but the "move perft tests to chess-core" commit
(`dd9ae6c`) silently broke it. This plan closed the gap.

## What the audit found

The perft tests moved from `:app` to `:chess-core`, but the CI workflows still invoked
`:app:desktopTest --tests "*Perft*"`. Verified empirically: a `--tests "*Perft*"` filter on
`:app:desktopTest` matches **zero** tests post-move and `BUILD SUCCESSFUL` — the per-commit and
nightly perft gates were silent green no-ops.

A second breakage: `PerftDeepTest` gates on `System.getProperty("perft.deep")`, but the
`perft.*` system-property forwarding block lived only in `app/build.gradle.kts` —
`chess-core/build.gradle.kts` had none. So even with the right task target, the deep tier would
assume-skip forever.

## What changed

1. **`.github/workflows/android-tests.yml`**
   - `perft-nightly`: `:app:desktopTest` → `:chess-core:desktopTest` (the filter now matches the
     actual tests). The existing `upload-artifact` for `build/perft-divergence.txt` was already
     correct (root-relative path matches what `PerftVsStockfishTest` writes).
   - `apple-kotlin`: added `:chess-core:desktopTest` alongside `:app:desktopTest` (the latter stays —
     it has the macOS-only Filament bridge + renderer tests). Added `brew install stockfish` so the
     Stockfish localizer (Oracle 2) actually runs per-commit instead of silently skipping.
   - `instrumented-tests` (Linux): added `stockfish` to the existing apt install (single package,
     negligible wall-time) so Oracle 2 runs per-commit on Linux too, not just nightly.
   - Added a "Report perft localizer status" step to both the Linux and macOS jobs that surfaces
     ran-vs-skipped in `$GITHUB_STEP_SUMMARY` — a skipped localizer is now a visible warning, never
     a silent green.
   - Fixed the nightly's test-report upload path (`app/build/` → `chess-core/build/`).

2. **`chess-core/build.gradle.kts`**
   - Mirrored the `perft.*` system-property forwarding from `app/build.gradle.kts` onto the
     `desktopTest` task, so `-Dperft.deep=true` actually reaches `PerftDeepTest`.

## Verification

- `./gradlew :chess-core:desktopTest --tests "*Perft*"` → green (canonical gate 6/6, commonTest
  15/15, Oracle 2 2/2 with stockfish present; deep tier 6/6 skipped without the flag).
- `./gradlew :chess-core:desktopTest --tests "*PerftDeepTest*" -Dperft.deep=true` → green (6/6 ran,
  0 skipped — the property forwarding works; ~1m40s, which is why it's nightly-only).
- **Divergence-artifact path (local):** injected a synthetic move-generator bug in a throwaway
  worktree, ran `PerftVsStockfishTest`, confirmed (a) the test failed, (b) `build/perft-divergence.txt`
  was produced with the expected FEN + diverging move + app-vs-Stockfish counts + multi-ply trail.
  Reverted the injection. The remote `upload-artifact` step is standard GHA and already correctly
  wired, so no remote dispatch was needed.
