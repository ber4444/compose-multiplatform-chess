# Perft loop brief (verbatim input for the autonomous loop)

> This file is the one-paragraph brief fed verbatim to the loop agent each iteration. It is the
> contract: success is machine-checkable, the localizer is automatic, and the only thing the
> agent is allowed to edit is the move generator. **Do not edit this brief** unless the loop's
> contract itself is changing; in particular, never weaken the "never edit the oracle" rule.

## Loop contract

Run:

```
./gradlew :app:desktopTest --tests "*Perft*"
```

If green, **stop — you are done**. The canonical gate (`PerftTest` / `PerftCanonicalGateTest`) and
the Stockfish localizer (`PerftVsStockfishTest`) both pass, which means the shipped move generator
agrees with chess-programming ground truth at every depth exercised and with Stockfish's own
`go perft` on six canonical positions plus a seeded random walk through midgame/endgame positions.

If red, do exactly this:

1. **Read `build/perft-divergence.txt`** (written by `PerftVsStockfishTest` on failure). It contains
   the exact FEN where the app's move generator diverges from Stockfish, the diverging root move's
   UCI string, the app's subtree count vs. Stockfish's, and a one-ply-deeper trail that narrows
   the search. The deepest `[ply N]` block is the position immediately upstream of the bug.

2. **Form a hypothesis about the move-generation rule it implicates.** The usual suspects, in
   order of how often they bite hand-written generators:
   - **Promotion fan-out** in `perft/Perft.kt` (`legalMovesFor`). Perft must count four leaves
     (Q, R, B, N) for each pawn reaching the back rank; the underlying `getAllLegalMoves` returns
     one. If the divide diff shows the *aggregate* count off by exactly 3× the number of promotion
     moves in the position, the harness is collapsing promotions, not the generator.
   - **En-passant pin / discovered check** in `Move.kt::getEnPassantMoves`. EP is the classic
     perft-diverging move; the horizontal-pin case (capturing the victim exposes the king) must be
     filtered out by the king-safety simulation.
   - **Castling legality** in `Move.kt::getCastlingMoves` (squares-empty, through-check,
     into-check, rights-tracking) and **castling-rights loss when a rook is captured on its home
     square** in `applyMove` (Move.kt) — the `capturedPiece is Rook` branch.
   - **Pawn double-push blocked by an intermediate piece**, and `checkCheck` coverage for every
     attacker type.
   - **Double-counting risk**: `getAllLegalMoves` appends castling/ep lists separately; a
     `(dest, pieceIndex)` collision would inflate counts.

3. **Fix the implicated rule in `app/src/commonMain/kotlin/com/example/myapplication/Move.kt`**
   (or `applyMove` / `applyWinConditions` in the same file). Do not edit anything else.

4. **Re-run the success command.** Repeat until green.

## What you may NEVER do

- **Never edit `perft/PerftPositions.kt` or any expected count.** Those are arithmetic facts from
  chessprogramming.org/Perft_Results, not test fixtures. A failing test means the generator is
  wrong, not the number. The integrity guard (banner comment + this brief + the Stockfish second
  oracle) makes hacking the oracle pointless: even if you change the constant, Stockfish's
  `go perft` will still disagree with the generator.

- **Never weaken an assertion to make it pass.** If `PerftCanonicalGateTest.start_depth_4` is red,
  the fix is in `Move.kt`, not the assertion.

- **Never edit the platform glue.** The `DO NOT TOUCH` fence in `CLAUDE.md` applies: 3D renderers
  (`Chess3DBoardRenderer` actuals), the Stockfish bridges (`BaseStockfishEngine` / `StockfishEngine`
  / `DesktopStockfishEngine` / `WasmStockfishEngine` / Swift `StockfishChessEngine`), and the
  `jvmCommonMain`/`wasmJsMain`/`iosMain` process boundaries. The perft gate exercises
  `commonMain` only.

- **Never optimize the representation "just for perft."** The entire value of this rig is testing
  the *shipped* generator. Don't introduce a bitboard board behind a flag; bound depth instead.

## Failure-mode crib sheet

| Symptom in `perft-divergence.txt` | Likely cause |
|---|---|
| Off by exactly 3× # of promotion moves in the position | `legalMovesFor` not expanding promotions (harness bug, not generator) |
| One root move's count differs, all its child moves also differ | A move *type* (e.g. ep, castle) is being generated wrongly — check the relevant generator |
| Many root moves differ by small amounts | `applyMove` state corruption — castling rights, en-passant target, or halfmove clock not being updated correctly |
| Random-walk test fails but canonical gate passes | Bug only triggers in deep/irregular positions — read the deepest ply trail carefully |
| `StockfishPerft.isAvailable()` returns false | Test was skipped, not failed. Install `stockfish` (Homebrew: `brew install stockfish`) and re-run |
| `build/perft-divergence.txt` is missing | The test failed before reaching the localizer (e.g. canonical gate already red). Fix the canonical failure first; the localizer will then write the trail for any remaining Stockfish-side mismatch |

## Reproducibility

- The random walk uses a fixed seed (`PerftVsStockfishTest.RANDOM_WALK_SEED = 0xC0FFEEL`). A red
  walk reproduces the same path every time; the failing FEN(s) appear in `perft-divergence.txt`.
- The canonical gate has no RNG; failures are deterministic.

## Depth tiers at a glance

| Tier | Class | Depths | When it runs |
|---|---|---|---|
| Portable shallow | `PerftTest` (commonTest) | start d3, others d2/d3 | every PR, every target (`:app:check`) |
| Canonical deep | `PerftCanonicalGateTest` (desktopTest) | start d4, Kiwipete d3, Pos3 d4, Pos4/5/6 d3 | every PR, desktop only |
| Opt-in deeper | `PerftDeepTest` (desktopTest) | start d5, Kiwipete d4, Pos3 d5, Pos4/5/6 d4 | CI nightly (`perft-nightly` job) via `-Dperft.deep=true` |
| Stockfish oracle | `PerftVsStockfishTest` (desktopTest) | d3 divide diff | every PR (stockfish installed via apt on Linux, brew on macOS); nightly on Linux |

> **Note:** all perft tests live in `:chess-core` (moved out of `:app`). Every gradle target below
> uses `:chess-core:desktopTest`, NOT `:app:desktopTest` — the latter matches zero tests post-move.
> See `docs/plans/perft-ci-completion.md` for the full CI story.

Run the opt-in tier locally with:

```
./gradlew :chess-core:desktopTest --tests "*PerftDeepTest*" -Dperft.deep=true
```

## MCP server (executable form of this brief)

The `:perft-mcp` module (`./gradlew :perft-mcp:installDist`) exposes three tools —
`run_perft_gate`, `stockfish_divide`, `read_divergence` — that are the executable form of this
brief. An MCP-aware agent (Claude Code, etc.) can call them instead of parsing this document. See
`docs/plans/perft-mcp-server.md` for details and `.mcp.json.example` for host wiring. **The agent
still cannot edit the oracle (`PerftPositions.kt`); the tools only make the loop faster.**
