# Implementation plan: En Passant (issue #27)

Target issue: https://github.com/ber4444/compose-multiplatform-chess/issues/27

> Missing: If a pawn moves two squares forward from its starting position and lands
> immediately adjacent to an enemy pawn, the enemy pawn can capture it on the very next
> turn as if it had only moved one square.
> Current behavior: The Pawn movement logic only checks standard diagonal captures and
> doesn't store the concept of the "last move played" to allow an en passant capture.

This plan is self-contained and verified against the code on branch `castling`
(commit `75d0822`). Line numbers refer to that revision; treat them as anchors, not
exact offsets.

## Background you need before editing

- Compose Multiplatform chess app (Kotlin). All game-rules code lives in
  `app/src/commonMain/kotlin/com/example/myapplication/`.
- Board state in `GameUiState` is **parallel lists**: `piecesWhite`/`positionsWhite` and
  `piecesBlack`/`positionsBlack`, indexed together. A position is `Pair<Int, Int>` =
  (row, col): row 0 = rank 8 (Black's back rank), row 7 = rank 1 (White's back rank),
  col 0 = file a. White pawns move toward smaller rows; Black pawns toward larger rows.
- A generated move is `Pair<Pair<Int, Int>, Int>` = (destination square, index of the
  moving piece in the ally lists).
- Capture handling in `GameViewModel.deriveNewGameState` assumes the victim sits **on the
  destination square** (`if (newPosition in enemyPositions)`). En passant is the one move
  where that is false — the victim is beside the destination — which is why it needs
  special-casing rather than falling out of existing logic.
- **Copy the castling pattern.** Castling was added as: a `CastlingRights` field on
  `GameUiState`; a standalone `getCastlingMoves()` generator (Move.kt:376) appended inside
  `getAllLegalMoves()` (Move.kt:361) that performs its own king-safety simulation; and
  special-case handling in `deriveNewGameState`. En passant follows the same shape.
- Player (White) moves enter via `GameViewModel.playerMove`; Black moves via
  `GameViewModel.moveCPU` with a `pickMove` lambda (`pickMoveStockfish` → engine, falling
  back to `pickMoveCPU`). Both funnel into `deriveNewGameState`.

## Step 1 — State: add `enPassantTarget` to `GameUiState`

File: `app/src/commonMain/kotlin/com/example/myapplication/GameUiState.kt`

Add to the `GameUiState` data class:

```kotlin
val enPassantTarget: Pair<Int, Int>? = null
```

Semantics: the square *skipped over* by a pawn that double-stepped on the immediately
preceding half-move — i.e. the square a capturing pawn would land on. This matches the
en passant field of FEN. Null whenever the previous half-move was not a double pawn push.

## Step 2 — Move generation: new `getEnPassantMoves()` in Move.kt

Do **not** change the `Piece.getValidMovesPositions` interface (Piece.kt:14). It has no
access to last-move state, and it is also used by `checkCheck` to enumerate enemy
attacks — en passant must not appear there (an en passant capture can never take a king).
Instead, add a standalone generator mirroring `getCastlingMoves`:

```kotlin
fun getEnPassantMoves(
    enPassantTarget: Pair<Int, Int>?,
    enemyPositions: List<Pair<Int, Int>>,
    enemyPieces: List<Piece>,
    allyPositions: List<Pair<Int, Int>>,
    allyPieces: List<Piece>
): List<Pair<Pair<Int, Int>, Int>>
```

Logic:

1. Return empty if `enPassantTarget == null`.
2. Determine ally color from the ally king's `set` (same trick as `getCastlingMoves`,
   Move.kt:384–387).
3. Locate the victim: for a White capturer the captured Black pawn sits at
   `Pair(target.first + 1, target.second)`; for a Black capturer the captured White pawn
   sits at `Pair(target.first - 1, target.second)`. Verify an enemy piece is there and it
   `is Pawn`; otherwise return empty.
4. Candidate capturers: ally pieces at the victim's row, columns `target.second - 1` and
   `target.second + 1` (bounds-check columns), that are `is Pawn`.
5. **King-safety simulation must happen inside this function.** The generic legality loop
   in `getAllLegalMoves` only removes a victim that sits on the destination square, so it
   cannot model en passant. For each candidate: build simulated ally positions with the
   pawn moved to `target`, and simulated enemy lists with the victim removed from its real
   square, then keep the move only if `!checkCheck(allyKingPos, simEnemyPositions,
   simEnemyPieces, simAllyPositions)`. This correctly rejects the classic horizontal-pin
   case (king and both pawns on the same rank as an enemy rook — capturing would vacate
   two squares at once).
6. Emit `Pair(enPassantTarget, allyPawnIndex)` for each surviving candidate.

## Step 3 — Thread the target through the legal-move pipeline

Add a defaulted parameter `enPassantTarget: Pair<Int, Int>? = null` (defaulting keeps
existing tests compiling) to:

- `getAllLegalMoves` (Move.kt:325) — append
  `legalMoves.addAll(getEnPassantMoves(enPassantTarget, ...))` next to the existing
  castling line (Move.kt:361).
- `getLegalMovesForPiece` (Move.kt:307) — forward to `getAllLegalMoves`.
- `hasLegalMoves` (Move.kt:271) — also append the en passant moves (via
  `getEnPassantMoves`, which is already fully validated). An en passant capture can be the
  only escape from check or the only move preventing stalemate, and this function drives
  checkmate/stalemate detection in `moveCPU`.
- `pickMoveCPU` (Move.kt:116) — forward to `getAllLegalMoves`. Optional polish: in the
  capture-preference filter (Move.kt:135), also treat
  `it.first == enPassantTarget && allyPieces[it.second] is Pawn` as a capture.
- `pickMoveStockfish` (Move.kt:74) — it already receives `gameState`; pass
  `gameState.enPassantTarget` to the validation `getAllLegalMoves` call (Move.kt:98) and
  to both `pickMoveCPU` fallback calls (Move.kt:83, 113).

Update call sites to pass the live value:

- `GameViewModel.playerMove` → `getAllLegalMoves` (GameViewModel.kt:79): add
  `enPassantTarget = gameState.value.enPassantTarget`.
- `GameViewModel.moveCPU` → both `hasLegalMoves` calls (GameViewModel.kt:252 and 264):
  pass `_gameState.value.enPassantTarget`.
- `GameScreen.kt:279` → `getLegalMovesForPiece`: add
  `enPassantTarget = gameState.enPassantTarget` (this makes the target square highlight
  when the player selects an adjacent pawn).

## Step 4 — Execution: `deriveNewGameState` (GameViewModel.kt:301)

Two additions:

1. **Remove the captured pawn.** The existing capture block (GameViewModel.kt:320) won't
   fire because the destination square is empty. Before or after that block (they are
   mutually exclusive), add: if the moving piece is a `Pawn`, the move is diagonal
   (`allyPositions[pieceIndex].second != newPosition.second`),
   `newPosition == _gameState.value.enPassantTarget`, and
   `newPosition !in enemyPositions`, then remove the enemy piece at
   `Pair(allyPositions[pieceIndex].first, newPosition.second)` from
   `mutableEnemyPositions`/`mutableEnemyPieces` (find its index with `indexOf`, remove
   from both lists). Reading the target from `_gameState.value` is consistent with how
   this function already reads `_gameState.value.castlingRights` (line 318). Log the
   capture like the existing block does.
2. **Record the new target.** Near the `movingPiece`/`fromPosition` declarations
   (lines 339–340), compute:

   ```kotlin
   val newEnPassantTarget =
       if (movingPiece is Pawn && abs(newPosition.first - fromPosition.first) == 2)
           Pair((newPosition.first + fromPosition.first) / 2, fromPosition.second)
       else null
   ```

   and add `enPassantTarget = newEnPassantTarget` to **both** `copy()` branches of the
   return (lines 400 and 412). Computing it fresh on every move automatically expires the
   target after one half-move, which is the rule.

No interaction with promotion: an en passant capture lands on row 2 or row 5, never the
back rank, so `isPromotionMove` can't be true for it. The deferred promotion path
(`playerMove` sets `pendingPromotion`, `promotePawn` applies later) still reads the
unchanged pre-move `_gameState.value.enPassantTarget`, so it stays correct.

## Step 5 — FEN: `FenConverter.kt`

Reuse the existing algebraic-square helpers `UciMoveConverter.positionToUciSquare`
(UciMoveConverter.kt:41) and `UciMoveConverter.uciSquareToPosition`
(UciMoveConverter.kt:23) — do not write new coordinate-conversion code.

- `gameStateToFen` (FenConverter.kt:108): replace the hardcoded `val enPassant = "-"`
  with:

  ```kotlin
  val enPassant = gameState.enPassantTarget
      ?.let { UciMoveConverter.positionToUciSquare(it) } ?: "-"
  ```

- `fenToGameState` (FenConverter.kt:119): parse field 4 and include it in the returned
  state:

  ```kotlin
  val enPassantTarget = if (parts.size >= 4 && parts[3] != "-")
      UciMoveConverter.uciSquareToPosition(parts[3]) else null
  ```

  Also update the stale KDoc ("En passant and counters are ignored…").

This fixes the Stockfish integration end-to-end: the engine now *sees* en passant
opportunities in the FEN it receives, and when it replies with an en passant move (e.g.
`e5d6`), `UciMoveConverter.uciMoveToAppMove` needs **no changes** — it resolves the
from-square to a piece index and the to-square to the destination; validation passes via
the updated `getAllLegalMoves`, and Step 4 removes the victim.

## Step 6 — UI/animation: no changes

Captured pieces already vanish from state instantly (only the mover animates via
`PieceAnimationState`); the en passant victim disappearing from its own square matches
existing capture behavior. Do not add a `secondaryPiece` animation for it.

## Step 7 — Tests: new `app/src/commonTest/kotlin/com/example/myapplication/EnPassantTest.kt`

Mirror the style of `CastlingTest.kt` and `PromotionTest.kt`: set up positions with
`FenConverter.fenToGameState(...)`, drive `GameViewModel.playerMove(...)` for White, and
`viewModel.moveCPU(Set.BLACK) { ... }` with an injected `SelectedMove` for Black. Use
`kotlin.test` assertions.

Cases to cover:

1. **Generation (pure function + getAllLegalMoves).** FEN
   `rnbqkbnr/ppp1pppp/8/3pP3/8/8/PPPP1PPP/RNBQKBNR w KQkq d6 0 3` (Black just played
   d7–d5; White pawn on e5). Target d6 = `Pair(2, 3)`. Assert `getAllLegalMoves(...,
   enPassantTarget = Pair(2, 3))` contains `Pair(Pair(2, 3), <index of the e5 pawn>)`,
   and that without the target the move is absent.
2. **Execution via playerMove.** Same FEN, `playerMove(pawnIdx, Pair(2, 3))`. Assert:
   Black has one fewer pawn, no Black piece remains at `Pair(3, 3)`, the White pawn is at
   `Pair(2, 3)`, turn passed to Black, and the new state's `enPassantTarget` is null.
3. **Target set on double push.** From the default `GameUiState()`, play e2→e4
   (`playerMove` with the pawn at `Pair(6, 4)` to `Pair(4, 4)`). Assert
   `enPassantTarget == Pair(5, 4)` and `gameStateToFen(...)` contains ` e3 ` as the
   en passant field.
4. **Expiry.** After a subsequent non-double-push move, assert the target is null and the
   FEN field is `-`.
5. **Pinned en passant is illegal.** FEN `4k3/8/8/r2pP2K/8/8/8/8 w - d6 0 1` (Black rook
   a5, Black pawn d5, White pawn e5, White king h5 — all on rank 5). Assert
   `getAllLegalMoves(..., enPassantTarget = Pair(2, 3))` does NOT contain a move to
   `Pair(2, 3)` by the pawn.
6. **FEN round-trip.** `fenToGameState` of a FEN with `d6` yields
   `enPassantTarget == Pair(2, 3)`; feeding that state to `gameStateToFen` emits `d6`
   again.
7. **Black side via moveCPU.** Black-to-move FEN with a White double-push target, e.g.
   `rnbqkbnr/pppp1ppp/8/8/3Pp3/8/PPP1PPPP/RNBQKBNR b KQkq d3 0 3` (White just played
   d2–d4; Black pawn on e4 can capture to d3 = `Pair(5, 3)`). Inject the en passant
   `SelectedMove(Pair(5, 3), <index of e4 pawn>)` via the `pickMove` lambda and assert the
   White pawn at `Pair(4, 3)` is removed.

## Step 8 — Docs

Add an **En Passant** bullet under "Recent Features" in `CLAUDE.md`, alongside the
Castling and Pawn Promotion entries: mention the `enPassantTarget` state field, the FEN
field now being emitted/parsed, and the off-square capture handling in
`deriveNewGameState`.

## Verification

```bash
./gradlew :app:desktopTest --tests "com.example.myapplication.EnPassantTest"   # fast iteration
./gradlew test                                                                  # all shared tests
# CI parity — the change is not done until all three targets build:
./gradlew :androidApp:assembleDebug :app:assembleAndroidDeviceTest :app:check :app:desktopJar :app:packageDistributionForCurrentOS :app:wasmJsBrowserDistribution
```

Manual check: `./gradlew :app:desktopRun` (needs system `stockfish` installed). Play a
game where a White double push lands next to a Black pawn (or vice versa) and confirm:
(a) selecting the adjacent White pawn highlights the en passant target square,
(b) playing the capture removes the enemy pawn from its actual square,
(c) Stockfish's Black replies include en passant when it is the best move (watch the
`Stockfish FEN:` debug log — the en passant field must show e.g. `e3` after 1. e4).

## Files touched

| File | Change |
|---|---|
| `app/src/commonMain/kotlin/com/example/myapplication/GameUiState.kt` | new `enPassantTarget` field |
| `app/src/commonMain/kotlin/com/example/myapplication/Move.kt` | new `getEnPassantMoves()`; params on `getAllLegalMoves`/`getLegalMovesForPiece`/`hasLegalMoves`/`pickMoveCPU`; wiring in `pickMoveStockfish` |
| `app/src/commonMain/kotlin/com/example/myapplication/GameViewModel.kt` | victim removal + target recording in `deriveNewGameState`; pass target at call sites |
| `app/src/commonMain/kotlin/com/example/myapplication/FenConverter.kt` | emit/parse the en passant FEN field |
| `app/src/commonMain/kotlin/com/example/myapplication/GameScreen.kt` | pass target to `getLegalMovesForPiece` (line 279) |
| `app/src/commonTest/kotlin/com/example/myapplication/EnPassantTest.kt` | new test class |
| `CLAUDE.md` | Recent Features bullet |
