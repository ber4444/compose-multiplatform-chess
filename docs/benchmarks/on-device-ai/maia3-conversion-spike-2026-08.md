# Maia-3 5M conversion spike — result: it converts, and it is fast (2026-08-11)

Status: **Spike complete, all kill criteria passed.** Nothing is wired into the app. This records
whether a human-move-prediction model *could* run here, and what it cost to find out.

## Why

Every model measured in `android-model-latency-2026-08.md` lost to the deterministic coach, and the
conclusion there was that Android ships no model. That is a conclusion about **language** models
asked to narrate. Maia-3 is a different kind of thing: it predicts *what a human of a given rating
would play*, which Stockfish structurally cannot do — Stockfish knows the best move, not the typical
one. If it converts and runs cheaply, it enables coaching the current architecture has no way to
express.

## Result

| Question | Answer |
|---|---|
| Does the checkpoint load? | **Yes** — `maia3-5m.pt`, 21 MB, 0 missing / 0 unexpected keys, 5.23M params |
| Does it export to ONNX? | **Yes** — 21.2 MB, opset 17, 2041 nodes, after two fixes below |
| Is the converted model faithful? | **Yes** — max abs diff **2.1e-05** vs PyTorch (fp32 noise), argmax agrees |
| Is it fast enough? | **3.03 ms/inference**, onnxruntime CPU, Apple Silicon |
| Is the input encoder portable? | **Yes, trivially** — see below |
| Does rating conditioning actually work? | **Yes, and it is interpretable** — see below |

3 ms against 1.4 s for the iOS language model and 5–20 s for anything Android could run. This is not
in the same cost class as the models that lost; it is roughly free.

## The two conversion blockers, and the fixes

Both are worth recording because neither is in any model card, and both would stop someone cold.

**1. `nn.MultiheadAttention` will not export under the dynamo exporter.**

```
ValueError: Cannot view a tensor with shape [64, 1, 8, 32] and strides (32, 16384, 2048, 1)
as a tensor with shape (64, 256)!
```

An internal non-contiguous reshape. Not fixable by removing `dynamic_axes` — that only changes the
symbolic batch to a literal `64`. Fix: **`dynamo=False`**, the legacy TorchScript tracer, which
handles MHA. torch 2.13 defaults to the dynamo path, so this is now the *non-default* route.

**2. `aten::rms_norm` is unsupported at every opset, including 23.**

```
torch.onnx.errors.UnsupportedOperatorError: Exporting the operator 'aten::rms_norm'
to ONNX opset version 23 is not supported
```

Bumping the opset does not help. Fix: patch `maia3.models.RMSNorm` to an explicit decomposition
before constructing the model —

```python
x * torch.rsqrt(x.pow(2).mean(-1, keepdim=True) + eps) * weight
```

— which is mathematically identical, loads the same weights (0 missing / 0 unexpected), and uses
only ops ONNX has. The 2.1e-05 parity above is measured *after* this substitution, so the
decomposition is verified, not assumed.

## The input encoder is a day of Kotlin, not a research risk

This was the other thing that could have killed it. `maia3/dataset.py`:

- `tokenize_board` — a `(64, 12)` one-hot of piece type × colour, with the board **mirrored when
  Black is to move**. ~15 lines against the existing `GameUiState`.
- `get_historical_tokens` — concatenate the last `history` positions (8 for this variant) and append
  a clock column, giving `(64, 97)`.
- `get_legal_moves_mask` — a mask over a fixed 4352-move vocabulary (64×64 from/to, plus 8×8×4
  promotions), built once from `get_all_possible_moves()`.

All integer/float board manipulation with no ML dependency, and `chess-core` already has the board
state, the square naming (`UciMoveConverter`) and legal move generation. The only subtlety is the
mirror-for-Black convention, which must match exactly or every prediction is wrong in a way that
still looks plausible.

## Rating conditioning is real, and it reads like a coach

Italian Game, Black to move (`r1bqkbnr/pppp1ppp/2n5/4p3/2B1P3/5N2/PPPP1PPP/RNBQK2R b KQkq - 4 4`):

| Rating | Top three |
|---:|---|
| 1000 | Nf6 37%, Bc5 17%, **d6 11%** |
| 1400 | Nf6 36%, Bc5 23%, **h6 13%** |
| 1800 | Nf6 36%, **Bc5 35%**, Be7 9% |
| 2200 | **Nf6 60%**, Bc5 25%, Be7 9% |

`h6` peaks at 1400 and vanishes by 1800 — the club-player reflex to stop Ng5. `Bc5` doubles from
1000 to 1800 as players find the Giuoco Piano. 2200 concentrates on the main line. From the starting
position the same pattern holds: `e3` appears only at 1100, `c4` only from 1500 up.

That is the product idea working: **"most 1400s play h6 here — you did too; 1800s play Bc5"** is a
sentence this app currently has no way to produce, and it is more useful to a club player than
another centipawn number.

## What this spike did *not* establish

- **Not validated against the published 57.1% move-match figure.** That needs a real Lichess sample
  at a known rating band, which was not available offline. The qualitative behaviour above is
  consistent with the claim; it does not verify it.
- **Not run on a phone.** 3 ms is onnxruntime CPU on Apple Silicon. Android would need onnxruntime-mobile
  or a LiteRT conversion, and iOS the same or CoreML — the ONNX is the input to that step, not proof
  of it.
- **No Kotlin tokenizer exists yet.** Assessed as easy; not written.
- **Nothing is wired in.** No `VendorRoute`, no `AiRoutePolicy`, no surface. This model does not fit
  the `OnDeviceTextGenerator` seam at all — it emits a probability distribution over moves, not text
  — so it needs its own seam alongside, not inside, the text one.

## Recommendation

**Worth doing, and it is a different bet from the one that failed.** The reason every language model
lost is that it was asked to *judge* a position from weights that do not contain the judgement.
Maia-3 is asked what humans do, which is exactly what it was trained on, and the answer is checkable
against the board. It also inherits the property that made the deterministic layer win: the output is
data, and code decides what to say about it.

Sequencing note: this pairs naturally with **B6/RAG-5 (habits)** rather than competing with it. Habit
aggregation says *you keep doing X*; Maia-3 says *players at your level do X, players above you do
Y*. Together that is a coaching product; separately each is a feature.

## Reproducing

```bash
python3 -m venv /tmp/maia_venv && /tmp/maia_venv/bin/pip install torch numpy onnx onnxscript onnxruntime python-chess
git clone --depth 1 https://github.com/CSSLab/maia3.git /tmp/maia3
curl -sL -o /tmp/maia3-5m.pt https://huggingface.co/UofTCSSLab/Maia3-5M/resolve/main/maia3-5m.pt
```

Then load via `resolve_model_spec("maia3-5m")`, rename `smolgen`→`gab` in the state dict (the
published checkpoint predates the rename; `maia3/uci.py:149` does this too), patch `RMSNorm`, and
export with `dynamo=False`.
