"""Export the published Maia-3 5M checkpoint to ONNX for the desktop human-move predictor.

Reproduces docs/benchmarks/on-device-ai/maia3-conversion-spike-2026-08.md. The two fixes recorded
there are applied here: RMSNorm is decomposed into ops ONNX has, and the legacy TorchScript exporter
(`dynamo=False`) is used because the dynamo path cannot export nn.MultiheadAttention.

Usage (see tools/maia3/README.md for the full recipe):
    PYTHONPATH=/path/to/maia3-clone python export_onnx.py maia3-5m.pt maia3-5m.onnx
"""

import sys
from types import SimpleNamespace

import torch
import torch.nn as nn

import maia3.models as models
from maia3.model_registry import resolve_model_spec


class DecomposedRMSNorm(nn.Module):
    """Mathematically identical to torch.nn.RMSNorm as maia3 constructs it (`RMSNorm(d_model)`, so
    eps=None, which torch resolves to the input dtype's machine epsilon), built from ops every ONNX
    opset has."""

    def __init__(self, dim, eps=None, **_):
        super().__init__()
        self.eps = torch.finfo(torch.float32).eps if eps is None else eps
        self.weight = nn.Parameter(torch.ones(dim))

    def forward(self, x):
        return x * torch.rsqrt(x.pow(2).mean(-1, keepdim=True) + self.eps) * self.weight


def build_model(checkpoint_path):
    spec = resolve_model_spec("maia3-5m")
    cfg = SimpleNamespace(**spec.config, device="cpu")
    original = models.RMSNorm
    models.RMSNorm = DecomposedRMSNorm
    try:
        model = models.MAIA3Model(cfg)
    finally:
        models.RMSNorm = original
    state = torch.load(checkpoint_path, map_location="cpu", weights_only=True)
    state = state.get("model_state_dict", state)
    renamed = {k.replace("smolgen", "gab"): v for k, v in state.items()}
    missing, unexpected = model.load_state_dict(renamed, strict=False)
    if missing or unexpected:
        raise SystemExit(f"checkpoint mismatch: missing={missing[:5]} unexpected={unexpected[:5]}")
    model.eval()
    return model, cfg


def main(checkpoint_path, onnx_path):
    model, cfg = build_model(checkpoint_path)
    width = 12 * cfg.history + 1
    tokens = torch.zeros((1, 64, width), dtype=torch.float32)
    elos = torch.tensor([1500], dtype=torch.long)
    torch.onnx.export(
        model,
        (tokens, elos, elos),
        onnx_path,
        dynamo=False,
        opset_version=17,
        input_names=["tokens", "self_elo", "oppo_elo"],
        output_names=["logits_move", "logits_value", "logits_ponder"],
        dynamic_axes={
            "tokens": {0: "batch"},
            "self_elo": {0: "batch"},
            "oppo_elo": {0: "batch"},
            "logits_move": {0: "batch"},
            "logits_value": {0: "batch"},
            "logits_ponder": {0: "batch"},
        },
    )
    print(f"wrote {onnx_path} (tokens width {width})")


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
