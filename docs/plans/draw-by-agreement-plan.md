# Draw by Agreement — Implementation Plan (GitHub issue #29)

> Self-contained plan for an AI coding agent. Repo: `compose-multiplatform-chess`, branch `draw_conditions`. All paths are relative to the repo root.

## Context

[Issue #29](https://github.com/ber4444/compose-multiplatform-chess/issues/29): the app has automatic draw detection (fifty-move, threefold repetition, insufficient material in `DrawConditions.kt`) but no way for either side to **offer** a draw. This adds:

1. An **"Offer Draw" button** for the player (White). Black (engine/CPU) accepts or declines based on **Stockfish evaluation**, falling back to a **material-balance heuristic** when no engine is available (Wasm has no engine; engines can fail).
2. **Black proactively offering a draw** in drawish positions; the player accepts/declines via a dialog (mirroring the existing `pendingPromotion` → `PromotionDialog` blocking pattern).
3. Accepting sets the existing `WinState.DRAW`, which already triggers the game-over popup in `GameScreen.kt:107-150`.

This is a Compose Multiplatform app (Kotlin 2.3.x) with three targets: Android, desktop JVM, Wasm. **Every change must compile on all three.** All decision logic must live in `commonMain` as pure Kotlin (no `java.*`); only `jvmCommonMain` (`BaseStockfishEngine`) may do process I/O.

## Key existing code (verified)

| What | Where |
|---|---|
| `GameUiState` immutable data class, `WinState` enum (`DRAW` exists) | `app/src/commonMain/kotlin/com/example/myapplication/GameUiState.kt` |
| `GameViewModel` — plain class, `scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)`, StateFlows `gameState/animState/viewState/stockfishEnabled`, `playerMove()`, `promotePawn()/cancelPromotion()` (block-on-decision pattern), `animationEnd()` (triggers Black's move at line 173), `moveCPU()`, private `deriveNewGameState()` (calls `applyDrawConditions` at line 460), `setAutoPlay()`, `resetGame()` | `app/src/commonMain/kotlin/com/example/myapplication/GameViewModel.kt` |
| `ChessEngine` interface: `getBestMove(fen): String?`, `close()` | `app/src/commonMain/kotlin/com/example/myapplication/ChessEngine.kt` |
| `BaseStockfishEngine` — UCI over process; private `lineQueue: LinkedBlockingQueue<String>` fed by reader thread; `waitForBestMove()` polls for `bestmove` lines and **discards `info` lines**; embedded fallback when `process == null` | `app/src/jvmCommonMain/kotlin/com/example/myapplication/BaseStockfishEngine.kt` |
| `GameScreen` — game-over `PopupWindow` (line 107), `PromotionDialog` shown when `pendingPromotion != null` (line 152), control rows with Autoplay checkbox + Reset button (lines 172-201), autoplay `LaunchedEffect` (line 204), `PopupWindow`/`PromotionDialog` composables (lines ~472-510, testTag pattern `promotion_choice_X`) | `app/src/commonMain/kotlin/com/example/myapplication/GameScreen.kt` |
| Draw detection helpers | `app/src/commonMain/kotlin/com/example/myapplication/DrawConditions.kt` |
| `FenConverter.gameStateToFen` / `fenToGameState` / `positionKey` | `app/src/commonMain/kotlin/com/example/myapplication/FenConverter.kt` |
| Unit tests (kotlin.test, run on all targets; fast path = desktopTest) | `app/src/commonTest/kotlin/com/example/myapplication/` (MoveTest, GameViewModelTest, DrawConditionsTest, PromotionTest, …) |
| Android Compose UI tests (`createComposeRule`, testTags, `waitUntil`) | `app/src/androidDeviceTest/kotlin/com/example/myapplication/GameScreenTest.kt` |
| Strings (compose resources, `stringResource(Res.string.x)`, generated pkg `game.app.generated.resources`) | `app/src/commonMain/composeResources/values/strings.xml` |

There is **no existing piece-value/material helper** — define one (Step 4).

## Step 1 — Extend `ChessEngine` (commonMain)

`ChessEngine.kt` — add a method **with a default implementation** (existing anonymous mocks in `MoveTest.kt` and `CastlingTest.kt` must keep compiling):

```kotlin
/**
 * Position evaluation in centipawns from WHITE's perspective (positive = White better).
 * Mate-in-N maps to ±(100000 - N). Null = unavailable (callers fall back to material balance).
 */
fun evaluate(fen: String): Int? = null
```

## Step 2 — New `UciEvaluation.kt` (commonMain, pure — wasm-safe and commonTest-testable)

`app/src/commonMain/kotlin/com/example/myapplication/UciEvaluation.kt`, object with:

- `const val MATE_SCORE_CP = 100_000`
- `parseInfoScore(line: String): Int?` — parses `info ... score cp X ...` and `info ... score mate Y ...` (tolerate `lowerbound`/`upperbound` suffixes; non-`info` lines and `info` lines without `score` → null). Returns side-to-move centipawns.
- `mateToCp(matePlies: Int): Int` — symmetric: `0 → -MATE_SCORE_CP` (we are mated), otherwise `sign(plies) * (MATE_SCORE_CP - abs(plies))` (e.g. `mate 3 → 99997`, `mate -2 → -99998`).
- `isWhiteToMove(fen: String): Boolean` — FEN field 2 != `"b"`.
- `toWhitePerspective(scoreCp: Int, whiteToMove: Boolean): Int` — negate when Black to move.

## Step 3 — Implement `evaluate` in `BaseStockfishEngine` (jvmCommonMain)

- Companion consts: `EVAL_DEPTH = 12`, `EVAL_TIMEOUT_MS = 5000L`.
- `override fun evaluate(fen: String): Int?`:
  - `if (!isReady || process == null) return null` — embedded-fallback mode has no real engine; the caller's material heuristic takes over.
  - `sendCommand("position fen $fen")`; `sendCommand("go depth $EVAL_DEPTH")`; drain via new private `waitForEvaluation(timeoutMs)`; convert with `UciEvaluation.toWhitePerspective(score, UciEvaluation.isWhiteToMove(fen))`. Catch `IOException` → log + null.
- `private fun waitForEvaluation(timeoutMs: Long): Int?` — modeled on existing `waitForBestMove` (deadline loop over `lineQueue.poll(remaining, MILLISECONDS)`): remember the score of the **last** line where `UciEvaluation.parseInfoScore` is non-null; return it when a line starting with `"bestmove"` arrives. **Must consume the `bestmove` line** — otherwise it stays in `lineQueue` and corrupts the next `getBestMove` call. Null on timeout.
- No changes to `StockfishEngine` (androidMain) or `DesktopStockfishEngine` (desktopMain) — they inherit it.

## Step 4 — New `DrawAgreement.kt` (commonMain, pure functions)

`app/src/commonMain/kotlin/com/example/myapplication/DrawAgreement.kt`:

```kotlin
const val DRAW_ACCEPT_THRESHOLD_CP = -100     // Black accepts unless ahead by > 1 pawn
const val DRAW_OFFER_EVAL_WINDOW_CP = 60      // Black offers when |eval| <= this
const val DRAW_OFFER_MIN_FULLMOVE = 20
const val DRAW_OFFER_MIN_HALFMOVE_CLOCK = 8
const val DRAW_OFFER_COOLDOWN_FULLMOVES = 10

fun pieceValueCp(piece: Piece): Int            // Pawn 100, Knight 320, Bishop 330, Rook 500, Queen 900, King 0
fun materialBalanceCp(state: GameUiState): Int // sum(white) - sum(black), White's perspective
fun evaluatePositionCp(engine: ChessEngine?, state: GameUiState): Int =
    engine?.evaluate(FenConverter.gameStateToFen(state)) ?: materialBalanceCp(state)
fun shouldBlackAcceptDraw(evalCp: Int): Boolean = evalCp >= DRAW_ACCEPT_THRESHOLD_CP
fun shouldBlackOfferDraw(evalCp: Int): Boolean = abs(evalCp) <= DRAW_OFFER_EVAL_WINDOW_CP
fun blackDrawOfferPreconditions(state: GameUiState): Boolean  // cheap gates BEFORE engine I/O:
    // winState == NONE && !autoPlay && drawOffer == null && pendingPromotion == null
    // && fullmoveNumber >= DRAW_OFFER_MIN_FULLMOVE && halfmoveClock >= DRAW_OFFER_MIN_HALFMOVE_CLOCK
    // && (lastDrawOfferFullmove == 0 || fullmoveNumber - lastDrawOfferFullmove >= DRAW_OFFER_COOLDOWN_FULLMOVES)
fun canOfferDraw(state: GameUiState): Boolean  // White's button enabled-state + offerDraw() guard:
    // turn == WHITE && winState == NONE && !autoPlay && pendingPromotion == null
    // && drawOffer == null && fullmoveNumber > lastDrawOfferFullmove   // 1 offer per fullmove (anti-spam)
```

## Step 5 — Extend `GameUiState` (defaults keep all existing code/tests working)

Add after `positionHistory`:

```kotlin
val drawOffer: Set? = null,            // side with an unresolved offer pending (Set is the WHITE/BLACK enum)
val drawOfferDeclinedBy: Set? = null,  // drives "declined" feedback text; cleared on next move
val lastDrawOfferFullmove: Int = 0     // fullmoveNumber of most recent offer (0 = never); cooldown anchor
```

`resetGame()` re-creates `GameUiState()`, so reset clears everything automatically.

## Step 6 — `GameViewModel` changes

New functions:

- `fun requestDrawOffer() { scope.launch { offerDraw() } }` — UI entry point; `evaluate()` is blocking process I/O and must stay off the UI thread. **Do not** make `offerDraw()` itself async — tests call it synchronously.
- `fun offerDraw()` — guard with `if (!canOfferDraw(_gameState.value)) return`; set `drawOffer = Set.WHITE, lastDrawOfferFullmove = fullmoveNumber`; compute `evaluatePositionCp(chessEngine, state)`; if `shouldBlackAcceptDraw(eval)` → `copy(winState = WinState.DRAW, drawOffer = null)`, else → `copy(drawOffer = null, drawOfferDeclinedBy = Set.BLACK)`. (Race-safe: a concurrent second call no-ops on `canOfferDraw`.)
- `fun tryBlackDrawOffer(): Boolean` — return false unless `turn == Set.BLACK` && `blackDrawOfferPreconditions(state)` (cheap gates first — no engine I/O) && `shouldBlackOfferDraw(evaluatePositionCp(...))`; on true set `drawOffer = Set.BLACK, lastDrawOfferFullmove = fullmoveNumber`.
- `fun acceptDrawOffer()` — guard `drawOffer == Set.BLACK && winState == WinState.NONE`; → `copy(drawOffer = null, winState = WinState.DRAW)`.
- `fun declineDrawOffer()` — guard `drawOffer == Set.BLACK`; clear `drawOffer`, then `gameMoves?.cancel(); gameMoves = scope.launch { moveBlackWithEngine() }` so Black proceeds with its move.
- `private fun moveBlackWithEngine()` — extract the existing inline `moveCPU { pickMoveStockfish(chessEngine, _gameState.value, ...) }` block from `animationEnd()`.

Edits to existing functions:

- `animationEnd()` BLACK branch (line 173) → `if (tryBlackDrawOffer()) return; moveBlackWithEngine()`. (`startUserTurn()` unchanged — autoplay never offers because preconditions check `!autoPlay`.)
- `playerMove()` — next to the `pendingPromotion` guard (line 74) add `if (_gameState.value.drawOffer != null) return`.
- `setAutoPlay()` (line 52) — also clear the offer: `copy(autoPlay = newVal, pendingPromotion = null, drawOffer = null)` (the autoplay `LaunchedEffect` then drives `startUserTurn()`, which makes Black's deferred move since turn is still BLACK).
- `deriveNewGameState()` — add `drawOffer = null, drawOfferDeclinedBy = null` to **both** the `Set.WHITE` and `Set.BLACK` copy branches (lines ~421-450): defensively clears stale offers and clears the "declined" feedback on the next move. `lastDrawOfferFullmove` intentionally persists (cooldown).

## Step 7 — Strings

`app/src/commonMain/composeResources/values/strings.xml`:

```xml
<string name="offer_draw_button">Offer Draw</string>
<string name="draw_offer_prompt">Black offers a draw</string>
<string name="accept_button">Accept</string>
<string name="decline_button">Decline</string>
<string name="draw_offer_declined">Black declined the draw offer</string>
```

(Generated `Res.string.*` accessors appear after any `:app` compile; import from `game.app.generated.resources`.)

## Step 8 — UI in `GameScreen.kt`

1. After the `pendingPromotion` block (line 152-158):
   ```kotlin
   if (gameState.drawOffer == Set.BLACK && gameState.winState == WinState.NONE) {
       DrawOfferDialog(onAccept = viewModel::acceptDrawOffer, onDecline = viewModel::declineDrawOffer)
   }
   ```
2. Bottom button row (lines 197-201) — add next to Reset:
   ```kotlin
   Button(
       onClick = viewModel::requestDrawOffer,
       enabled = canOfferDraw(gameState) && animState.pieceToAnimate == null,
       modifier = Modifier.testTag("offer_draw_button")
   ) { Text(stringResource(Res.string.offer_draw_button)) }
   ```
   and below the row, the decline feedback:
   ```kotlin
   if (gameState.drawOfferDeclinedBy == Set.BLACK) {
       Text(stringResource(Res.string.draw_offer_declined),
            modifier = Modifier.testTag("draw_offer_declined_text"))
   }
   ```
3. New `DrawOfferDialog` composable modeled on `PromotionDialog` (line ~496), reusing `PopupWindow`; outside-tap/back dismissal calls `onDecline`. Accept/Decline `Button`s with testTags `draw_offer_accept` / `draw_offer_decline`.

## Step 9 — Unit tests (commonTest, kotlin.test)

New file `app/src/commonTest/kotlin/com/example/myapplication/UciEvaluationTest.kt`:
- `parseInfoScore`: `"info depth 12 ... score cp 35 ... pv e2e4"` → 35; `score cp -210` → -210; `score cp 13 lowerbound` → 13; `score mate 3` → 99997; `score mate -2` → -99998; `score mate 0` → -100000; `"bestmove e2e4"` → null; `"info string NNUE evaluation"` → null.
- `toWhitePerspective(50, whiteToMove = false)` → -50; `isWhiteToMove` for `w`/`b` FENs.

New file `app/src/commonTest/kotlin/com/example/myapplication/DrawAgreementTest.kt` (mock pattern: `object : ChessEngine { override fun getBestMove(fen: String): String? = null; override fun evaluate(fen: String): Int? = X; override fun close() {} }` attached via `attachEngine`; call synchronous `offerDraw()`/`tryBlackDrawOffer()` directly, never `requestDrawOffer()`):

1. `materialBalanceCp`: start position → 0; `"4k3/8/8/8/8/8/8/R3K3 w - - 0 1"` → +500; `"3qk3/8/8/8/8/8/8/4K3 w - - 0 1"` → -900.
2. `shouldBlackAcceptDraw`: 0/-100/+500/+99997 → true; -101/-99997 → false (boundaries + mate scores).
3. `shouldBlackOfferDraw`: 0, ±60 → true; ±61 → false.
4. `blackDrawOfferPreconditions` — passes for a qualifying state; fails individually for fullmove 19, halfmoveClock 7, `autoPlay = true`, `drawOffer != null`, and cooldown (`lastDrawOfferFullmove = 25, fullmoveNumber = 30`).
5. `canOfferDraw` — false for: turn BLACK, winState != NONE, autoPlay, pendingPromotion set, drawOffer set, `fullmoveNumber == lastDrawOfferFullmove`.
6. White offers, mock eval 0 → `winState == DRAW`, `drawOffer == null`.
7. White offers, mock eval -400 → declined: `winState == NONE`, `drawOfferDeclinedBy == Set.BLACK`, `canOfferDraw` now false (cooldown).
8. White offers, **no engine**, fresh game (material 0) → accepted via fallback heuristic.
9. White offers, no engine, Black up a queen (`"3qk3/..."` FEN above) → declined via fallback.
10. White offers, mock `evaluate` returns null → falls back to material balance.
11. Cooldown resets after a move: declined offer, then quiet `playerMove` + `moveCPU(Set.BLACK) { ... }` → `canOfferDraw` true again and `drawOfferDeclinedBy == null` (cleared by `deriveNewGameState`).
12. `offerDraw()` no-ops mid-promotion (reuse PromotionTest's FEN `"4k3/P7/8/8/8/8/8/4K3 w - - 0 1"`, trigger `pendingPromotion` first).
13. Black proactively offers via the real flow: `GameViewModel(FenConverter.fenToGameState("r3k3/p7/8/8/8/8/P7/R3K3 w - - 10 30"))`, no engine; quiet rook move `playerMove(idxOf(7,0), Pair(7,3))` (halfmoveClock → 11), then `animationEnd()` → `drawOffer == Set.BLACK`, Black's pieces unmoved, `winState == NONE`. (`animationEnd()` requires `animState.pieceToAnimate != null`, which `playerMove` sets — same trick as `PromotionTest.testMatePromotion`.)
14. Same setup but `setAutoPlay(true)` before `animationEnd()` → no offer, Black moved.
15. Cooldown for Black: state with `lastDrawOfferFullmove = fullmoveNumber - 1` → `tryBlackDrawOffer()` false.
16. `acceptDrawOffer()` on `GameUiState(turn = Set.BLACK, drawOffer = Set.BLACK, ...)` → DRAW; no-op when `drawOffer != Set.BLACK`.
17. `declineDrawOffer()` → assert only synchronous effects (`drawOffer == null`, `winState == NONE`); Black's resumed move runs in a background coroutine — **do not assert Black's position** (races).
18. `playerMove` ignored while `drawOffer == Set.BLACK`.
19. `resetGame()` clears all three new fields.

**Test-FEN trap:** don't use bare-king "equal material" FENs — `isInsufficientMaterial` would end the game immediately. `"r3k3/p7/8/8/8/8/P7/R3K3 w - - 10 30"` (R+P each) avoids insufficient-material, fifty-move, and threefold.

## Step 10 — Android UI tests (androidDeviceTest)

New file `app/src/androidDeviceTest/kotlin/com/example/myapplication/DrawOfferScreenTest.kt`, copying the harness style of `GameScreenTest.kt` (`createComposeRule`, `MyApplicationTheme { GameScreen(WindowWidthSizeClass.Medium, viewModel) }`, `boardSquareTag` helper, `waitUntil` + `onAllNodesWithTag(..., useUnmergedTree = true)`):

1. **Button present/enabled at start** — fresh `GameViewModel()`: `onNodeWithTag("offer_draw_button").assertIsDisplayed()` + `assertIsEnabled()`.
2. **Offer accepted (fallback)** — fresh `GameViewModel()` (no engine, equal material): click button; `waitUntil(5_000)` for `winnerText`; assert `"Game ended in a DRAW!"` displayed. (Resolution is async on Dispatchers.Default — `waitUntil` is mandatory.)
3. **Offer declined** — `attachEngine` a mock with `evaluate = -500`: click button; `waitUntil` for `draw_offer_declined_text`; assert displayed, button `assertIsNotEnabled()`, no `winnerText` nodes.
4. **Black offers → accept** — `GameViewModel(FenConverter.fenToGameState("r3k3/p7/8/8/8/8/P7/R3K3 w - - 10 30"))`, no engine: click `board_square_WhitePiece_7_0` then `board_square_PossibleMove_7_3`; `waitUntil(10_000)` for `draw_offer_accept` (covers the 500 ms move animation); click it; `waitUntil` for `winnerText`; assert `"Game ended in a DRAW!"`.
5. **Black offers → decline** — same setup: click `draw_offer_decline`; `waitUntil` dialog gone; `waitUntil(10_000) { viewModel.gameState.value.turn == Set.WHITE }` (Black moved in background); assert no `winnerText`.

## Verification (run in order)

```bash
./gradlew :app:desktopTest                       # fast: all commonTest on JVM
./gradlew test                                   # unit tests on all targets
./gradlew :app:wasmJsBrowserDistribution         # proves new common code is wasm-safe
./gradlew :androidApp:assembleDebug :app:assembleAndroidDeviceTest :app:check :app:desktopJar :app:packageDistributionForCurrentOS
./gradlew :app:connectedAndroidDeviceTest        # needs device/emulator (CI runs API 35)
```

Optional manual check: `./gradlew :app:desktopRun` with system `stockfish` on PATH — play ~20 quiet moves, offer a draw, confirm accept/decline behavior; confirm Black's offer dialog appears in a drawish endgame.

## Risks / implementer notes

1. **Threading**: `evaluate()` blocks on process I/O. `animationEnd()` already calls `getBestMove` synchronously (pre-existing); `tryBlackDrawOffer()` adds at most one more engine call there, gated by the cheap preconditions so it fires rarely. White's offer path and the post-decline Black move run in `scope.launch`. Keep `offerDraw()` synchronous (tests depend on it).
2. **Interface default**: `evaluate(fen) = null` default is load-bearing — anonymous `object : ChessEngine` mocks exist in `MoveTest.kt` (~line 54) and `CastlingTest.kt` (~line 160).
3. **UCI queue hygiene**: `go depth N` also ends with a `bestmove` line; `waitForEvaluation` must consume it or the next `getBestMove` returns a stale move.
4. **Wasm**: keep `UciEvaluation.kt` and `DrawAgreement.kt` pure Kotlin (no `java.*`, no `System.currentTimeMillis`).
5. **GameUiState equality asserts**: `PromotionTest` uses `assertEquals` on whole states — new fields have defaults, so existing tests stay green.
6. **UI-test flakiness**: every assertion after clicking the offer button or moving a piece needs a preceding `waitUntil` (async resolution + 500 ms tween); use 10 s timeouts for the Black-offer dialog tests.
