"""Export the published Maia-3 5M checkpoint to LiteRT (.tflite) for Android and iOS.

Same model and the same RMSNorm decomposition as export_onnx.py, converted with litert-torch. Only
the move logits are exported (signature `serving_default`: `args_0` tokens float32 [1, 64, 97],
`args_1`/`args_2` self/opponent Elo int32 [1], `output_0` logits [1, 4352]). Refuses to write the
file unless every legal-move logit matches the *unpatched* PyTorch model within 1e-3.

Needs its own environment (tools/maia3/requirements-litert.txt): litert-torch wants torch < 2.15
and leaves jax unpinned, and jax 0.12 emits StableHLO its converter plugin (1.19.0) cannot read.

Usage:
    PYTHONPATH=/path/to/maia3-clone python export_litert.py maia3-5m.pt maia3-5m.tflite
"""

import os
import sys
from collections import deque
from types import SimpleNamespace

import chess
import numpy as np
import torch
import litert_torch
from ai_edge_litert.interpreter import Interpreter

import maia3.models as models
from maia3.dataset import get_historical_tokens, get_legal_moves_mask, tokenize_board
from maia3.model_registry import resolve_model_spec
from maia3.utils import get_all_possible_moves

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from export_onnx import DecomposedRMSNorm  # noqa: E402

PROBES = [
    chess.STARTING_FEN,
    "r1bqkbnr/pppp1ppp/2n5/4p3/2B1P3/5N2/PPPP1PPP/RNBQK2R b KQkq - 4 4",
    "8/4P1k1/8/8/8/8/6K1/8 w - - 0 1",
    "r3k2r/pppq1ppp/2npbn2/2b1p3/2B1P3/2NPBN2/PPPQ1PPP/R3K2R b KQkq - 6 8",
]


class MoveLogits(torch.nn.Module):
    def __init__(self, model):
        super().__init__()
        self.model = model

    def forward(self, tokens, self_elo, oppo_elo):
        return self.model(tokens, self_elo, oppo_elo)[0]


def load(checkpoint_path, patched):
    cfg = SimpleNamespace(**resolve_model_spec("maia3-5m").config, device="cpu")
    original = models.RMSNorm
    if patched:
        models.RMSNorm = DecomposedRMSNorm
    try:
        model = models.MAIA3Model(cfg)
    finally:
        models.RMSNorm = original
    state = torch.load(checkpoint_path, map_location="cpu", weights_only=True)
    state = state.get("model_state_dict", state)
    model.load_state_dict({k.replace("smolgen", "gab"): v for k, v in state.items()}, strict=True)
    return model.eval(), cfg


def main(checkpoint_path, out_path):
    patched, cfg = load(checkpoint_path, patched=True)
    reference, _ = load(checkpoint_path, patched=False)
    sample = (torch.zeros((1, 64, 12 * cfg.history + 1)), torch.tensor([1500]), torch.tensor([1500]))
    litert_torch.convert(MoveLogits(patched).eval(), sample).export(out_path)

    interpreter = Interpreter(model_path=out_path)
    interpreter.allocate_tensors()
    tokens_in, self_in, oppo_in = interpreter.get_input_details()
    output = interpreter.get_output_details()[0]
    vocab = {m: i for i, m in enumerate(get_all_possible_moves())}
    worst = 0.0
    for fen in PROBES:
        board = chess.Board(fen)
        tokens = get_historical_tokens(deque([tokenize_board(board)], maxlen=cfg.history), cfg, 0, 0, 0, 0)
        legal = get_legal_moves_mask(board, vocab).numpy()
        for rating in (1100, 1500, 1900):
            elo = torch.tensor([rating])
            with torch.no_grad():
                want = reference(tokens.unsqueeze(0), elo, elo)[0][0].numpy()
            interpreter.set_tensor(tokens_in["index"], tokens.unsqueeze(0).numpy().astype(np.float32))
            interpreter.set_tensor(self_in["index"], np.array([rating], dtype=self_in["dtype"]))
            interpreter.set_tensor(oppo_in["index"], np.array([rating], dtype=oppo_in["dtype"]))
            interpreter.invoke()
            got = interpreter.get_tensor(output["index"])[0]
            worst = max(worst, float(np.abs(got[legal] - want[legal]).max()))
    if worst > 1e-3:
        os.remove(out_path)
        raise SystemExit(f"LiteRT disagrees with PyTorch: max abs legal-move logit diff {worst}")
    print(f"wrote {out_path}; max abs legal-move logit diff vs unpatched PyTorch {worst:.2e}")


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
