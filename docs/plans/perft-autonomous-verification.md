# Plan: Perft-based autonomous verification for the chess engine

**Status:** proposed
**Scope:** `commonMain` game-rules kernel + `commonTest`/`desktopTest` harnesses + an autonomous loop
**Verified against:** branch `3d`, the move-generation code in `app/src/commonMain/kotlin/com/example/myapplication/`. Line numbers are anchors, not exact offsets.

---

## 1. Why this is the highest-leverage agentic investment in the repo

The game logic in `commonMain` — move generation, legality, check/checkmate/stalemate, castling, en passant, promotion, FEN — is **pure deterministic Kotlin with ground truth baked into the domain**: perft (performance test) node counts.

Perft counts the number of leaf nodes in the legal-move tree to a fixed depth. From the standard start position the answers are not opinions, they are arithmetic:

| depth | leaf nodes |
|------:|-----------:|
| 1 | 20 |
| 2 | 400 |
| 3 | 8,902 |
| 4 | 197,281 |
| 5 | 4,865,609 |
| 6 | 119,060,324 |

Canonical values exist for dozens of test positions (the six "standard" ones are reproduced in §6). This makes the engine the **ideal target for an autonomous loop** (ralph-loop / feature-dev / `/loop`) and the Outcomes-evaluator pattern, because there is no LLM-as-judge rubric to write and no human in the loop:

- An agent writes a perft harness, runs it, and compares against a constant.
- When the number is wrong, **perft divide** (per-root-move subtree counts) localizes the divergence to a single move and position.
- A second oracle — Stockfish's own `go perft <n>` over UCI — gives ground truth for *arbitrary* positions, so the agent can random-walk into bug-rich positions it never hard-coded.

Almost nothing in typical app/UI code gives you arithmetic ground truth like this. Treating the engine as ordinary UI work wastes it. The deliverable of this plan is a self-correcting verification rig plus the loop wiring that lets an agent drive the move generator to perft-correctness with zero human judgment.

---

## 2. The one real obstacle: move application is not a pure function

A perft harness must, millions of times, do exactly this:

```
count(state, depth):
    if depth == 0: return 1
    total = 0
    for move in legalMoves(state):
        total += count(applyMove(state, move), depth - 1)
    return total
```

The repo **has** the `legalMoves` half already, in pure form:

- `getAllLegalMoves(enemyPositions, enemyPieces, allyPositions, allyPieces, castlingRights, enPassantTarget)` — `Move.kt:336`. Pure, no ViewModel coupling. Returns `List<Pair<Pair<Int,Int>, Int>>` = `(destinationSquare, allyPieceIndex)`. Castling moves (`getCastlingMoves`, `Move.kt:389`) and en-passant moves (`getEnPassantMoves`, `Move.kt:467`) are appended; both already do their own king-safety simulation, so the returned list is fully legal.

The repo does **not** have the `applyMove` half in pure form. The state transition lives in `GameViewModel.deriveNewGameState(...)` (`GameViewModel.kt:325`), and it is:

- **`private`** to `GameViewModel`, and
- **not a function of its arguments** — it reads `_gameState.value.castlingRights`, `_gameState.value.enPassantTarget`, `_gameState.value.halfmoveClock`, `_gameState.value.turn`, `_gameState.value.fullmoveNumber`, and returns `_gameState.value.copy(...)` (`GameViewModel.kt:342`, `:363`, `:377-379`, `:433`, `:444-480`). It mutates around the ViewModel's live `StateFlow`.
- **loaded with massive side effects** — recent lifecycle PRs added `autosave()` disk I/O, SAN generation for `MoveRecord`, and `positionHistory` tracking for draw rules directly into this function.

So **Step 1 of this plan is an enabling refactor**: extract a pure top-level `applyMove(state: GameUiState, pieceIndex: Int, to: Pair<Int,Int>, promotion: PromotionType?): GameUiState` into `Move.kt`, and have `GameViewModel.deriveNewGameState` delegate to it. 

**CRITICAL WARNING:** The extracted `applyMove` function must ONLY return the raw board state (pieces, castling rights, en passant target, halfmove clock). All side effects (autosave, SAN generation, draw history) MUST be left behind in `deriveNewGameState`. If an agent attempts to run a perft loop through the full `deriveNewGameState` instead of a pure `applyMove`, it will trigger millions of autosave disk I/O calls and immediately crash or hang.

This is plain game-rules code, **not** platform glue — the `DO NOT TOUCH` fence in `CLAUDE.md` is about the 3D `actual` renderers and the Stockfish bridges, not this. Extracting it improves testability and leaves all UI/animation behavior identical.

Before extracting, **enumerate every `_gameState.value.X` read inside `deriveNewGameState`** (`castlingRights`, `enPassantTarget`, `halfmoveClock`, `turn`, `fullmoveNumber`) and map each to a `state.` field. A single missed read won't fail the existing UI suite — those tests drive one move at a time off the live flow — but it will silently corrupt deep perft counts, the hardest class of bug to localize. Also note the signatures differ: `deriveNewGameState` takes the ally/enemy split as eight explicit parameters, while `applyMove(state, …)` must derive that split from `state.turn`. Pick one home for the split-derivation (either the delegating `deriveNewGameState` computes it and forwards, or `applyMove` does) and keep it the only place that decision lives.

> If the refactor is judged too risky to do up front, the fallback is to write `applyMove` as a *new, independent* pure function in `commonTest` that reproduces `deriveNewGameState`'s rules. This is worse — it can drift from the production transition and you'd be perft-testing a copy, not the shipped code — so it is the fallback, not the plan. Prefer the extract-and-delegate path.

---

## 3. Background a fresh agent needs

- Coordinates: a square is `Pair<Int,Int>` = `(row, col)`. **Row 0 = rank 8** (Black's back rank), **row 7 = rank 1** (White's back rank), **col 0 = file a**. (`FenConverter.kt:7-12`.) `WHITE_KING_HOME = Pair(7,4)` = e1 (`Move.kt:11`).
- Board state is **parallel lists**: `piecesWhite`/`positionsWhite`, `piecesBlack`/`positionsBlack`, indexed together (`GameUiState.kt:30-42`). Plus `turn`, `castlingRights`, `enPassantTarget`, `halfmoveClock`, `fullmoveNumber`.
- A generated move is `(destinationSquare, allyPieceIndex)`. `getAllLegalMoves` takes an **ally/enemy split**, not white/black — the harness must pass `ally = sideToMove`. For `state.turn == WHITE`, ally lists are the white lists; for `BLACK`, the black lists.
- FEN ↔ state: `FenConverter.fenToGameState(fen)` (`:122`) and `gameStateToFen(state)` (`:115`). En-passant target is set on every double push and emitted/parsed correctly.
- Move → UCI string: `UciMoveConverter.appMoveToUci(from, to)` (`UciMoveConverter.kt:89`). It does **not** append a promotion char — the harness adds it (see §4.1).
- `PromotionType` (`PromotionType.kt:3`) has a `uciChar` field (`q`/`r`/`b`/`n`) and `toPiece(set)`. Used for promotion expansion and divide strings.
- Stockfish over UCI: `BaseStockfishEngine.sendCommand(...)` is `protected` (`BaseStockfishEngine.kt:180`); `DesktopStockfishEngine` (desktopMain) launches the system/Homebrew `stockfish`. Stockfish answers `go perft <n>` with one `<from><to>[promo]: <count>` line per root move and a final `Nodes searched: <total>`.

---

## 4. Component design

### 4.1 The perft kernel — `commonTest`

New file `app/src/commonTest/kotlin/com/example/myapplication/perft/Perft.kt`.

```
fun legalMovesFor(state): List<(pieceIndex, to, promotion?)>
fun perft(state, depth): Long
fun perftDivide(state, depth): Map<String /*uci*/, Long>   // root move -> subtree count
```

`legalMovesFor` wraps `getAllLegalMoves` with the ally/enemy mapping from `state.turn`, and — critically — **expands promotions**. `getAllLegalMoves` returns *one* move for a pawn reaching the back rank (`deriveNewGameState` defaults it to Queen, `GameViewModel.kt:411-415`). Perft must count **four** leaf moves there (Q, R, B, N). Detect with `isPromotionMove(piece, to)` (`Move.kt:24`) and emit one entry per `PromotionType`. This promotion fan-out is the single most common reason a hand-rolled perft is off — get it right first.

`perft` recurses through the pure `applyMove` from §2. `perftDivide` returns each root move's UCI string (`appMoveToUci(from, to)` + `promotion?.uciChar`) mapped to its subtree count — this is what makes a mismatch *localizable* instead of just "wrong total".

### 4.2 Oracle 1 — canonical static counts (the always-on gate), `commonTest`

New file `app/src/commonTest/.../perft/PerftPositions.kt`: a table of `(name, fen, listOf(expectedDepth1, expectedDepth2, ...))` for the six standard positions in §6.

> **This file is ground truth. The autonomous loop must never edit it.** Put a banner comment at the top: `// CANONICAL PERFT REFERENCE VALUES — DO NOT EDIT. These are arithmetic facts, not test fixtures. If a test fails, the generator is wrong, not these numbers.` (See §7, the integrity guard — the loop's biggest failure mode is "fixing" the test by rewriting the oracle.)

New file `PerftTest.kt` runs `perft(fenToGameState(fen), d)` for each position/depth and asserts equality. This runs on **every target** (`./gradlew test`), needs no engine, and is the regression gate that proves the generator stays correct forever after.

**Depth budget.** The parallel-list representation copies lists per node (`getAllLegalMoves` filters/`toMutableList`s on every move), so it is allocation-heavy. Keep the always-on gate fast:

- Start position depth 4 (197,281) — the headline gate.
- Kiwipete depth 3 (97,862), Position 3 depth 4 (43,238), Positions 4/5/6 depth 3.
- Tag depth-5/6 runs (start 4,865,609; Kiwipete 4,085,603) as an **opt-in** test (system property / separate `--tests` filter) for a CI nightly, not the per-commit gate.

Set a concrete wall-clock target for the always-on gate (aim **< ~30s**) so it doesn't quietly get `@Ignore`d. Because generation is allocation-heavy, the same depth costs very differently per target: run the full canonical gate on desktop/JVM, and on the **wasm/native** test runners drop to a lighter depth (e.g. start depth 3 = 8,902) so `./gradlew test` stays fast on every platform instead of timing out on the slowest one.

Do **not** build a faster bitboard board "just for perft" — the entire value is testing the *shipped* generator. Bound depth instead.

### 4.3 Oracle 2 — Stockfish `go perft` divide differ (the localizer), `desktopTest`

New files under `app/src/desktopTest/.../perft/`:

- `StockfishPerft.kt` — a tiny desktop helper that starts `stockfish`, sends `position fen <fen>` + `go perft <n>`, and parses the `move: count` lines into `Map<String, Long>`. Because `sendCommand` is `protected`, either add a minimal `desktopMain` subclass that exposes a `perft(fen, depth)` method, or spawn the process directly with `ProcessBuilder` in the test helper (test-only, so the latter is fine and avoids touching production engine classes). When parsing, skip the leading blank line and the trailing `Nodes searched: <total>` summary — only `<move>: <count>` lines go into the map. Keep the `<total>`, though: it's a free independent cross-check against your own `perft(state, n)` sum.
- `PerftVsStockfishTest.kt` — Oracle 2. For a set of positions (the six canonical + random-walked ones), compute `perftDivide(state, n)` and Stockfish's `go perft n`, then **diff the two maps**. On mismatch, emit a localized report: which root move's subtree count differs, by how much, and recurse one ply into *that* move to find the next diverging move. Write the trail to `build/perft-divergence.txt`.

This is the oracle that makes the loop autonomous on *new* bugs: the agent doesn't need a hard-coded answer, it asks Stockfish, and the divide diff hands it the exact `fen` + move where the app's generator and a correct generator part ways.

**Random-walk generator** (in the same test source set): from the start position, play N random legal moves (using the app's own generator) to reach varied midgame/endgame positions, then perft-divide-diff each against Stockfish at a shallow depth. Seed the RNG so failures are reproducible; log the seed. Guard against a generator bug poisoning the walk: after each random move, assert the resulting position is legal (the side that just moved did not leave its own king capturable) and **skip + log** any position that fails rather than handing it to Stockfish — `go perft` on an illegal FEN returns numbers that *look* like divergences but aren't, which sends the loop chasing a phantom. (The self-check filter in `getAllLegalMoves`, `Move.kt:363`, makes this unlikely but not impossible if the bug under test is exactly a legality gap.)

### 4.4 What perft will probably catch first (informed by the current code)

These are the usual suspects in hand-written generators, and where the current code has subtle, simulation-based handling worth confirming:

- **Promotion fan-out** in the harness itself (§4.1) — rule it out first.
- **En-passant pin / discovered check**: `getEnPassantMoves` (`Move.kt:467`) simulates removing the victim and calls `checkCheck` — this should handle the horizontal-pin illegal-ep case, but ep is the classic perft-diverging move; depths 4–5 of Kiwipete and Position 3 exercise it hard. Note: While recent lifecycle PRs tightened en passant handling (e.g. `FenConverter` now correctly parses/emits en passant targets), perft is still needed to verify these interactions at depth.
- **Castling legality**: through/into-check and squares-empty checks (`getCastlingMoves`, `Move.kt:389`), plus **castling-rights loss when a rook is captured on its home square** (handled in the capture branch, `GameViewModel.kt:350-358` — make sure it survives the §2 extraction). Note: Castling was also tightened recently, but similarly needs deep verification.
- **Pawn double-push blocked by an intermediate piece**, and `checkCheck` coverage for every attacker type (`Move.kt:173`).
- **Double-counting risk**: `getAllLegalMoves` appends castling/ep lists separately (`Move.kt:373-374`); confirm no `(dest, pieceIndex)` collisions inflate counts.

The point isn't to pre-fix these — it's that perft + divide will pinpoint whichever are actually wrong, and the loop fixes them one localized divergence at a time.

---

## 5. The autonomous loop wiring

The loop's job: **drive `getAllLegalMoves` + `applyMove` to perft-correctness, editing only the generator, never the oracle.**

**Success criterion (machine-checkable, no LLM judge):**
```
./gradlew :app:desktopTest --tests "*Perft*"
```
green ⇒ done. (commonTest perft runs under the desktop target too.)

**Per-iteration localizer:** before each fix attempt the agent runs the canonical gate; on failure it runs the Stockfish divide differ (§4.3) which writes `build/perft-divergence.txt` — a concrete `fen` + diverging move + "app says X, Stockfish says Y". The agent reads that file, forms a hypothesis about the move-generation rule it implicates, fixes `Move.kt` (or the extracted `applyMove`), and re-runs.

**Loop mechanics — options, in order of preference:**
1. **feature-dev / ralph-loop agent** pointed at a one-paragraph brief (below) with the success command and the divergence report as its feedback signal. Best fit: it self-corrects against arithmetic, exactly the pattern the Outcomes evaluator rewards.
2. **`/loop` self-paced** running the gate + differ each iteration until green, for a lighter-weight, user-visible cadence.

**The loop brief** (save as `docs/plans/perft-loop-brief.md`, fed verbatim to the agent each iteration):
> Run `./gradlew :app:desktopTest --tests "*Perft*"`. If green, stop — you are done. If red, read `build/perft-divergence.txt` for the exact position (FEN) and move where the app's move generator diverges from Stockfish. Fix the move-generation rule in `app/src/commonMain/.../Move.kt` (or the extracted `applyMove`) that causes it. **Never edit `PerftPositions.kt` or any expected count — those are arithmetic ground truth; a failing test means the generator is wrong, not the number.** Re-run and repeat.

---

## 6. Canonical reference positions (Oracle 1 table)

```
Start    rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1
         d1=20 d2=400 d3=8902 d4=197281 d5=4865609 d6=119060324
Kiwipete r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq - 0 1
         d1=48 d2=2039 d3=97862 d4=4085603 d5=193690690
Pos3     8/2p5/3p4/KP5r/1R3p1k/8/4P1P1/8 w - - 0 1
         d1=14 d2=191 d3=2812 d4=43238 d5=674624 d6=11030083
Pos4     r3k2r/Pppp1ppp/1b3nbN/nP6/BBP1P3/q4N2/Pp1P2PP/R2Q1RK1 w kq - 0 1
         d1=6 d2=264 d3=9467 d4=422333 d5=15833292
Pos5     rnbq1k1r/pp1Pbppp/2p5/8/2B5/8/PPP1NnPP/RNBQK2R w KQ - 1 8
         d1=44 d2=1486 d3=62379 d4=2103487 d5=89941194
Pos6     r4rk1/1pp1qppp/p1np1n2/2b1p1B1/2B1P1b1/P1NP1N2/1PP1QPPP/R4RK1 w - - 0 10
         d1=46 d2=2079 d3=89890 d4=3894594
```
(Source: chessprogramming.org/Perft_Results.)

---

## 7. Risks and guards

- **Loop edits the oracle ("reward hacking").** The single biggest failure mode: the agent makes the test pass by changing the expected number. Guards: (a) the `DO NOT EDIT` banner in `PerftPositions.kt`; (b) the brief in §5 forbids it explicitly; (c) optionally a pre-commit/CI check that `PerftPositions.kt`'s expected values are unchanged from a pinned hash. The Stockfish second oracle (§4.3) also makes hacking pointless — it'll disagree regardless of the constant.
- **The §2 refactor changes game behavior.** Mitigate by extracting mechanically (delegate, don't rewrite) and gating on the existing suite — `CastlingTest`, `EnPassantTest`, `PromotionTest`, `DrawConditionsTest`, `MoveTest`, `GameViewModelTest` all already exercise `deriveNewGameState` through the ViewModel and must stay green.
- **Performance.** Allocation-heavy generation bounds practical depth; §4.2 keeps the per-commit gate ≤ low-hundreds-of-thousands of nodes and pushes depth-5/6 to a nightly.
- **Stockfish availability.** Oracle 2 is desktop-only and needs a `stockfish` binary; `DesktopStockfishEngineTest` already assumes one. The canonical gate (Oracle 1) needs nothing and is the portable backbone — Oracle 2 degrades to "skipped" without a binary.

---

## 8. Milestones

- **M0 — Enable.** Extract pure `applyMove` from `deriveNewGameState` (§2); delegate; existing suite green.
- **M1 — Kernel + canonical gate.** `Perft.kt` (with promotion fan-out) + `PerftPositions.kt` + `PerftTest.kt`. Run it; record the first divergence (there will likely be one — that's the point).
- **M2 — Stockfish localizer.** `StockfishPerft.kt` + `PerftVsStockfishTest.kt` + random-walk + `build/perft-divergence.txt`.
- **M3 — Wire the loop.** `perft-loop-brief.md`; point feature-dev / ralph-loop / `/loop` at the success command; let it close divergences to green.
- **M4 — Broaden.** Add more positions, enable depth-5/6 nightly in CI (`.github/workflows/android-tests.yml`), keep the gate in `./gradlew test`.

---

## 9. File manifest

| File | Source set | Purpose |
|------|-----------|---------|
| `Move.kt` (edit) | commonMain | Extract pure `applyMove`; `deriveNewGameState` delegates |
| `perft/Perft.kt` | commonTest | Kernel: `legalMovesFor` (promotion fan-out), `perft`, `perftDivide` |
| `perft/PerftPositions.kt` | commonTest | Canonical FEN + expected counts — **ground truth, do not edit** |
| `perft/PerftTest.kt` | commonTest | Oracle 1 gate (runs on all targets) |
| `perft/StockfishPerft.kt` | desktopTest | UCI `go perft` helper |
| `perft/PerftVsStockfishTest.kt` | desktopTest | Oracle 2: divide diff + random-walk + divergence report |
| `docs/plans/perft-loop-brief.md` | — | Verbatim brief for the autonomous loop |
