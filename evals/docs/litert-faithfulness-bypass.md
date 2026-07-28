# LiteRT-LM reason-faithfulness bypass

Measures whether the on-device LiteRT-LM model (Qwen3-0.6B-int4) stays faithful
to the deterministic tags the Move Coach prompt supplies — the measurement the
roadmap's Sequence 2 calls for, done without building a LiteRT-LM provider into
ferryman (ferryman is HTTP-only; the generator lives here).

## Why a bypass

ferryman's harness has the reason-faithfulness scorer. The LiteRT-LM generator
lives in this app. Wiring LiteRT-LM into ferryman as a provider would be a
multi-day feature (native deps, model payload, the MCP tool-call loop — which a
0.6B model can't reliably do anyway). The scorer is provider-agnostic, so we
decouple: **this app generates, ferryman scores.** One JSON contract between them.

## Two-step run

### 1. Generate (this repo — produces `litert-outputs.json`)

```bash
cd /path/to/compose-multiplatform-chess
git checkout eval/litert-faithfulness-bypass   # or main, once merged

# Drive LiteRT-LM over 10 golden cases, emit outputs JSON.
# First run downloads the ~347 MB Qwen3-0.6B-int4 model (~1-2s to load after).
# The driver lives in :litert-eval — its own module, isolated from :server so
# its dependency graph stays Ktor-free (see build.gradle.kts for why).
# litert-outputs.json lands in litert-eval/. Use an absolute path to write elsewhere:
#   --args="10 /tmp/litert-outputs.json"
./gradlew :litert-eval:run --args="10 litert-outputs.json"
```

Output shape (one object per case):

```json
{
  "id": "opening-001",
  "fen": "rnbqkbnr/...",
  "bestMoveUci": "g1h3",
  "tags": ["opening", "develops"],
  "output": "Nh3 develops the knight toward the center.",
  "route": "litert",
  "firstTokenMs": 1200,
  "completeMs": 3400
}
```

`route` is `"litert"` for a real generation, or `"fallback (<reason>)"` when the
model produced nothing and the driver recorded the deterministic `MoveCoachFallback`
text (what the production orchestrator would show the user).

### 2. Score (ferryman repo — produces the scorecard)

```bash
cd /path/to/ferryman
python3 eval_harness/score_litert_outputs.py /path/to/litert-outputs.json \
  --write scorecard-litert.md
```

The scorer imports ferryman's `_check_faithfulness` and scores each output
against the pre-supplied tags (no FEN derivation — candidates.json carries
hand-authored tags per case). Two axes per case:

- **Faithfulness** — paraphrase covers the supplied tag concepts AND asserts no
  unsupported high-stakes concept (check / checkmate / wins material / capture /
  castle / promotion). The "Nf3 attacks the enemy king" failure mode (tag says
  `develops`, model invents a threat) fails here.
- **Honesty** — the forbidden-phrase gate (no "Stockfish thinks" / "engine depth"
  / unsupported certainty). Same gate the chess app's
  `MoveCoachResponseValidator` enforces client-side.

No API keys, no network — pure local scoring.

## What the scorecard tells you

The roadmap's prediction to confirm: **the honesty gate passes at high rates**
(the model is paraphrasing supplied tags, not inventing engine evals), and the
question is **whether faithfulness holds** — does the paraphrase cover the tags,
or does the 0.6B model add unsupported detail (wrong piece, invented threat)?

A high honesty / low faithfulness split is the most informative outcome: it
means the existing client-side validator (honesty + grounding) is *insufficient*,
and the faithfulness scorer catches what it can't — which is the gap the roadmap
names. That's the finding worth reporting.

## Why a separate `:litert-eval` module

The driver lives in its own Gradle module (`:litert-eval`), not in `:evals`,
because of an internally-inconsistent dependency: **litertlm-jvm 0.14.0's
bytecode and its POM disagree about kotlinx-coroutines.**

- litertlm-jvm's `sendMessageAsync` bytecode calls
  `SendChannel.close$default(SendChannel, Throwable, int, Object)` — a static
  bridge that **only exists in kotlinx-coroutines 1.11.0+**.
- litertlm-jvm's POM declares kotlinx-coroutines-core-jvm **1.9.0** (which lacks
  the bridge). So the version the POM asks for cannot satisfy the bytecode.
- The rest of this repo resolves coroutines to **1.10.2** (Ktor 3.4.3, via
  `:server`) or **1.9.0** (the catalog) — neither provides the method.

The result: litertlm crashes with `NoSuchMethodError: SendChannel.close$default`
the moment a real generation completes (compile is fine; the call site is in a
JNI callback that only runs at inference time).

The fix is module-scoped: `:litert-eval` depends on `:onDeviceAi` + `:coachapi`
only (no `:server`, no Ktor) and forces coroutines to **1.11.0** via
`resolutionStrategy.force` in `litert-eval/build.gradle.kts`. That's the single
version that provides the bridge litertlm needs.

If you ever move the driver back into `:evals`, or add a `:server`/Ktor
dependency here, Ktor will drag coroutines back to 1.10.2 and the crash returns.
Don't. When litertlm-jvm publishes a build whose bytecode matches its POM (or
Ktor moves to 1.11.0+), this isolation can be reconsidered — but verify by
running the driver to completion (not just compiling) before merging modules.

## Fixture note

The driver runs against `evals/golden/candidates.json`, whose tag vocabulary was
normalized to the `MoveCoachFallback` tag constants (`develops`, `center-control`,
`king-safety`, `opening`; `pawn-tension` has no canonical constant and passes
through `describeTags`' `else -> tag` as a literal string). Before this
normalization, the tags used `development` / `center`, which didn't match
`describeTags`' `when` branches — the prompt was handing the model raw strings
instead of the translated phrases. Fixing the fixture was the article's thesis
applied to itself: a fixture bug silently misleads every measurement.

## Hardware requirements

- Apple Silicon Mac (M-series) or Linux x86_64 — `litertlm-jvm` ships native
  libs for `darwin-aarch64`, `linux-x86_64`, `linux-aarch64`, `win-x86_64`.
- **Intel Mac is unsupported** (no `darwin-x86_64` native lib). The driver
  detects this and exits with a clear message rather than emitting empty outputs.
