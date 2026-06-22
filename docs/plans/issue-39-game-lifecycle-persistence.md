# Game lifecycle, history & persistence — Implementation Plan (GitHub issue #39)

> **Audience:** this is a self-contained spec for an external coding agent (Z.AI / GLM). It assumes
> no prior conversation context. Read the whole document before writing code; implement phase by
> phase, in order; keep every phase independently green against CI before opening the next PR.

## Context

The app already has FEN/UCI conversion and rule-based draw detection in shared code. Issue #39 asks
for the full "game lifecycle" story, in three parts:

1. **PGN export + game history list** — a PGN model, a converter from move history + metadata to
   PGN, "Save game" / "Share PGN" actions on game end, and a Game History screen backed by local
   storage.
2. **Resume-later + autosave** — persist the current game on every move; reload on app startup.
3. **Persist settings** — time control, theme, engine difficulty, in a shared settings abstraction
   in `commonMain` with platform behaviour shared across Android, desktop, web, iOS, macOS.

### Two decisions already made by the maintainer (do not re-litigate)

- **Storage backend:** use the **`multiplatform-settings` (russhwolf)** library plus
  **`kotlinx-serialization`**, not a hand-rolled key-value store.
- **Settings scope:** implement **all three features for real** — a persisted theme override, a real
  engine-difficulty control, **and a full time-control / chess-clock subsystem** (the app currently
  has no clock at all). Time control is therefore a net-new feature, not just a persisted field.

### What does NOT exist yet (verified — do not assume otherwise)

- **No move history is stored anywhere.** `GameViewModel.deriveNewGameState` derives the next
  `GameUiState` but never records the move that produced it. PGN needs this.
- **No SAN (Standard Algebraic Notation) converter exists** — only UCI (`UciMoveConverter`) and FEN
  (`FenConverter`). PGN movetext is SAN, so SAN generation must be built.
- **No persistence / settings layer** — no `multiplatform-settings`, DataStore, okio, or
  `kotlinx-serialization` in `gradle/libs.versions.toml`.
- **No theme toggle** — `MyApplicationTheme(darkTheme = isSystemInDarkTheme())`; wasm forces
  `darkTheme = false`. No user override.
- **No engine difficulty** — `ChessEngine` has no difficulty knob; all engines play full strength.
- **No clock / time control** — no clock state, UI, or flag-fall logic.
- **No navigation** — `ChessApp` renders exactly one screen (`GameScreen`). History/Settings screens
  need a minimal navigation host.

## ⚠️ The platform-glue fence (read `CLAUDE.md` first)

`CLAUDE.md` marks the 3D renderer actuals and the Stockfish engine bridges as **frozen** — do not
unify or rewrite them. **Only one task in this issue touches that fence: engine difficulty (Phase 4).**
That change must be **strictly additive** (a new defaulted interface method + sending extra UCI
`setoption` lines in the *shared* UCI paths), never a rewrite or merge of the platform bridges. When
in doubt on Phase 4, stop and ask the maintainer. Everything else in this issue lives in new files or
in `commonMain` state/UI code and stays well clear of the fence.

## Key existing code (verified — these are your anchors)

- `app/src/commonMain/kotlin/com/example/myapplication/GameUiState.kt` — `GameUiState` (turn,
  parallel `piecesWhite`/`positionsWhite` + black lists, `castlingRights`, `enPassantTarget`,
  `halfmoveClock`, `fullmoveNumber`, `positionHistory`, draw-offer fields, `winState`). `WinState`
  enum (`NONE/WHITE/BLACK/DRAW/STALEMATE`). `ViewState` (`show3D`, etc.).
- `app/src/commonMain/kotlin/com/example/myapplication/GameViewModel.kt` — plain class (NOT androidx
  ViewModel), owns its own `CoroutineScope`, exposes `gameState`/`animState`/`viewState` StateFlows.
  `playerMove`, `promotePawn`, `moveCPU`, `animationEnd`, `resetGame`, `attachEngine`, `close`.
  `private fun deriveNewGameState(...)` (lines ~326–493) is where every move is applied — this is the
  single choke-point to record move history and trigger autosave.
- `app/src/commonMain/kotlin/com/example/myapplication/FenConverter.kt` — `STARTING_FEN`,
  `gameStateToFen(state)`, `fenToGameState(fen)`, `positionKey(state)`. Note `fenToGameState` returns
  a fresh `GameUiState` with default (empty) `positionHistory`/`moveHistory`/draw fields.
- `app/src/commonMain/kotlin/com/example/myapplication/UciMoveConverter.kt` — `appMoveToUci(from,to)`,
  `positionToUciSquare(pos)`, `parseUciMove`, `uciMoveToAppMove`.
- `app/src/commonMain/kotlin/com/example/myapplication/Move.kt` — `SelectedMove(position,pieceIndex,
  promotion)`, `getAllLegalMoves(...) : List<Pair<Pair<Int,Int>, Int>>` (`.first` = target square,
  `.second` = piece index), `getLegalMovesForPiece(...) : List<Pair<Int,Int>>`, `isPromotionMove`,
  `castlingRookMove(piece, from, to) : Pair<Pair<Int,Int>,Pair<Int,Int>>?`, board-home constants.
- `app/src/commonMain/kotlin/com/example/myapplication/PromotionType.kt` — `QUEEN/ROOK/BISHOP/KNIGHT`,
  `uciChar`, `toPiece(set)`, `fromUciChar`.
- `app/src/commonMain/kotlin/com/example/myapplication/Piece.kt` — `King/Queen/Rook/Bishop/Knight/
  Pawn`, `Set.WHITE/BLACK`, `checkCheck(...)`, `BOARD_SIZE = 8`.
- `app/src/commonMain/kotlin/com/example/myapplication/ChessEngine.kt` — `suspend getBestMove(fen)`,
  `suspend evaluate(fen)` (default null), `close()`.
- Shared UCI: `UciProtocolClient.kt` (wasm path, `go movetime`, `EVAL_DEPTH`) and
  `jvmCommonMain/.../BaseStockfishEngine.kt` (android+desktop path, `sendCommand("go movetime ...")`).
- `app/src/commonMain/kotlin/com/example/myapplication/ChessApp.kt` — `ChessApp(viewModel, modifier,
  board3D, switchTopPadding)`; `WindowWidthSizeClass`.
- `app/src/commonMain/kotlin/com/example/myapplication/GameScreen.kt` — `GameScreen(...)`, the
  game-over `PopupWindow`, `GameControls` (reset / offer-draw row), 2D `Board`, 3D branch.
- `app/src/commonMain/kotlin/com/example/myapplication/ui/theme/Theme.kt` —
  `MyApplicationTheme(darkTheme = isSystemInDarkTheme(), content)`.
- Entry points (each constructs `GameViewModel()` and injects engine + `Board3DSupport`):
  - `androidMain/.../MainActivity.kt` — `AndroidGameViewModel : ViewModel` holder; `setContent {
    MyApplicationTheme { ChessApp(...) } }`. Has `Activity`/`Context`/`assets`/`filesDir`.
  - `desktopMain/.../Main.kt` — `application { ... MyApplicationTheme { ChessApp(...) } }`.
  - `wasmJsMain/.../Main.kt` — `ComposeViewport(...) { MyApplicationTheme(darkTheme=false){ ChessApp }}`.
  - `iosMain/.../MainViewController.kt` — `MainViewController(engine): UIViewController`.
- `app/build.gradle.kts` — manual source-set graph: `commonMain` → `jvmCommonMain` →
  {`androidMain`,`desktopMain`}, plus `wasmJsMain`, `iosMain`. `kotlin.mpp.applyDefaultHierarchy
  Template=false`. Wasm klib incremental compilation is intentionally disabled — do not re-enable.

## Architectural decisions (apply throughout)

- **`multiplatform-settings` is constructed via a thin `expect/actual` factory**, not the `-no-arg`
  artifact (Android no-arg auto-init is avoided; we already have an `Activity`/`Context` at the entry
  point). One `expect fun createSettings(): Settings` with per-platform actuals. This is the standard
  pairing with the library, not a hand-rolled store — it does not contradict the "use the library"
  decision.
- **Serialize DTOs, never `GameUiState` directly.** `GameUiState` holds `Piece` objects and `Pair`s
  that are awkward to serialize and easy to desync from rules. Use FEN (already lossless for board +
  clocks + castling + en passant + turn) plus small `@Serializable` DTOs for the rest (move list,
  win state, position history, draw-offer fields, clock remaining-times).
- **Rapidly-changing clock time lives in its own StateFlow (`clockState`), NOT in `GameUiState`.**
  Putting per-tick time in `gameState` would re-trigger the autosave collector and the 3D
  `fen = remember(gameState)` recomputation every tick. Snapshot the clock into the autosave only at
  move boundaries (sub-second loss on resume is acceptable).
- **Injection mirrors the existing `Board3DSupport` / engine pattern.** New platform capabilities
  (`PgnSharer`, the `Settings` factory) are created at each platform entry point and passed down,
  exactly like `board3D = androidBoard3DSupport()`.
- **No navigation library.** Add a minimal screen enum + multiplatform `BackHandler`
  (`androidx.compose.ui.backhandler.BackHandler`, available in CMP 1.10) inside a new shared
  `AppRoot` composable. `AppRoot` also becomes the single home for theme application, removing the
  per-entry-point `MyApplicationTheme` duplication.

---

## Status

- **Phase 0** (deps, settings infra, `AppRoot`/nav, persisted theme) — ✅ implemented in #56.
- **Phase 1** (move history + SAN + PGN model) — ✅ implemented in #56.

The phases below are the open work as of #56. The "What does NOT exist yet" list above is
historic — Phase 0/1 entries should now be read as "what #56 added."

---

## Phase 2 — Autosave + resume-later

Goal: the in-progress game is saved on every completed move and restored on next launch. Depends on
Phase 1 (needs `moveHistory`) and Phase 0 (needs the `Settings`).

### 2.1 `GameSnapshot` DTO + mapping

`commonMain/.../persistence/GameSnapshot.kt`:
```kotlin
@kotlinx.serialization.Serializable
data class GameSnapshot(
    val fen: String,                       // board + clocks + castling + ep + turn (lossless)
    val moveHistory: List<MoveRecord>,
    val positionHistory: List<String>,     // threefold keys (resets on irreversible — store as-is)
    val winState: WinState,
    val drawOffer: String? = null,         // Set?.name
    val drawOfferDeclinedBy: String? = null,
    val lastDrawOfferFullmove: Int = 0,
    val clockWhiteMillis: Long? = null,     // Phase 5
    val clockBlackMillis: Long? = null,     // Phase 5
    val savedAtEpochMillis: Long = 0,
)

object GameSnapshotMapper {
    fun fromState(state: GameUiState, clock: ClockState? = null): GameSnapshot
    fun toState(snapshot: GameSnapshot): GameUiState // FenConverter.fenToGameState(fen).copy(...)
}
```
`toState` starts from `FenConverter.fenToGameState(fen)` then `.copy(moveHistory=…, positionHistory=…,
winState=…, drawOffer=…, …)`. Re-run `applyWinConditions`/`applyDrawConditions` defensively after
load (matches the existing `GameViewModel.init` behaviour).

### 2.2 `CurrentGameStore`

`commonMain/.../persistence/CurrentGameStore.kt` — thin wrapper over `Settings` + `Json`:
```kotlin
class CurrentGameStore(private val settings: Settings, private val json: Json = Json { ignoreUnknownKeys = true }) {
    fun save(snapshot: GameSnapshot) { settings.putString(KEY, json.encodeToString(snapshot)) }
    fun load(): GameSnapshot? = settings.getStringOrNull(KEY)?.let { runCatching { json.decodeFromString<GameSnapshot>(it) }.getOrNull() }
    fun clear() { settings.remove(KEY) }
    companion object { const val KEY = "current_game.v1" }
}
```
Version the key (`.v1`) so a future schema change can't crash on stale data; `ignoreUnknownKeys` +
`runCatching` make load tolerant.

### 2.3 Wire into `GameViewModel`

- Add an optional ctor param: `class GameViewModel(gameState: GameUiState = GameUiState(), private val
  currentGameStore: CurrentGameStore? = null)`. Default null keeps all existing tests/`remember {
  GameViewModel() }` call sites compiling.
- **Autosave:** in `init`, launch a collector in the VM scope on `gameState` (conflated) that writes
  `currentGameStore?.save(GameSnapshotMapper.fromState(state, _clockState.value))` — but **only at
  move boundaries / meaningful changes**, not on transient `selectedSquare` updates. Simplest correct
  approach: call `autosave()` explicitly at the end of `deriveNewGameState`'s apply path and after
  promotion/draw resolution, rather than a blanket flow collector. (A flow collector keyed off
  `moveHistory.size` + `winState` + `drawOffer` also works and avoids per-selection writes.)
- **Restore:** add a `companion`/factory or have the entry point call `currentGameStore.load()` and
  pass `GameSnapshotMapper.toState(it)` as the ctor `gameState`. If the loaded game is already over
  (`winState != NONE`), prefer starting fresh (or show it via the game-over popup — your call; default
  to fresh + clear). After a successful restore, if it's Black's turn, the engine should resume (the
  existing turn-driven flow will need a nudge — call the equivalent of `animationEnd()`’s Black branch
  on load if `turn == BLACK && winState == NONE`).
- `resetGame()` must `currentGameStore?.clear()`.

### 2.4 Wire entry points

Each entry point: build `CurrentGameStore(createSettings("chess"))`, `load()` a snapshot, construct
`GameViewModel(gameState = restoredOrDefault, currentGameStore = store)`. On Android, the
`AndroidGameViewModel` holder must construct the `GameViewModel` with the store (move store creation
into the holder or pass it in).

### Phase 2 tests
- `commonTest` `GameSnapshotMapperTest` — `fromState` → `toState` round-trips board, turn, clocks,
  move history, win state, draw fields. Mid-game (with en passant target + castling rights partially
  spent) round-trips losslessly.
- `commonTest` `CurrentGameStoreTest` (MapSettings) — save/load/clear; corrupt JSON → `load()` returns
  null (no throw); version key respected.
- `GameViewModelTest` — playing a move triggers a save (inject a `CurrentGameStore` over MapSettings,
  assert the key is populated and decodes to the expected move count); `resetGame()` clears it; a VM
  constructed from a restored snapshot reproduces the same `gameState`/`moveHistory`.
- `androidDeviceTest` — play a couple moves, recreate the Activity/VM from the store, assert board
  matches (use a deterministic engine or no engine).

---

## Phase 3 — Game History screen + Save game + Share PGN

Goal: on game end, the user can save the finished game and share its PGN; a History screen lists saved
games. Depends on Phase 1 (PGN) and Phase 0 (settings/nav).

### 3.1 `SavedGame` + `GameHistoryRepository`

`commonMain/.../persistence/GameHistory.kt`:
```kotlin
@kotlinx.serialization.Serializable
data class SavedGame(
    val id: String,            // timestamp-based or random UUID-ish (commonMain: build from epoch + counter)
    val savedAtEpochMillis: Long,
    val result: String,        // "1-0" etc.
    val white: String, val black: String,
    val moveCount: Int,
    val pgn: String,
)

class GameHistoryRepository(private val settings: Settings, private val json: Json = Json { ignoreUnknownKeys = true }) {
    private val _games = MutableStateFlow(load())
    val games: StateFlow<List<SavedGame>> = _games          // newest first
    fun add(game: SavedGame) { /* prepend, persist, update flow */ }
    fun delete(id: String) { /* ... */ }
    private fun load(): List<SavedGame> = settings.getStringOrNull(KEY)?.let { runCatching { json.decodeFromString<List<SavedGame>>(it) }.getOrNull() } ?: emptyList()
    companion object { const val KEY = "game_history.v1" }
}
```
Storing the whole list as one JSON blob is fine at hobby scale (SharedPreferences / localStorage /
NSUserDefaults / `java.util.prefs` all hold this comfortably; cap at e.g. 200 games, drop oldest).
For epoch millis in commonMain use `kotlinx.datetime`? Avoid adding a dep — use
`kotlin.time`/`Clock.System` only if `kotlinx-datetime` is already present (it is **not**). Instead
pass the current time in from the platform, or use a small `expect fun nowEpochMillis(): Long` +
`expect fun todayPgnDate(): String` (Android/JVM `System.currentTimeMillis()` + `SimpleDateFormat`;
iOS `NSDate`; wasm `Date.now()`/`Date`). Add this tiny `expect/actual` (`persistence/Clock.kt`).

### 3.2 `PgnSharer` (`expect/actual`, injected like `Board3DSupport`)

`commonMain/.../share/PgnSharer.kt`:
```kotlin
interface PgnSharer { fun share(pgn: String, suggestedFileName: String) }
```
Platform factories (created at entry points, passed into `AppRoot`):
- **Android** (`androidMain`) — `androidPgnSharer(activity: Activity)`: write PGN to a cache file +
  `FileProvider` URI, fire `ACTION_SEND` with `type = "application/x-chess-pgn"` (or `text/plain`).
  Requires a `FileProvider` entry in `androidApp` manifest + `res/xml/file_paths.xml`. (If FileProvider
  is too heavy, fall back to `ACTION_SEND` with `EXTRA_TEXT = pgn`.)
- **iOS** (`iosMain`) — present a `UIActivityViewController` with the PGN string from the topmost
  `UIViewController`.
- **Desktop** (`desktopMain`) — `java.awt.FileDialog` (save) writing the `.pgn`, or copy to clipboard
  via `Toolkit.getDefaultToolkit().systemClipboard`. Provide both ("Save…" + "Copy").
- **wasm** (`wasmJsMain`) — create a `Blob`, an object URL, and a synthetic `<a download>` click for
  download; plus `navigator.clipboard.writeText` for copy. Guard JS interop carefully; this is
  fire-and-forget (no `Promise.await` needed — avoids the known wasm `Promise.await` GC pitfall).

`PgnSharer` is `null` on platforms where you defer it; the Share button hides when null (mirror the
`board3D != null` gating in `GameScreen`).

### 3.3 Game-end actions

In `GameScreen`'s game-over `PopupWindow` (the `winState != NONE && !hideWindow` block), add two
buttons next to "Play again"/"Cancel":
- **Save game** — builds `PgnTags` (date via `todayPgnDate()`, white/black names — "Stockfish" if an
  engine is attached else "CPU"; you may need a small flag on the VM/engine presence), calls
  `PgnSerializer.toPgn(tags, gameState.moveHistory)`, wraps in `SavedGame`, `gameHistory.add(...)`.
  Disable/confirm to avoid double-saves.
- **Share PGN** — same PGN string → `pgnSharer.share(pgn, "game-<id>.pgn")`. Hidden if `pgnSharer == null`.

Also expose a "Save / Share" affordance for an *in-progress* game if desired (optional; the issue only
requires game-end). Keep game-end as the primary path.

### 3.4 `GameHistoryScreen`

`commonMain/.../GameHistoryScreen.kt` — a `LazyColumn` over `gameHistory.games` (newest first), each
row showing date, result, players, move count. Tapping a row opens a detail view showing the PGN text
(selectable) with **Share** and **Delete** actions. Empty state when no games. Reached from `AppRoot`
via `Screen.HISTORY`; entry button added to `GameScreen` (next to Settings). `testTag`s throughout
(`history_row_<id>`, `history_empty`, `history_share`, `history_delete`).

> Optional stretch (not required by the issue): "load/replay" a saved game. Skip unless time allows;
> `MoveRecord.fenAfter` makes a read-only scrubber easy later.

### Phase 3 tests
- `commonTest` `GameHistoryRepositoryTest` (MapSettings) — add/delete/load round-trips; newest-first
  ordering; cap/eviction; corrupt blob tolerated.
- `androidDeviceTest` — finish a game (force a quick mate via a seeded position or no-engine CPU),
  tap **Save game**, open **History**, assert one row with the right result; tap **Delete**, assert
  empty. Stub/route `PgnSharer` so the **Share** button is present and clickable without launching a
  real chooser in tests (inject a fake `PgnSharer` in the test harness).
- `PgnSharer` itself is platform-glue (intent/share-sheet/file/JS) — verify manually per platform;
  unit-test only the pure PGN string it receives.

---

## Phase 4 — Engine difficulty (⚠️ touches the engine fence — additive only)

Goal: a persisted difficulty setting that actually weakens/strengthens play. **Additive changes only**
to the engine code; no rewrites or merges of the platform bridges (see the fence section).

### 4.1 Model + setting

`EngineDifficulty` enum in `commonMain` (e.g. `EASY, MEDIUM, HARD, MAX`) mapping to:
- Stockfish `setoption name Skill Level value N` (0–20; e.g. EASY=2, MEDIUM=8, HARD=15, MAX=20), and
- a per-move think budget (`movetime` ms) used by the "go" command (EASY shorter, MAX longer).
Persist via `AppSettings` (`KEY_DIFFICULTY`), same pattern as theme. Add a control to `SettingsScreen`.

### 4.2 Additive `ChessEngine` API

Extend the interface with a **defaulted, non-breaking** method:
```kotlin
interface ChessEngine {
    suspend fun getBestMove(fen: String): String?
    suspend fun evaluate(fen: String): Int? = null
    suspend fun configure(difficulty: EngineDifficulty) {}   // default no-op (CPU fallback ignores it)
    fun close()
}
```
Implement `configure` in the **shared** UCI paths only:
- `UciProtocolClient` (wasm) — send `setoption name Skill Level value N` (+ optionally
  `UCI_LimitStrength`/`UCI_Elo`); store the chosen `movetime` so subsequent `go movetime` uses it.
- `BaseStockfishEngine` (jvmCommon → android+desktop) — same `setoption`/movetime handling. This is the
  shared base; the android/desktop subclasses (`resolveExecutablePath()` only) are untouched.
- Swift `StockfishChessEngine` (iOS) — add the same `setoption` before `go` in the existing bridge
  (smallest possible additive edit; do **not** restructure the semaphore bridge).

> If editing the Swift bridge feels like crossing the fence, stop and confirm with the maintainer
> before proceeding — but adding two `setoption` lines is squarely additive.

### 4.3 Apply difficulty

When the engine is attached and whenever the setting changes, call `engine.configure(difficulty)`.
Thread the current `AppSettings.engineDifficulty` to the VM (`GameViewModel.attachEngine` could take
the difficulty, or expose `viewModel.setEngineDifficulty(...)` that calls `configure` in VM scope).
Apply once on attach and on each change.

### Phase 4 tests
- `commonTest` — `UciProtocolClientTest` additions: `configure(EASY)` emits the expected
  `setoption name Skill Level value 2` line on the fake `UciTransport`; subsequent `getBestMove` uses
  the difficulty's `movetime`.
- `AppSettingsTest` — difficulty persists / defaults.
- `androidDeviceTest` — change difficulty in Settings, assert the setting is stored (behavioural play
  strength is not asserted — too flaky).

---

## Phase 5 — Time control / chess-clock subsystem

Goal: a real clock. Presets + unlimited; per-side countdown that runs on the side to move; flag-fall
ends the game; the choice is persisted and in-progress times survive resume. Depends on Phase 0
(settings) and Phase 2 (snapshot for resume). Largest phase — build it last.

### 5.1 Model + setting

`commonMain/.../TimeControl.kt`:
```kotlin
@kotlinx.serialization.Serializable
data class TimeControl(val baseMillis: Long, val incrementMillis: Long) {
    val isUnlimited get() = baseMillis <= 0
    companion object {
        val UNLIMITED = TimeControl(0, 0)
        val BLITZ_5_0 = TimeControl(5*60_000, 0)
        val BLITZ_3_2 = TimeControl(3*60_000, 2_000)
        val RAPID_10_0 = TimeControl(10*60_000, 0)
        val presets = listOf(UNLIMITED, BLITZ_5_0, BLITZ_3_2, RAPID_10_0)
    }
    fun toPgnTag(): String? = if (isUnlimited) null else "${baseMillis/1000}+${incrementMillis/1000}"
}
```
Persist the selected `TimeControl` in `AppSettings` (serialize via `Json`, `KEY_TIME_CONTROL`). Add a
control to `SettingsScreen`. Changing time control takes effect on the next new game (don't mutate a
game in progress).

### 5.2 Clock state + driver (separate StateFlow)

`commonMain/.../ClockState.kt`:
```kotlin
@kotlinx.serialization.Serializable
data class ClockState(
    val whiteMillis: Long,
    val blackMillis: Long,
    val running: Set? = null,   // which side's clock is ticking; null = paused
    val unlimited: Boolean = false,
)
```
In `GameViewModel`:
- `private val _clockState = MutableStateFlow(...)`; `val clockState: StateFlow<ClockState>`.
- Initialize from the selected `TimeControl` on new game / restore from snapshot on resume.
- A ticking coroutine in VM scope (e.g. 100 ms cadence using `kotlinx.coroutines.delay` +
  monotonic time deltas — not naïve `-=100`) that decrements the running side. **Pause** while:
  promotion dialog open, draw-offer dialog open, game over, animation in flight (your choice — at
  minimum pause when `winState != NONE`). Switch the running side when `turn` flips (drive off the
  same turn transitions that already gate Black's engine move in `animationEnd`/`moveCPU`).
- Apply increment to the side that just moved (Fischer increment) on move completion.
- **Flag-fall:** when a running clock hits 0 → set `winState` to the *opponent* (White flags →
  `WinState.BLACK`, and vice-versa) and stop the clock. Add an optional `winReason` (see 5.4). Insufficient-
  material-on-flag → draw is a nicety; keep `timeout = loss` for v1 and note it.
- Do **not** put `ClockState` in `GameUiState` (per the architecture note). The autosave snapshot
  reads `_clockState.value` at move boundaries only.

### 5.3 Clock UI

Two clock readouts (mm:ss, switch to mm:ss.t under ~10s) for White (bottom) and Black (top), rendered
in both the 2D layout (in/near `GameControls`) and the 3D overlay. Highlight the running side. Hidden
when `unlimited`. `testTag("clock_white")`, `testTag("clock_black")`. Add a `formatClock(millis)`
pure helper in commonMain (unit-tested).

### 5.4 Win integration + PGN

- Add `val winReason: WinReason? = null` to `GameUiState` (`enum WinReason { CHECKMATE, STALEMATE,
  TIMEOUT, DRAW_AGREEMENT, DRAW_RULE, RESIGN }`), default null so nothing breaks. Set it where each
  terminal state is produced (checkmate/stalemate in `applyWinConditions`, timeout in the clock
  driver, draws in `applyDrawConditions`/draw-offer handlers). Use it for the game-over message ("White
  wins on time").
- PGN: set `PgnTags.timeControl = selectedTimeControl.toPgnTag()`; on timeout you may also emit a
  `[Termination "Time forfeit"]` tag (optional).

### 5.5 Persistence

- Selected `TimeControl` persists in `AppSettings`.
- In-progress `whiteMillis`/`blackMillis` go into `GameSnapshot` (fields already reserved in 2.1);
  `GameSnapshotMapper` reads/writes them; restore seeds `_clockState`.

### Phase 5 tests
- `commonTest` `ClockTest` — increment applied to the mover; running side switches on turn flip; flag-
  fall produces the correct `winState`/`winReason`; `formatClock` formatting (`5:00`, `0:09.8`, `0:00`).
  Drive the driver with a virtual clock / `runTest` + `TestDispatcher` so it's deterministic (don't
  sleep real time).
- `GameSnapshotMapperTest` — clock times round-trip.
- `AppSettingsTest` — `TimeControl` persists.
- `androidDeviceTest` — start a short (e.g. UNLIMITED vs a tiny base) game; assert clocks render and
  the running side highlights; optionally assert a forced timeout ends the game (may be flaky — gate
  behind a deterministic virtual clock hook if possible, else keep manual).

---

## Phase 6 — Docs & final verification

- Update `CLAUDE.md`:
  - Add a "Persistence & settings" subsection (multiplatform-settings + kotlinx-serialization;
    `AppSettings`, `CurrentGameStore`, `GameHistoryRepository`; the `createSettings` `expect/actual`
    factory; the `PgnSharer` injection mirroring `Board3DSupport`).
  - Note the new `expect/actual` boundaries (`createSettings`, `PgnSharer`, `nowEpochMillis`/
    `todayPgnDate`) so future agents know they exist.
  - Document the navigation model (`AppRoot` screen enum + multiplatform `BackHandler`) and that theme
    is now applied in `AppRoot`, not per entry point.
- Add this plan's outcome notes if anything was rejected/changed (follow the repo's
  `docs/plans/*-result.md` convention if you want a results doc).
- Update README/feature list if one enumerates features.

## Verification (run before each PR; CI must be green for ALL targets)

Per-iteration (fast):
```bash
./gradlew :app:desktopTest --tests "com.example.myapplication.SanConverterTest"
./gradlew :app:desktopTest --tests "com.example.myapplication.PgnSerializerTest"
./gradlew :app:desktopTest --tests "*Snapshot*" --tests "*Settings*" --tests "*History*" --tests "*Clock*"
./gradlew test            # all shared unit tests across targets
```
Full gate (mirror CI `.github/workflows/android-tests.yml`):
```bash
./gradlew :androidApp:assembleDebug :app:assembleAndroidDeviceTest :app:check \
          :app:desktopJar :app:packageDistributionForCurrentOS :app:wasmJsBrowserDistribution
./gradlew :app:connectedAndroidDeviceTest     # API 35 emulator/device
```
Apple job (second CI job):
```bash
./gradlew :app:iosSimulatorArm64Test
xcodebuild -project iosApp/iosApp.xcodeproj -scheme iosApp -configuration Debug \
  -destination "platform=iOS Simulator,name=iPhone 17" CODE_SIGNING_ALLOWED=NO test
```
Manual smoke (per platform, at least desktop + web + Android):
- Toggle theme → persists across restart.
- Play moves → kill & relaunch → game resumes (board, turn, move list, clocks).
- Finish a game → Save → History shows it → Share produces a valid PGN (paste into lichess.org
  "Import game" to validate SAN/PGN correctness).
- Change difficulty / time control → persists; clock counts down and flags.

> **A change isn't done until every target above builds and the Android device tests + apple tests
> pass.** Wasm is the most fragile: confirm `:app:wasmJsBrowserDistribution` builds with the new deps;
> do not re-enable wasm klib incremental compilation.

## Risks / implementer notes

- **`multiplatform-settings` on wasmJs** is the highest-risk dependency. Verify the version resolves
  for `wasmJs` before building anything else (Phase 0.1 is a gate). If the chosen version lacks wasmJs
  support, bump it or fall back to a hand-rolled `expect/actual` over `localStorage` for the wasm
  actual only (the rest of the design is unchanged).
- **The engine fence (Phase 4)** is the only place touching frozen platform glue. Keep edits additive
  (defaulted interface method + extra `setoption` lines in the *shared* UCI paths and a 2-line Swift
  edit). Anything bigger → ask the maintainer.
- **Don't bloat `GameUiState` with per-tick clock time** — keep `ClockState` a separate StateFlow, or
  you'll thrash autosave and 3D FEN recomputation every 100 ms.
- **Autosave must not fire on `selectedSquare` changes** — gate it on move/win/draw transitions.
- **Serialize DTOs, not `GameUiState`** — round-trip through FEN + small `@Serializable` DTOs.
- **Backwards-compatible constructors** — every new `GameViewModel` ctor param must be defaulted so the
  existing tests and `remember { GameViewModel() }` call sites keep compiling.
- **commonMain has no `kotlinx-datetime`** — use the tiny `nowEpochMillis()/todayPgnDate()`
  `expect/actual` rather than adding a date library.
- **Multiplatform `BackHandler`** (`androidx.compose.ui.backhandler.BackHandler`) — confirm it resolves
  on desktop/wasm/ios in CMP 1.10; if a target lacks it, provide an `expect`/`actual` no-op shim.
- **PGN correctness is verifiable externally** — import generated PGNs into lichess.org to catch SAN
  bugs (especially disambiguation, promotion, and `+`/`#` suffixes).

## Suggested PR sequence

1. ~~Phase 0 — deps + settings infra + `AppRoot`/nav + persisted theme.~~ ✅ #56
2. ~~Phase 1 — move history + SAN + PGN (pure logic + tests).~~ ✅ #56
3. Phase 2 — autosave + resume.
4. Phase 3 — history screen + save/share.
5. Phase 4 — engine difficulty (additive engine edits).
6. Phase 5 — time control / clocks.
7. Phase 6 — docs + final verification.

Each PR title: `feat(lifecycle): <phase summary> (#39)`. Keep each PR independently green against the
full CI gate above.
