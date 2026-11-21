# Maia-3 model provenance

The Android and desktop builds bundle an ONNX conversion of the released Maia-3 5M model, so the
History screen's human-move explorer can predict what players at a rating play with no network.
The layout of this record follows Mobile Maia's
[`MODEL_PROVENANCE.md`](https://github.com/Dash1971/maia-chess-android/blob/main/MODEL_PROVENANCE.md),
the reference for shipping Maia-3 in an Android app.

## Upstream source

- Project: <https://github.com/CSSLab/maia3>
- Source revision: `1e13597c42d4858b7cfd7cfdae01e297263364b2`
- Checkpoint repository: <https://huggingface.co/UofTCSSLab/Maia3-5M>
- Checkpoint repository revision: `b6559de2398d7140b985f28fd2c19fb5e47ddabe`
- Checkpoint file: `maia3-5m.pt`
- Checkpoint size: `20968049` bytes
- Checkpoint SHA-256: `ba14208b2992d85502f5fb501934abf6aaaeb355e9f3fdf90e326911f562524f`
- Licence: GNU Affero General Public License v3.0

The 5M model card refers to the repository for the code and weights licence, and the repository is
AGPL-3.0. The 79M model card states AGPLv3 directly.

## Bundled conversion

- File: `app/src/jvmCommonMain/resources/maia/maia3-5m.onnx`
- Size: `21346659` bytes
- SHA-256: `3d0b22170a965944379499737aac1f7375b93b9bca83446f91e9314443b78ab6`
- ONNX producer: PyTorch `2.15.0`, legacy TorchScript exporter (`dynamo=False`)
- ONNX IR version: `8`
- ONNX opset: `17`
- Inputs: `tokens` float32 `[batch, 64, 97]`, `self_elo` int64 `[batch]`, `oppo_elo` int64 `[batch]`
- Outputs: `logits_move` `[batch, 4352]`, `logits_value` `[batch, 3]`, `logits_ponder` `[batch, 1]`

The file is a converted form of the upstream checkpoint. It adds no training data and does not
change the learned weights; it stays under Maia-3's AGPL-3.0. The only graph change is that
`torch.nn.RMSNorm` is written out as `x * rsqrt(mean(x²) + eps) * weight`, because ONNX has no RMSNorm
op at opset 17. `eps` is torch's default for `RMSNorm(d_model)`, float32 machine epsilon.
`tools/maia3/make_golden.py` checks the export against the **unmodified** PyTorch model: the
largest logit difference is `1.8e-05`.

`OnnxMaiaRuntimeTest.bundledModelIsWhatTheProvenanceDocumentPins` fails if the bundled file's
SHA-256 differs from the one above. `BundledMaiaModel` in `OrtMaiaRuntime.kt` pins the same hash
and size, and refuses a file of the wrong size at load time.

## Reproduction

Use Python 3.14 with the packages pinned in `tools/maia3/requirements.txt`, and the Maia-3 source at
the revision above:

```sh
python3 -m venv /tmp/maia-venv
/tmp/maia-venv/bin/pip install -r tools/maia3/requirements.txt
git clone https://github.com/CSSLab/maia3.git /tmp/maia3
git -C /tmp/maia3 checkout 1e13597c42d4858b7cfd7cfdae01e297263364b2
curl -sL -o /tmp/maia3-5m.pt \
  https://huggingface.co/UofTCSSLab/Maia3-5M/resolve/b6559de2398d7140b985f28fd2c19fb5e47ddabe/maia3-5m.pt
shasum -a 256 /tmp/maia3-5m.pt   # must match the checkpoint SHA-256 above
PYTHONPATH=/tmp/maia3 /tmp/maia-venv/bin/python tools/maia3/export_onnx.py /tmp/maia3-5m.pt /tmp/maia3-5m.onnx
shasum -a 256 /tmp/maia3-5m.onnx  # must match the bundled SHA-256 above
```

On 2026-11-21 two clean exports from these inputs were byte-for-byte identical to the bundled file.
To regenerate the encoder fixtures as well, see `tools/maia3/README.md`.
