# Plan: Game summary + completing the coach (issue #33)

**Status:** proposed
**Issue:** [#33 — Add game summary and an optional coach](https://github.com/ber4444/compose-multiplatform-chess/issues/33)
**Builds on:** [PR #50 — On-device AI move coach](https://github.com/ber4444/compose-multiplatform-chess/pull/50) (branch `on-device-ai-move-coach`)
**Scope:** the *rest* of issue #33 — the end-of-game summary, PGN export, the "reply with bar" half of the in-game coach, and a decision on cloud fallback.

---

## 0. Where PR #50 leaves the ticket

Issue #33 has **two** deliverables — "game summary **and** an optional coach". PR #50 shipped the
infrastructure and the easy half of the coach; the headline feature ("Add game summary") is untouched.

| Ticket ask | Status after PR #50 |
|---|---|
| In-game tips ("Stockfish chose this because foo…") | **Done** — `MoveCoachPanel` explains Black's move on-device after Stockfish replies |
| …"and you should probably reply with **bar**" | **Missing** — panel never suggests White's reply |
| End-of-game summary — analyze the game, reveal the greatest mistake | **Missing entirely** |
| Hint #1: stabilize **PGN generation** + a `PGNTest` suite | **Missing** — there is no move list in the codebase at all |
| `GameCoach.summarizeGame(pgn)` abstraction | **Missing** (PR built `AiCoachOrchestrator.explainMove` instead) |
| Cloud fallback (Gemini cloud / Apple PCC) | **Missing** — only deterministic-text fallback exists |
| Per-move eval wiring (`evaluationBeforeCp/After`) | **Stubbed `null`** in `GameViewModel.triggerCoach` |

What PR #50 *did* build, and which this plan reuses:

- `:onDeviceAi` KMP module: `AiCoachOrchestrator` / `DefaultAiCoachOrchestrator`, `OnDeviceTextGenerator`
  facade, `AiRoutePolicy` + `AiRoutePolicyDecider`, `MoveCoachPromptBuilder`, `MoveCoachResponseValidator`,
  `MoveCoachFallback`, `defaultNowMs()` expect/actual.
- Platform actuals: Android Cactus/llama.cpp (`gemma3-270m`), iOS Foundation Models. Desktop/web =
  deterministic fallback only.
- Chess-app wiring: `movecoach/` UI state + context extractor + panel, and the coach trigger in
  `GameViewModel` (fires after Black's move, cancellable).

---

## 1. Critical read of the ticket

The ticket was written by an agent. Five things are wrong, stale, or under-thought; the plan below
accounts for each.

1. **"PCC fallback" on Apple is not a real public API.** Apple's Foundation Models framework is
   on-device only for third-party apps. Private Cloud Compute is reachable by Apple's own system
   features, not via a developer API for arbitrary prompts. PR #50's own plan doc flags the
   provider-swappable `LanguageModel` direction as a *"roadmap assumption — not in the cited WWDC25
   source."* **Do not promise PCC fallback.**
2. **"Gemini Nano via Firebase AI Logic" is already overtaken.** PR #50 deliberately rejected the
   ML Kit/AICore path (narrow device support) and shipped Cactus/llama.cpp. Remaining work builds on
   the existing `:onDeviceAi` abstraction; it does **not** re-introduce Firebase because the ticket
   names it. The ticket's tech picks are stale; its architecture (abstraction + platform actuals +
   deterministic fallback) is what survived and is sound.
3. **The LLM must not "analyze the PGN and reveal the greatest mistake" — it can't.** Cactus's
   `gemma3-270m` (and even Apple's ~3B device model) cannot reliably find the single biggest blunder
   from raw moves. **Stockfish does the detection** (centipawn-loss sweep → worst move); the LLM only
   *narrates* the one moment it is handed. This matches PR #50's own framing ("chess facts come from
   Stockfish; the model only explains") but contradicts the issue's literal wording.
4. **Hint #1 ("stabilize PGN first") is the most important sentence in the ticket, and PR #50 ignored
   it.** There is currently no move history: `positionHistory` stores only FEN *position keys* (board
   layout, for threefold repetition); the moves played are never recorded. Move logging, SAN
   generation, PGN export, and the `PGNTest` suite are real net-new prerequisites and the critical
   path for the summary feature.
5. **Avoid two competing abstractions.** The ticket proposes a fresh `GameCoach` interface; PR #50
   shipped `AiCoachOrchestrator`. **Extend the existing orchestrator** with a `summarizeGame` path so
   there is one routing/validation/fallback pipeline. The ticket's
   `suggestExplanation(fen, engineBestMove, playerMove)` signature is a better fit for "reply with
   bar" than what PR #50 built (it carries *both* moves) — fold that shape into the existing
   `explainMove` path rather than adding an interface.

---

## 2. Milestones

`M-A` is the unavoidable prerequisite. `M-B` and `M-C` are the two missing halves of the ticket.
`M-D` is optional and recommended for deferral.

### M-A — Move log + PGN export + `PGNTest`

Net-new; everything else depends on it. All pure `commonMain` (fast to iterate via `:app:desktopTest`).

- **Record moves.** Add `moveLog: List<RecordedMove>` to `GameUiState` (or a sibling state object if
  keeping `GameUiState` lean is preferred). Each entry captures from/to squares, piece, capture flag,
  promotion, check/checkmate, castle side, and the resulting FEN. Populate it in the
  move-application path in `GameViewModel.deriveNewGameState` — the same place that already updates
  `positionHistory`, so the data is in hand. Reset with `resetGame()`.
- **SAN generator.** New `commonMain` `SanConverter`: piece letters, disambiguation (file/rank/both),
  `x` captures, `+`/`#`, `O-O`/`O-O-O`, `=Q` promotions. Reuse `getAllLegalMoves` (in `Move.kt`) to
  compute disambiguation. This is the fiddly part and the reason the ticket asks for a test suite.
- **PGN export.** `PgnExporter.export(state): String`: Seven Tag Roster with sensible defaults — Event
  "Casual Game", Site = app name, Date from the `defaultNowMs()` expect/actual PR #50 added, Result
  from `WinState` — followed by SAN movetext with move numbers.
- **`PGNTest` (`commonTest`):** known game → exact PGN; SAN disambiguation cases; castling, promotion,
  en passant, and checkmate notation. Deterministic, no engine.

**Exit criteria:** `PGNTest` green; a played game round-trips to valid, importable PGN.

### M-B — End-of-game summary (the headline feature)

- **Deterministic blunder detection (`commonMain` `GameReviewAnalyzer`).** At game end, replay
  `moveLog` and call the existing `ChessEngine.evaluate(fen)` (already used by the draw-agreement
  feature via `UciEvaluation.kt`) before/after each *White* move, compute centipawn loss, and rank to
  find the worst move — the "teachable moment". Output: FEN, the move played, the engine's better
  move, and the eval swing. **Works with no LLM** — desktop/web get a templated summary, a robustness
  win the ticket doesn't anticipate.
  - *Cost/UX:* a full sweep is N Stockfish calls. Run off the move loop at shallow depth with a
    progress state, only after `winState != NONE`. On wasm/iOS (slower engines) cap depth or sample.
    See §3 decision 1 — caching evals during play is the preferred alternative to a sweep.
- **Extend the orchestrator, do not fork it.** Add `summarizeGame(request: GameSummaryRequest):
  GameSummaryResult` to `AiCoachOrchestrator`, plus a `GameSummaryPromptBuilder` and validator
  mirroring the move-coach ones. The prompt receives the *deterministically chosen* blunder (FEN,
  played move, better move, swing) and asks only for a 2–3 sentence explanation — never "find the
  mistake."
- **UI.** Surface in the existing game-over window (the `ViewState.hideWindow` path in `GameScreen`).
  New `GameSummaryUiState` (Analyzing → Ready / Fallback), reusing `MoveCoachPanel` styling.
- **Tests:** `GameReviewAnalyzerTest` with a scripted game containing a known blunder and a fake
  engine returning fixed evals (deterministic); prompt-builder/validator tests like PR #50's.

**Exit criteria:** finishing a game shows a summary naming the biggest mistake; analyzer tests green;
desktop/web show the templated (no-LLM) summary.

### M-C — "…reply with **bar**" (completes the in-game half)

Small, cheap extension of PR #50's existing trigger.

- After Stockfish returns Black's move, make one extra `ChessEngine.getBestMove(fenAfterBlack)` call
  to get White's suggested reply, and **wire the real `evaluationBeforeCp/After`** currently stubbed
  `null` in `GameViewModel.triggerCoach`.
- Extend `MoveCoachRequest` / the prompt so the explanation becomes
  "Stockfish played … because …; consider **Nf3** in reply." This realizes the ticket's
  `suggestExplanation(fen, engineBestMove, playerMove)` shape inside the existing `explainMove` path.

**Exit criteria:** coach panel includes a suggested White reply; eval fields populated.

### M-D — Cloud fallback (recommend **deferring**)

The routing policy already models `AiRoute.Cloud`, but no cloud actual exists and the ticket's cloud
paths are problematic (PCC is not a public API; Firebase was rejected in PR #50). **Defer.** On-device
+ deterministic fallback already degrades gracefully. If pursued later, the honest version is "Gemini
via Firebase AI Logic on Android, on-device-only on Apple," with its own privacy/consent/cost work and
explicit user opt-in. Tracked as a decision, not baked in (§3 decision 2).

---

## 3. Decisions needed before coding

1. **Summary eval cost.** Full eval sweep at game end (best quality, slow on wasm/iOS) **vs.** cache
   the per-move evals already computed during play and reuse them at game end (instant summary).
   *Recommendation:* cache during play. This also satisfies M-C's eval wiring, making the two
   milestones share one dependency.
2. **Cloud fallback.** Confirm dropping it for now (recommended), or scope at least the Android
   Gemini-cloud path into M-D.
3. **`GameUiState` vs. sibling state for `moveLog`.** Inlining keeps one source of truth; a sibling
   keeps `GameUiState` (an `@Immutable` Compose state) small. *Recommendation:* sibling state owned by
   `GameViewModel`, since the move log is not read during composition of the board.

---

## 4. Cross-cutting

- **Verification gate (CLAUDE.md):** every milestone must keep all targets building —
  `:androidApp:assembleDebug`, `:app:desktopJar`, `:app:wasmJsBrowserDistribution`, `:app:check`, plus
  the Apple job. M-A/M-B core logic is pure `commonMain`, testable fast via `:app:desktopTest`.
- **Branching:** M-B/M-C depend on the `:onDeviceAi` orchestrator that only exists on
  `on-device-ai-move-coach`. Either land PR #50 first, or branch this work off it.
- **Frozen actuals:** do not touch the 3D renderer or Stockfish-engine `actual` implementations
  (CLAUDE.md "Platform Glue Fences"). This feature only *consumes* `ChessEngine.evaluate` /
  `getBestMove` and the `OnDeviceTextGenerator` facade.

---

## 5. Dependency order

```text
M-A (move log + SAN + PGN + PGNTest)   ← prerequisite, pure commonMain
   └── M-B (blunder detection + summary orchestration + game-over UI)
   └── M-C (reply suggestion + eval wiring)   ← shares the eval-cache decision with M-B
M-D (cloud fallback)   ← optional, deferred
```
