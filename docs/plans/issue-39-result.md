# Issue #39 — Game lifecycle, history & persistence — result

> **Verdict: SHIPPED (in scope).** The full game-lifecycle story landed across five PRs (#56, #64,
> #65, #66, #67, #69). PGN export + Game History + Save/Share, autosave/resume-later, a persisted
> 3D toggle, and a real engine-difficulty control are all implemented and green on CI for every
> target (Android, desktop, web/Wasm, iOS). The originally-planned persisted theme override and
> time-control/chess-clock subsystem were **removed from scope** by maintainer decision (#66).

This is the deliverable record for issue #39. Implementation plan:
`docs/plans/issue-39-game-lifecycle-persistence.md` (phases annotated with their outcome).

## What shipped (by phase)

- **Phase 0 (#56)** — deps + settings infra + `AppRoot`/nav. `multiplatform-settings` + `kotlinx-serialization`; `createSettings` expect/actual; `LocalAppSettings`; `AppRoot` screen enum + multiplatform `BackHandler`; `MyApplicationTheme` consolidated into `AppRoot`.
- **Phase 1 (#56)** — move history + SAN + PGN. `MoveRecord` appended per ply in `deriveNewGameState`; `SanConverter` (disambiguation/castling/promotion/`+`/`#`); `PgnSerializer` (Seven Tag Roster + movetext).
- **Phase 2 (#64)** — autosave + resume-later. `GameSnapshot` (FEN + DTOs) + `GameSnapshotMapper`; `CurrentGameStore` (versioned key, corrupt-tolerant); `CurrentGameStoreSupport.loadInitialState` (finished game → fresh start); explicit `autosave()` at move/draw choke-points; resume Black's engine move on restore.
- **Phase 3 (#65)** — Game History + Save + Share. `SavedGame` + `GameHistoryRepository` (StateFlow, newest-first, cap 200); `GameActions` (PGN/SavedGame builder); `PgnSharer` expect/actual (Android `ACTION_SEND`, desktop `FileDialog`, iOS `UIActivityViewController`, wasm `Blob`+download); Save/Share buttons in the game-over popup; `GameHistoryScreen` (list + detail + delete).
- **Phase 4 (#69)** — engine difficulty. `EngineDifficulty` (Easy/Medium/Hard/Max → `Skill Level` + `movetime`); additive `ChessEngine.configure`; shared UCI impls (`UciProtocolClient`, `BaseStockfishEngine`); iOS Swift bridge (additive `setoption`); `AppSettings.engineDifficulty`; `SettingsScreen` selector.
- **Supporting (#66, #67, #68)** — #66 removed theme + time-control from scope (theme follows system dark mode); #67 moved the 3D toggle into Settings (persisted, default on); #68 split the Apple CI job into parallel kotlin/xcode jobs + caching.

## Removed from scope (#66)

- **Persisted theme override** — the app now always follows the system dark-mode setting. `AppSettings`/`SettingsScreen`/`AppRoot` are retained as the seam for the difficulty setting. The historic Phase 0 theme code was deleted.
- **Time-control / chess-clock subsystem (Phase 5)** — presets, per-side countdown, flag-fall, `ClockState`, `winReason`, clock UI were not built. The reserved `clockWhiteMillis`/`clockBlackMillis` fields on `GameSnapshot` and the optional `[TimeControl]` PgnTags field stay for a possible future reintroduction.

## Architecture notes (decisions that shaped the implementation)

- **Serialize DTOs, never `GameUiState` directly.** Round-trip through FEN (lossless board/clocks/castling/ep/turn) + small `@Serializable` DTOs for the rest. `GameUiState`'s `Piece` subclasses are plain `class` (identity `equals`), so round-trip tests compare via FEN + SAN.
- **Autosave is explicit, not a flow collector.** `autosave()` is called at move/draw choke-points in `GameViewModel`, never on transient `selectedSquare` updates — deterministic and testable.
- **Engine difficulty is additive-only** (the frozen fence). A new defaulted `ChessEngine.configure` + `setoption name Skill Level` in the *shared* UCI paths + a 2-line Swift send. The platform `resolveExecutablePath()` subclasses are untouched.
- **`PgnSharer` mirrors `Board3DSupport`** — injected at entry points, `null` hides the Share button.

## Verification

CI green on all targets (`instrumented-tests` + `apple-kotlin` + `apple-xcode` jobs). Unit tests:
`SanConverterTest`, `PgnSerializerTest`, `GameSnapshotMapperTest`, `CurrentGameStoreTest`,
`GameHistoryRepositoryTest`, `GameActionsTest`, `AppSettingsTest`, `UciProtocolClientTest`
(configure/movetime). Instrumented: `AutoSaveRestoreTest`, `SaveGameHistoryTest`,
`EngineDifficultySettingsTest`.

Manual smoke (per platform, at least desktop + web + Android): play moves → kill & relaunch → game
resumes; finish a game → Save → History shows it → Share produces a valid PGN (paste into lichess
"Import game"); change difficulty → persists; toggle 3D in Settings → persists.
