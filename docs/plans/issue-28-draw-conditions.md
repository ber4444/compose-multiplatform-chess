# Implement Draw Conditions — issue #28 (spec for Antigravity)

Self-contained implementation plan for https://github.com/ber4444/compose-multiplatform-chess/issues/28. Written to be executed by an agent with no prior context on this repo.

## Context

Compose Multiplatform chess app (Kotlin, package `com.example.myapplication`, module `:app`) targeting Android, desktop JVM, and Web/Wasm. The player is White; Black is played by Stockfish or a fallback random CPU. `WinState.DRAW` exists in the enum (`app/src/commonMain/kotlin/com/example/myapplication/GameUiState.kt:55-61`) but is never assigned. Three draw rules are missing:

1. **Threefold repetition** — same position (piece placement + side to move + castling rights + en passant target) occurring 3 times. No position history exists today.
2. **Fifty-move rule** — 100 halfmoves with no pawn move and no capture. `FenConverter.gameStateToFen` hardcodes halfmove clock `0` and fullmove number `1` (`FenConverter.kt:110-111`); `fenToGameState` ignores FEN fields 5–6.
3. **Insufficient material** — immediate draw for K vs K, K+B vs K, K+N vs K, and K+B vs K+B with same-colored bishops.

**Key architecture facts (verified):**
- All moves — player (`playerMove`, line 106), promotion (`promotePawn`, line 133), and CPU (`moveCPU`, line 279) — funnel through the private `deriveNewGameState(...)` in `app/src/commonMain/kotlin/com/example/myapplication/GameViewModel.kt:302-441`. It already detects normal captures (line 321), en passant captures (line 338), pawn double pushes (line 351), and castling-rights updates, and returns the new state via `_gameState.value.copy(...)` at lines 414-440. This is the single hook point for clocks, history, and draw evaluation.
- Checkmate/stalemate are detected lazily at the start of `moveCPU` (lines 241-272). `moveCPU` early-returns when `winState != WinState.NONE` (line 237) and `playerMove` is guarded the same way (line 71), so setting `winState = DRAW` inside `deriveNewGameState` is terminal.
- UI needs **no changes**: `GameScreen.kt:107-150` already shows a `PopupWindow` whenever `winState != NONE`, and `WinState.DRAW` maps to the `no_winner` icon + `game_end_message_no_winner` string → "Game ended in a DRAW!". The text node has `testTag("winnerText")`.
- Coordinates: app `(row, col)` where row 0 = rank 8, row 7 = rank 1, col 0 = file a. `Pair(7,4)` = e1.
- Piece indexes follow FEN scan order (row 0 → 7, left → right) for FEN-loaded states. Default `GameUiState()`: White Ng1 = index 6, e2-pawn = index 12; Black Nb8 = 1, Ng8 = 6.
- `pickMoveStockfish` (Move.kt) builds FEN from the live state — once clocks are tracked, Stockfish automatically receives real values (free accuracy win).
- Wasm target exists: all new code must live in `commonMain`, no JVM-only APIs.

**Behavioral decision:** threefold repetition and the fifty-move rule auto-trigger the draw (no claim UI exists; the issue allows "claimed/triggered"). Insufficient material is immediate per the issue.

## Step 1 — `GameUiState.kt`: add tracking fields

In `data class GameUiState`, after `val enPassantTarget: Pair<Int, Int>? = null` (line 51):

```kotlin
val halfmoveClock: Int = 0,      // halfmoves since last capture or pawn move (fifty-move rule)
val fullmoveNumber: Int = 1,     // starts at 1, increments after each Black move
// Repetition keys (first four FEN fields) of every position since the last irreversible
// move (capture/pawn move). Invariant: when non-empty, the last element is the current position.
val positionHistory: List<String> = emptyList()
```

All existing construction sites use named args, so nothing else changes.

**Important:** do NOT seed `positionHistory` in the `GameViewModel` constructor or `resetGame()`. Existing UI tests (`GameScreenTest.testStalemate`, `assertChessboardVisibleIn`) construct `GameUiState` with mismatched pieces/positions list lengths; computing a FEN key on those states throws `IndexOutOfBoundsException`. Seeding is done lazily in Step 4.

## Step 2 — `FenConverter.kt`: extract `positionKey`, emit/parse real clocks

File: `app/src/commonMain/kotlin/com/example/myapplication/FenConverter.kt`

1. Extract lines 56–109 of `gameStateToFen` (board array through the `enPassant` val) into a new public function:
   ```kotlin
   /** First four FEN fields (placement, active color, castling, en passant) —
    *  the position identity used for threefold-repetition detection. */
   fun positionKey(gameState: GameUiState): String {
       // ... existing code unchanged ...
       return "$piecePlacement $activeColor $castling $enPassant"
   }
   ```
   then:
   ```kotlin
   fun gameStateToFen(gameState: GameUiState): String =
       "${positionKey(gameState)} ${gameState.halfmoveClock} ${gameState.fullmoveNumber}"
   ```
   Delete the hardcoded `val halfmoveClock = 0` / `val fullmoveNumber = 1` and the stale comment at line 99.
2. In `fenToGameState`, before the `return GameUiState(...)`:
   ```kotlin
   val halfmoveClock = parts.getOrNull(4)?.toIntOrNull() ?: 0
   val fullmoveNumber = parts.getOrNull(5)?.toIntOrNull() ?: 1
   ```
   Pass both into the returned `GameUiState`. Update the KDoc at line 118 (clocks are no longer ignored). Leave `positionHistory` empty here.

Existing FenConverter/EnPassant test assertions are `startsWith`/`contains`-based and remain valid (defaults 0/1 match the old hardcoded output).

## Step 3 — new file `DrawConditions.kt` (pure rule functions)

File: `app/src/commonMain/kotlin/com/example/myapplication/DrawConditions.kt` — top-level functions, matching the Move.kt style:

```kotlin
package com.example.myapplication

const val FIFTY_MOVE_RULE_HALFMOVES = 100

/** K vs K, K+minor vs K, or K+B vs K+B with same-colored bishops.
 *  K+N vs K+N and two minors on one side are NOT treated as draws (mate is possible). */
fun isInsufficientMaterial(state: GameUiState): Boolean {
    val white = state.piecesWhite.zip(state.positionsWhite)
    val black = state.piecesBlack.zip(state.positionsBlack)
    val nonKings = (white + black).filter { it.first !is King }
    if (nonKings.any { it.first is Pawn || it.first is Rook || it.first is Queen }) return false
    return when {
        nonKings.isEmpty() -> true                       // K vs K
        nonKings.size == 1 -> true                       // K + minor vs K
        nonKings.size == 2 &&
            nonKings.all { it.first is Bishop } &&
            white.any { it.first is Bishop } && black.any { it.first is Bishop } &&
            nonKings.map { (it.second.first + it.second.second) % 2 }.distinct().size == 1 -> true
        else -> false
    }
}

/** Fifty-move rule. Deferred while the side to move is in check so a mating 100th halfmove
 *  is scored as a win (mate is detected lazily in moveCPU); a non-mate escape draws next move. */
fun isFiftyMoveDraw(state: GameUiState): Boolean {
    if (state.halfmoveClock < FIFTY_MOVE_RULE_HALFMOVES) return false
    val sideToMoveInCheck = if (state.turn == Set.WHITE) state.inCheckWhite else state.inCheckBlack
    return !sideToMoveInCheck
}

/** Relies on the invariant that positionHistory ends with the current position's key.
 *  Safe even in check: a repeated position (same side to move) had legal moves before. */
fun isThreefoldRepetition(state: GameUiState): Boolean {
    val key = state.positionHistory.lastOrNull() ?: return false
    return state.positionHistory.count { it == key } >= 3
}

/** Returns the state with winState = DRAW when any draw condition holds. */
fun applyDrawConditions(state: GameUiState): GameUiState =
    if (state.winState == WinState.NONE &&
        (isInsufficientMaterial(state) || isFiftyMoveDraw(state) || isThreefoldRepetition(state))
    ) state.copy(winState = WinState.DRAW) else state
```

## Step 4 — `GameViewModel.deriveNewGameState`: track clocks/history, evaluate draws

Four edits, all inside `deriveNewGameState` (`GameViewModel.kt:302-441`):

1. After `var updatedRights = _gameState.value.castlingRights` (line 319): `var captureOccurred = false`.
2. Set `captureOccurred = true` in **both** capture branches: inside `if (newPosition in enemyPositions)` (after the `removeAt` calls, line 337) and inside the en passant branch's `if (index != -1)` block (after line 344).
3. After `val fromPosition = allyPositions[pieceIndex]` (line 349):
   ```kotlin
   val newHalfmoveClock = if (captureOccurred || movingPiece is Pawn) 0
                          else _gameState.value.halfmoveClock + 1
   val newFullmoveNumber = _gameState.value.fullmoveNumber + if (turn == Set.BLACK) 1 else 0
   ```
   (Promotions and en passant are pawn moves, so the clock resets on both automatically.)
4. Change `return when (turn) { ... }` (lines 414-440) to `val movedState = when (turn) { ... }`, adding `halfmoveClock = newHalfmoveClock, fullmoveNumber = newFullmoveNumber` to **both** `copy(...)` branches, then:
   ```kotlin
   // Captures/pawn moves are irreversible: earlier positions can never recur, so reset history.
   // Otherwise lazily seed the pre-move position (covers fresh games, resetGame, and FEN-loaded
   // states without touching the constructor — some tests build GameUiState with mismatched
   // piece/position lists that must never reach positionKey).
   val priorHistory = if (newHalfmoveClock == 0) emptyList()
       else _gameState.value.positionHistory.ifEmpty { listOf(FenConverter.positionKey(_gameState.value)) }
   val newState = movedState.copy(positionHistory = priorHistory + FenConverter.positionKey(movedState))
   return applyDrawConditions(newState)
   ```

No changes to `resetGame()`, the constructor, `playerMove`, `moveCPU`, `Move.kt`, or any UI file.

## Step 5 — unit tests (commonTest, kotlin.test)

Patterns: build states via `FenConverter.fenToGameState(fen)` or `GameUiState(...)`; drive White with `vm.playerMove(idx, pos)` and Black with `vm.moveCPU(Set.BLACK) { _, _, _, _ -> SelectedMove(pos, idx) }` (see `CastlingTest.kt` / `EnPassantTest.kt` for the idiom). Run with `./gradlew :app:desktopTest --tests "com.example.myapplication.DrawConditionsTest" --tests "com.example.myapplication.FenConverterTest"`.

**Add to `app/src/commonTest/kotlin/com/example/myapplication/FenConverterTest.kt`:**

| Test | Steps | Assertions |
|---|---|---|
| clock round-trip | `fenToGameState("4k3/8/8/8/8/8/8/4K3 w - - 12 34")` | `halfmoveClock == 12`, `fullmoveNumber == 34`; `gameStateToFen(state)` equals the input FEN |
| missing clock fields default | `fenToGameState("4k3/8/8/8/8/8/8/4K3 w - -")`; also round-trip `STARTING_FEN` | clock 0, fullmove 1; `gameStateToFen(fenToGameState(STARTING_FEN)) == STARTING_FEN` |

**New `app/src/commonTest/kotlin/com/example/myapplication/DrawConditionsTest.kt`:**

| # | Test | Setup / moves (app row, col) | Assertions |
|---|---|---|---|
| 1 | quiet moves increment clock; fullmove after Black | `GameViewModel()`; `playerMove(6, Pair(5,5))` (Ng1-f3); then `moveCPU(Set.BLACK){ SelectedMove(Pair(2,0), 1) }` (Nb8-a6) | after W: clock 1, fullmove 1; after B: clock 2, fullmove 2; FEN ends `" 2 2"` |
| 2 | pawn move resets clock | `GameViewModel()`; `playerMove(12, Pair(4,4))` (e2-e4) | clock 0, `positionHistory.size == 1` |
| 3 | capture resets clock and history | FEN `"4k3/8/8/3p4/8/8/8/3RK3 w - - 5 10"`; `rookIdx = positionsWhite.indexOf(Pair(7,3))`; `playerMove(rookIdx, Pair(3,3))` (Rxd5) | clock 0, history size 1, winState NONE, fullmove 10 |
| 4 | en passant capture resets clock | FEN `"rnbqkbnr/ppp1pppp/8/3pP3/8/8/PPPP1PPP/RNBQKBNR w KQkq d6 7 3"` (clock 7 is artificial); `pawnIdx = positionsWhite.indexOf(Pair(3,4))`; `playerMove(pawnIdx, Pair(2,3))` | clock 0, history size 1 |
| 5 | fifty-move draw at clock 100 | FEN `"4k2r/8/8/8/8/8/8/4K2R w - - 99 80"`; white rook idx 1; `playerMove(1, Pair(4,7))` (Rh1-h4, quiet, no check) | clock 100, `winState == DRAW` |
| 6 | no draw below 100 | same FEN with `99 → 98`; same move | clock 99, winState NONE |
| 7 | fifty-move deferred while in check | FEN `"4k3/8/8/8/8/8/8/4K2R w - - 99 80"`; `playerMove(1, Pair(0,7))` (Rh1-h8+); then `moveCPU(Set.BLACK){ SelectedMove(Pair(1,3), 0) }` (Ke8-d7) | after Rh8+: clock 100, winState NONE, `inCheckBlack == true`; after Kd7: clock 101, `winState == DRAW` |
| 8 | pawn move at 99 prevents draw | FEN `"4k3/8/8/8/8/8/4P3/4K3 w - - 99 80"`; `pawnIdx = positionsWhite.indexOf(Pair(6,4))`; `playerMove(pawnIdx, Pair(5,4))` | clock 0, winState NONE |
| 9 | threefold via knight shuffle | `GameViewModel()`; repeat ×2: `playerMove(6, Pair(5,5))`, `moveCPU(B){ Pair(2,5), 6 }` (Ng8-f6), `playerMove(6, Pair(7,6))`, `moveCPU(B){ Pair(0,6), 6 }` | winState NONE after halfmove 4 (start position's 2nd occurrence incl. lazy seed); `DRAW` after halfmove 8 (3rd occurrence) |
| 10 | repetition key includes castling rights | FEN `"r3k2r/8/8/8/8/8/8/R3K2R w KQkq - 0 1"` (rook idx 2 on each side); cycle = `playerMove(2, Pair(6,7))` (Rh1-h2), `moveCPU(B){ Pair(1,7), 2 }` (Rh8-h7), `playerMove(2, Pair(7,7))`, `moveCPU(B){ Pair(0,7), 2 }`; run 2 full cycles, then Rh1-h2 + Rh8-h7 once more | NONE after 8 halfmoves (start placement recurred but rights changed KQkq → Qq, so only 2 matching keys); `DRAW` at halfmove 10 — third occurrence of the (Rh2, Rh7, Qq, White-to-move) position |
| 11 | insufficient material positives | `isInsufficientMaterial(fenToGameState(f))` for: `"4k3/8/8/8/8/8/8/4K3 w - - 0 1"`, `"4k3/8/8/8/8/8/8/2B1K3 w - - 0 1"`, `"4k3/8/8/8/8/8/8/1N2K3 w - - 0 1"`, `"4kn2/8/8/8/8/8/8/4K3 w - - 0 1"`, `"4kb2/8/8/8/8/8/8/2B1K3 w - - 0 1"` (f8 + c1 bishops, both dark) | all `true` |
| 12 | insufficient material negatives | `"2b1k3/8/8/8/8/8/8/2B1K3 w - - 0 1"` (opposite-color bishops), `"1n2k3/8/8/8/8/8/8/1N2K3 w - - 0 1"` (KN vs KN), `"4k3/8/8/8/8/8/8/R3K3 w - - 0 1"`, `"4k3/8/8/8/8/4P3/8/4K3 w - - 0 1"`, `"4k3/8/8/8/8/8/8/1NB1K3 w - - 0 1"` (two minors one side) | all `false` |
| 13 | integration: capture leaves K+B vs K → DRAW | FEN `"4k3/8/8/6n1/8/8/8/2B1K3 w - - 10 40"`; `bishopIdx = positionsWhite.indexOf(Pair(7,2))`; `playerMove(bishopIdx, Pair(3,6))` (Bc1xg5 — clear diagonal, no resulting check) | `winState == DRAW`, `piecesBlack.size == 1`, clock 0 |
| 14 | threefold helper contract | simple state `s`; `isThreefoldRepetition(s.copy(positionHistory = List(3){ FenConverter.positionKey(s) }))` and `List(2){...}` | `true` / `false` |

## Step 6 — UI tests (`app/src/androidDeviceTest/kotlin/com/example/myapplication/GameScreenTest.kt`)

Reuse the file's existing patterns (`createComposeRule`, `boardSquareTag(...)`, `waitUntil`):

1. **`testDrawDialogDisplayed`** — mirror the existing game-over test (~line 45): `setContent { MyApplicationTheme { GameScreen(WindowWidthSizeClass.Medium, GameViewModel(GameUiState(winState = WinState.DRAW))) } }`, then `onNodeWithText("Game ended in a DRAW!").assertIsDisplayed()`.
2. **`testInsufficientMaterialDrawViaClicks`** — load FEN `"4k3/8/8/6n1/8/8/8/2B1K3 w - - 10 40"` into a `GameViewModel`, `setContent` as above; click `boardSquareTag(SquareType.WhitePiece, 7, 2)` (bishop on c1), then `boardSquareTag(SquareType.PossibleCapture, 3, 6)` (knight on g5 — capture squares are tagged `SquareType.PossibleCapture` in `Board()`, GameScreen.kt ~lines 324-329). Then follow `testStalemate`'s pattern: `waitUntil(5_000)` on `onAllNodesWithTag("winnerText", useUnmergedTree = true)` being non-empty, and assert `onNodeWithText("Game ended in a DRAW!").assertIsDisplayed()`. (The dialog appears immediately — `winState` is set synchronously by `playerMove`; Black's CPU move never runs because `moveCPU` early-returns on `winState != NONE`.)

## Step 7 — docs

Update `CLAUDE.md` → "Recent Features" with a bullet: **Draw detection** — threefold repetition (`positionHistory` of FEN position keys, cleared on irreversible moves), fifty-move rule (real `halfmoveClock`/`fullmoveNumber`, now emitted/parsed by `FenConverter` and sent to Stockfish), and insufficient material — all evaluated in `deriveNewGameState` via `applyDrawConditions` (`DrawConditions.kt`), setting `WinState.DRAW`.

No new string resources, no platform-specific code, no build-file changes.

## Verification

1. `./gradlew :app:desktopTest --tests "com.example.myapplication.DrawConditionsTest" --tests "com.example.myapplication.FenConverterTest"` — new tests.
2. `./gradlew :app:desktopTest` then `./gradlew test` — full suite. Pre-validated interactions with existing tests:
   - `PromotionTest` "white promotion flow" promotes to a Knight leaving K+N vs K → state becomes DRAW, but the test asserts only the piece type and makes no further move — still passes. If it's ever extended, switch its FEN to include extra material.
   - `GameScreenTest.testStalemate` and `assertChessboardVisibleIn` use malformed states; the lazy history seeding (Step 4) keeps them away from `positionKey`.
   - `FenConverterTest`/`EnPassantTest` string assertions remain valid (defaults 0/1 match old hardcoded output).
3. CI parity: `./gradlew :androidApp:assembleDebug :app:assembleAndroidDeviceTest :app:check :app:desktopJar :app:packageDistributionForCurrentOS :app:wasmJsBrowserDistribution` — all three targets must build.
4. With a device/emulator: `./gradlew :app:connectedAndroidDeviceTest` for the two new UI tests.
5. Manual smoke (optional): `./gradlew :app:desktopRun`, capture down to K+B vs K and confirm the "Game ended in a DRAW!" popup.

## Accepted simplifications (document as code comments where noted)

- Repetition keys use the raw `enPassantTarget` (set after every double push even when no en passant capture is actually legal). Strict FIDE counts en passant only when capturable; the raw field is the standard conservative simplification — it under-detects repetition, never over-detects.
- Draws auto-trigger rather than being claimable (no claim UI exists).
- If stalemate coincides with clock ≥ 100, `moveCPU`'s stalemate check may not run first and the popup says DRAW instead of STALEMATE — both render the same no-winner icon/message format.

## Handoff

This document is the deliverable for Antigravity. On approval, save a copy into the repo at `docs/plans/issue-28-draw-conditions.md` (a `docs/` folder already exists) so Antigravity can be pointed at it directly.
