# Add a chess skill + eval maturity to ferryman

## Goal

Your article describes a two-tier maturity story: the chess repo's rule-based CI gating here, ferryman's full two-layer scoring there. This makes that claim *real* by adding a chess skill to ferryman evaluated against an **objective, exact-match** golden set — the thing the chess repo currently lacks. Decisions locked with you:

- **Golden set source:** Bootstrap with **ChessQA** (CSSLab, MIT, exact-match answer keys), then document the Lichess→Stockfish curation pipeline as a scoped follow-on.
- **Grounding:** Self-contained corpus (the skill carries its chess knowledge; no network/DB dependency).
- **Harness scope:** Make the harness multi-skill (per-skill golden set + scorer registry + `--skill` flag); add the chess skill with rule scorers + family-excluded judge + multi-provider matrix.

The eval contract in AGENTS.md (skills enumerable via `skills/*/SKILL.md`, config TOML, `Orchestrator.runSkill`) is **preserved** — we extend around it, we don't break it.

---

## What ships (honesty-first; nothing fabricated)

1. A new ferryman skill `chess-opening-coach` that coaches a position from a bundled corpus.
2. A vendored, MIT-licensed chess golden set (ChessQA subset) with **exact-match** ground truth — no judge needed for the floor.
3. A multi-skill eval harness: `run_scorecard.py --skill <name>` selects golden set + scorers. The existing company-research harness keeps working byte-for-byte.
4. New chess scorers: objective best-move / classification exact-match (the maturity floor) **plus** the existing family-excluded judge for coaching-explanation quality (the product signal) — clearly labeled as two distinct axes.
5. A multi-provider chess matrix in the scorecard: which of GLM / Gemini / Llama understands chess best.
6. A `README.md` section stating plainly: bootstrap set is ChessQA (templated, MIT, attributed), the canonical set (Lichess→Stockfish, the repo's own engine at fixed depth, stratified, human-reviewed) is a documented follow-on.

---

## File-by-file plan

### A. The skill (self-contained corpus)

**`ferryman/skills/chess-opening-coach/SKILL.md`** (new)
- Frontmatter: `name: chess-opening-coach`, `description: ...`, `provider: zai-glm`.
- Prompt modeled on the chess repo's opening-explainer system prompt (`Composers.kt`): "You are a chess opening coach. Use only the supplied passages. Do not mention engine depth, ratings, or unsupported claims." But adapted: the skill takes a FEN + move list + a natural-language question, reasons from a bundled corpus, and answers concisely.
- **Two response modes** (the skill handles both golden-set task shapes):
  - *Best-move mode* (for ChessQA Short Tactics cases): respond with `FINAL ANSWER: <move>` so the exact-match scorer can parse it (mirrors ChessQA's `extract_final_answer` contract).
  - *Explain mode* (for coaching cases): 1–3 sentence grounded explanation.
- Bundled corpus: copy the chess repo's `server/corpus/concepts.md` (4 sections: Central control, Development, King safety, Pawn tension — CC0/CC-BY, attributed in the skill doc) into `ferryman/skills/chess-opening-coach/corpus/concepts.md`. This is the same corpus the chess server uses to ground its opening explainer — faithful provenance.

### B. The golden set (ChessQA bootstrap, MIT)

**`eval_harness/golden/chess_golden.json`** (new, vendored + normalized)
- Source: ChessQA (CSSLab, MIT — [arXiv:2510.23948](https://arxiv.org/abs/2510.23948), [github.com/CSSLab/chessqa-benchmark](https://github.com/CSSLab/chessqa-benchmark)).
- Vendored subset: ~50–80 cases from the **Short Tactics** (single best move, exact-match) + **Position Judgment** (eval band, exact-match) categories — enough to be meaningful in CI, small enough to inspect.
- Normalized to ferryman's golden shape so the multi-skill runner treats it uniformly:
  ```json
  {
    "id": "chess-tactics-0001",
    "input": { "fen": "...", "question": "Find the best move for the side to move." },
    "correctAnswer": "Nf3",
    "answerType": "single",
    "taskCategory": "Short Tactics",
    "taskType": "tactics_best_move",
    "provenance": { "source": "ChessQA (CSSLab)", "license": "MIT", "originalTaskId": "..." }
  }
  ```
- A `provenance` field on every case so the scorecard/README can cite source + license — no silent dataset borrowing.

### C. The chess scorers

**`eval_harness/chess_scorers.py`** (new)
- `score_exact_move(output, case)` — parses `FINAL ANSWER: <move>` (ChessQA's contract), normalizes UCI/SAN, exact-matches `correctAnswer`. The objective floor — **no judge, no substring fuzziness**. This is the maturity upgrade over the chess repo's concept-substring scorer.
- `score_classification(output, case)` — for Position Judgment band cases.
- `score_forbidden_phrases(output, case)` — reuses the chess repo's forbidden-phrase list (`"engine depth"`, `"elo "`, `"rating of"`, `"forced mate"`…) as a hard honesty gate. Ports the exact list from `MoveCoachResponseValidator` / `PositionChatValidator`.
- A scorer registry dict (mirroring `rule_scorers.SCORERS`) so `run_scorecard` dispatches by claim/task type.

### D. Make the harness multi-skill (the core refactor)

**`eval_harness/run_scorecard.py`** (modify — backward-compatible)
- Add a `--skill` flag (default: `company-role-research`). The company harness keeps working with no args, exactly as today.
- Introduce a small **skill spec** registry mapping skill name → `{golden_path, scorer_fn, default_skill_id}`:
  ```python
  SKILL_SPECS = {
      "company-role-research": {golden: "golden/golden_set.json", scorer: rule_scorers.score_all, skill: "company-role-research"},
      "chess-opening-coach":    {golden: "golden/chess_golden.json", scorer: chess_scorers.score_for_case, skill: "chess-opening-coach"},
  }
  ```
- `run_one()` passes the resolved `skill=` to `invoke_mod.invoke(...)` (already supported — currently defaulted to `DEFAULT_SKILL`). So invoking the chess skill hits `Orchestrator.runSkill("chess-opening-coach", input, provider)` with zero orchestrator changes.
- `--all-providers` + `--judge` work unchanged for any skill (the judge layer is generic; we add a chess coaching rubric in E below).
- **Scorecard output naming:** `scorecard.md` stays the company default; chess runs write `scorecard-chess.md` / `.json` so neither overwrites the other.

**`eval_harness/invoke.py`** (modify — tiny)
- `invoke()` already takes `skill=`; we just stop hardcoding the default in the runner and let `run_scorecard` pass it through. `DEFAULT_SKILL` stays as the fallback.

### E. Judge rubric for coaching quality (tier 2, product signal)

**`eval_harness/rubric-chess.md`** (new)
- A chess-coaching-specific rubric (specificity to *this* position, factual move-correctness, no fabrication of engine claims, tone). The existing `judge_scorer.py` is generic — it reads a rubric path; we add a `--rubric` option (default = company `rubric.md`) so the chess run uses the chess rubric. Family-exclusion (`judge_scorer.model_family`) applies unchanged: GLM never judges GLM.
- **Honest labeling in the scorecard:** objective exact-match (floor) and judge coaching-quality (product signal) are reported as **separate columns**, never collapsed into one misleading number.

### F. Tests + docs

**`eval_harness/tests/test_chess_scorers.py`** (new)
- Unit tests for the exact-move scorer (SAN/UCI normalization, `FINAL ANSWER:` extraction, non-match), the classification scorer, and the forbidden-phrase gate. Hermetic (no API keys), mirroring the style of `test_rule_scorers.py`.

**`eval_harness/tests/test_run_scorecard.py`** (modify — extend, don't break existing)
- Add: `--skill chess-opening-coach` resolves the right golden path + scorer + invokes the chess skill id; default (no flag) still hits company-research unchanged. Existing 4 tests untouched.

**`eval_harness/README.md`** (modify) + **`README.md`** (modify)
- Scorecard-status section gains a chess row: provenance (ChessQA, MIT, bootstrap), what it scores (exact-match floor + judge), and the **explicit honesty note**: the bootstrap set is templated ChessQA, the canonical set (Lichess→Stockfish via the chess repo's own engine at fixed depth, stratified, human-reviewed per roadmap Sequence 0's owner-must-verify rule) is a documented follow-on.
- Feature-status table: add a chess row with its proof command.

---

## Verification (the "it's done when" gates)

| Check | Command |
|---|---|
| Chess scorers pass unit tests | `python -m pytest eval_harness/tests/test_chess_scorers.py -q` |
| Company harness unchanged | `python -m pytest eval_harness/ -q` (all existing tests still green) |
| Chess skill is discoverable | `ferry skills list` shows `chess-opening-coach` |
| Chess multi-provider matrix runs (needs keys) | `python eval_harness/run_scorecard.py --skill chess-opening-coach --all-providers` |
| Chess scorecard written | `eval_harness/scorecard-chess.md` exists with real numbers (only after a live run) |
| Kotlin build still clean | `./gradlew build` (ktlint + detekt + tests) |

**Honesty gate (matches your roadmap S0):** No scorecard numbers are committed until a real run produces them. If no provider keys are set, `run_scorecard` records "not run" rather than fabricating — same convention the company harness already follows.

---

## What this does NOT do (scoped follow-ons, stated explicitly)

- Does **not** build the Lichess→Stockfish curation pipeline (your chosen path defers this). It's documented as the next maturity step.
- Does **not** touch the chess repo at all — this is ferryman-only. The chess repo's own evals keep running as-is.
- Does **not** add the chess golden set to the chess repo's CI — the chess repo keeps its current grounding-drift gate; this is a *separate, more mature* evaluation in the companion repo, exactly as the article frames it.
- Does **not** call the deployed coach server (grounding is self-contained per your choice).

This delivers the article's claim — "full two-layer scoring" applied to chess, with an objective floor the chess repo lacks — as runnable, tested reality, while being honest that the canonical engine-grounded set is the documented next step.