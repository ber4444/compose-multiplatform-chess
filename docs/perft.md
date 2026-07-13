# Perft verification rig

This branch ships a perft (performance test) verification rig for the chess engine: an arithmetic
ground-truth gate that proves the move generator is correct, plus an autonomous loop that can drive
a buggy generator to correctness with zero human judgment.

Perft counts the number of leaf nodes in the legal-move tree to a fixed depth. From the standard
start position the answers are arithmetic facts, not test fixtures:

| depth | leaf nodes |
|------:|-----------:|
| 1 | 20 |
| 2 | 400 |
| 3 | 8,902 |
| 4 | 197,281 |
| 5 | 4,865,609 |
| 6 | 119,060,324 |

Canonical values for six standard positions (Start, Kiwipete, Positions 3–6) are published at
[chessprogramming.org/Perft_Results](https://www.chessprogramming.org/Perft_Results). The rig checks
the shipped generator against those constants **and** against Stockfish's own `go perft` on a random
walk of positions — so it catches bugs in positions nobody hard-coded.

## How to run it

```bash
# The per-commit gate (fast — runs on every PR):
./gradlew :chess-core:desktopTest --tests "*Perft*"

# The deep tier (nightly-only; minutes, not seconds):
./gradlew :chess-core:desktopTest --tests "*PerftDeepTest*" -Dperft.deep=true
```

> **Target is `:chess-core:desktopTest`, not `:app:desktopTest`.** The perft tests moved to
> `:chess-core`; a `--tests "*Perft*"` filter on `:app:desktopTest` matches zero tests post-move.

The gate needs a `stockfish` binary on PATH for Oracle 2 (the Stockfish cross-check). Install it
with `brew install stockfish` (macOS) or `sudo apt-get install -y stockfish` (Linux). Without it,
Oracle 2 cleanly assumes-skips — the canonical gate (Oracle 1) needs no binary and is the portable
backbone.

## What the rig is

Three layers, each a stronger check than the last:

### 1. Oracle 1 — canonical static counts (`commonTest`, runs on every target)

`PerftPositions.kt` holds the six standard positions with their published perft counts.
`PerftTest.kt` runs `perft(fen, depth)` against those constants. This is the always-on regression
gate — it runs under `./gradlew :chess-core:check` on every target (desktop, iOS sim, JS, Wasm).

> **`PerftPositions.kt` is the oracle. Never edit it.** Those numbers are arithmetic facts. A
> failing test means the generator is wrong, not the number. The file has a `DO NOT EDIT` banner and
> the Stockfish second oracle makes hacking the constant pointless anyway.

### 2. Oracle 2 — Stockfish `go perft` divide diff (`desktopTest`, localizer)

`PerftVsStockfishTest.kt` computes perft-divide (per-root-move subtree counts) with the app's
generator and diffs it against Stockfish's own `go perft`. On mismatch it writes a localized trail
to `build/perft-divergence.txt`: the exact FEN where they diverge, the diverging root move, the
app's count vs. Stockfish's, and a one-ply-deeper trail that narrows the search. A seeded random
walk (`RANDOM_WALK_SEED = 0xC0FFEEL`) exercises midgame/endgame positions nobody hard-coded.

`StockfishPerft.kt` is the test-only UCI helper that spawns `stockfish` and parses its divide output.

### 3. The deep tier (`desktopTest`, nightly-only)

`PerftDeepTest.kt` runs deeper perft (start d5, Kiwipete d4, etc.) gated behind `-Dperft.deep=true`.
Too slow for the per-commit gate (~100s); runs in the CI nightly and on manual dispatch.

## Depth tiers at a glance

| Tier | Class | Depths | When it runs |
|---|---|---|---|
| Portable shallow | `PerftTest` (commonTest) | start d3, others d2/d3 | every PR, every target (`:chess-core:check`) |
| Canonical deep | `PerftCanonicalGateTest` (desktopTest) | start d4, Kiwipete d3, Pos3 d4, Pos4/5/6 d3 | every PR, desktop only |
| Opt-in deeper | `PerftDeepTest` (desktopTest) | start d5, Kiwipete d4, Pos3 d5, Pos4/5/6 d4 | CI nightly via `-Dperft.deep=true` |
| Stockfish oracle | `PerftVsStockfishTest` (desktopTest) | d3 divide diff | every PR (stockfish via apt on Linux, brew on macOS); nightly on Linux |

## The enabling refactor: pure `applyMove`

A perft harness must, millions of times, call `applyMove(state, move) → newState`. The state
transition previously lived inside `GameViewModel.deriveNewGameState(...)`, which was `private`,
read from the ViewModel's live `StateFlow`, and was loaded with side effects (autosave, SAN
generation, draw-history tracking).

This branch extracts a pure top-level function:

```kotlin
fun applyMove(
    state: GameUiState,
    pieceIndex: Int,
    newPosition: Pair<Int, Int>,
    promotion: PromotionType? = null
): GameUiState
```

`deriveNewGameState` now delegates to it. The extracted function returns **only** raw board state
(pieces, castling rights, en passant target, halfmove clock) — all side effects stay behind in
`deriveNewGameState`. This is plain game-rules code (not platform glue); extracting it improved
testability and left all UI/animation behavior identical.

## The autonomous loop

The rig is designed to be driven by an agent (feature-dev / ralph-loop / `/loop`) with no human in
the loop. The contract:

1. Run the gate. If green, stop.
2. If red, read `build/perft-divergence.txt` for the exact FEN + diverging move.
3. Form a hypothesis about the implicated rule (the [loop brief](plans/perft-loop-brief.md) has a
   crib sheet of usual suspects).
4. Fix `Move.kt` in `:chess-core`. **Never edit the oracle.**
5. Re-run. Repeat until green.

The full verbatim brief (the one fed to the loop agent) is in [`docs/plans/perft-loop-brief.md`](plans/perft-loop-brief.md).

## The MCP server (`:perft-mcp`)

The loop brief is a markdown document an agent parses. The `:perft-mcp` module turns it into tools:
a thin stdio MCP server so an agent calls `run_perft_gate` instead of reading prose.

| Tool | What it does |
|------|-------------|
| `run_perft_gate(deep=false)` | Runs `./gradlew :chess-core:desktopTest --tests "*Perft*"`. Returns `{passed, summary, divergenceFileExists}`. |
| `stockfish_divide(fen, depth=3)` | Spawns `stockfish`, returns `{moves, total, oracleUnavailable}`. Depth clamped to 6; 120s timeout. Never throws — degrades to structured "unavailable". |
| `read_divergence()` | Reads `build/perft-divergence.txt`, or a structured "no divergence recorded" message. |

It's an **adapter, not an engine** — no dependency on `:app` or `:chess-core`; it shells out to
gradle and stockfish exactly as a human would. Each tool description embeds the hard rules verbatim.

```bash
./gradlew :perft-mcp:test :perft-mcp:installDist
```

Point an MCP host at it via `.mcp.json` (see `.mcp.json.example`). Details in
[`docs/plans/perft-mcp-server.md`](plans/perft-mcp-server.md).

**The agent still cannot edit the oracle; the tools only make the loop faster.**

## CI

The perft gate runs in CI on every PR and nightly:

- **Per-commit (Linux)**: `:chess-core:check` includes `:chess-core:desktopTest`, which runs the
  canonical gate + Oracle 2. Stockfish installed via `apt`. A `$GITHUB_STEP_SUMMARY` step reports
  whether the Stockfish localizer ran or skipped (never a silent green).
- **Per-commit (macOS)**: `:chess-core:desktopTest` alongside `:app:desktopTest` (which has the
  macOS-only Filament bridge tests). Stockfish installed via `brew`. Same summary step.
- **Nightly**: `perft-nightly` job (cron `0 7 * * *` + `workflow_dispatch`) runs the full `*Perft*`
  filter with `-Dperft.deep=true` and uploads `build/perft-divergence.txt` on failure.

See [`docs/plans/perft-ci-completion.md`](plans/perft-ci-completion.md) for the full CI story,
including the breakage the chess-core move caused and how it was fixed.

## File map

| File | Source set | Purpose |
|------|-----------|---------|
| `Move.kt` (edit) | commonMain | Pure top-level `applyMove`; `deriveNewGameState` delegates |
| `perft/Perft.kt` | commonTest | Kernel: `legalMovesFor` (promotion fan-out), `perft`, `perftDivide` |
| `perft/PerftPositions.kt` | commonTest | Canonical FEN + expected counts — **ground truth, do not edit** |
| `perft/PerftTest.kt` | commonTest | Oracle 1 gate (runs on all targets) |
| `perft/PerftCanonicalGateTest.kt` | desktopTest | Oracle 1 deeper (desktop only) |
| `perft/PerftDeepTest.kt` | desktopTest | Opt-in depth-5/6 (nightly) |
| `perft/StockfishPerft.kt` | desktopTest | UCI `go perft` helper |
| `perft/PerftVsStockfishTest.kt` | desktopTest | Oracle 2: divide diff + random-walk + divergence report |
| `perft-mcp/` | main + test | MCP server adapter (3 tools + tests) |
| `docs/plans/perft-loop-brief.md` | — | Verbatim brief for the autonomous loop |
| `docs/plans/perft-autonomous-verification.md` | — | The original design plan |
| `docs/plans/perft-ci-completion.md` | — | CI breakage + fix record |
| `docs/plans/perft-mcp-server.md` | — | MCP server plan + tool docs |
