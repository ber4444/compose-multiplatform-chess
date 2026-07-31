# Move Coach quality axes (desktop LiteRT-LM, Qwen3-0.6B-int4)

Status: **Measured once, on one host.** This is a 10-case slice, not a
statistically powered benchmark — treat the numbers as a demonstrated gap, not
a calibrated pass rate. Re-run before citing a specific percentage anywhere
load-bearing (the article, a routing threshold, a release note).

## Why this doc exists

`MoveCoachResponseValidator` gates every Move Coach response before it reaches
the user: forbidden phrases, echoed-scaffolding detection, length, and a
grounding check (does the text mention the move or chess vocabulary at all).
It does **not** check whether the paraphrase is faithful to the tags the
engine actually supplied, or whether it names the right piece. Those are
different axes, measured out-of-band by `:litert-eval` + ferryman's
`score_litert_outputs.py`, and the validator passes cases that fail them.

## Results (10 cases, Apple Silicon, 2026-07-29)

| Axis | Result | What it means |
|---|---|---|
| Honesty (forbidden-phrase gate) | **10/10 (100%)** | The model never claims engine provenance it can't have. `MoveCoachResponseValidator` catches this too — the two gates agree here. |
| Reason-faithfulness | **7/10 (70%)** | Paraphrase covers the supplied deterministic tags and asserts no unsupported high-stakes concept (check/mate/material/capture). `MoveCoachResponseValidator` has no equivalent check — every one of the 3 failures here passes the shipped validator. |
| Piece-type | **9/10 (90%)** | Output names the piece the prompt actually supplied. Also invisible to `MoveCoachResponseValidator`. |
| First-token latency | 462 ms mean (9 litert samples) | `:litert-eval` driver, not the app path (see Provenance). |
| Complete latency | 5419 ms mean (9 litert samples) | ditto. |

**The gap that matters:** `MoveCoachResponseValidator` passes 10/10 on this
slice. Faithfulness + piece-type together pass 7/10. The validator is honest
about what it checks — it was never designed to catch reasoning drift — but a
100% pass rate here should not be read as "70-90% accurate on the axes
that weren't gated."

## Provenance

- **Generator:** `LitertLmTextGenerator` (`onDeviceAi/src/desktopMain`), the
  same class the desktop app uses — invoked via the `:litert-eval` CLI driver
  (`litert-eval/src/main/kotlin/.../EvalLiteRtDriver.kt`), **not** through
  `CHESS_ENABLE_COACH=1 ./gradlew :app:run`. It exercises the real generation
  code but not the app's UI/lifecycle wiring around it.
- **Cases:** the same 10-case slice of `evals/golden/candidates.json` used by
  the in-repo `:evals` harness.
- **Scoring:** ferryman's `score_litert_outputs.py`
  ([github.com/ber4444/ferryman-mcp](https://github.com/ber4444/ferryman-mcp)),
  a separate Python repo — decoupled from this app's build on purpose so the
  scorer can't share a bug with the thing it's grading.
- **Host:** Apple Silicon, one run, no repeats. No CI job re-runs this.

## Two bugs the first run had baked in

The first version of this table (never committed — it lived in chat and in an
untracked `scorecard-litert.md` in the ferryman repo) reported honesty
100% / faithfulness 50% / piece-type 80%. Both non-honesty numbers were
measuring bugs, not the model:

1. **The driver passed UCI as the move-display string**
   (`bestMoveDisplay = bestMoveUci`). `MoveCoachPromptBuilder.describeMove`
   derives the piece name from that string's first letter, and UCI always
   starts with a lowercase file letter — so every prompt described the move
   as "Pawn", including 4 of the 10 cases that were actually knight/queen
   moves. The model was echoing what it had been told. Fixed in `4d30b30`
   (mirrors the same fix in `:evals` itself, `748d1c4`).
2. **ferryman's pawn-leniency branch passed any output that omitted the word
   "pawn"**, which also covers output naming a *different* piece outright
   (a pawn move explained as "Bishop b2→b4"). Fixed and merged in
   [ferryman-mcp#13](https://github.com/ber4444/ferryman-mcp/pull/13).

Re-running after both fixes is what produced the table above. Piece-type rose
from a harness-inflated 80% to 90% (with the one remaining failure now a real
hallucination, not an echo); faithfulness rose from 50% to 70% independently,
since correcting the prompt also gave the model accurate move descriptions to
reason from.

## Using this

- **Routing policy** (`AiRoutePolicies`, the hybrid-inference plan): capability
  and privacy class currently decide local-vs-cloud with no quality dimension.
  This table is the evidence that "local is available" and "local is
  faithful" are different questions.
- **Article citations:** don't quote a bare percentage from this doc without
  re-stating the sample size (n=10) and the "measured once" caveat above.
