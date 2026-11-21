# Maia-3 model provenance

Every build runs a conversion of the released Maia-3 5M model: as the default opponent, and in the
History screen's human-move explorer.

- **LiteRT** (`.tflite`) on Android (`org.tensorflow.lite.Interpreter`), iOS (the TFLite C API) and
  web (LiteRT.js). Android and iOS bundle the same file, so Maia works with no network:
  - `app/src/androidMain/resources/maia/`;
  - `app/src/iosMain/composeResources/files/maia/`.

  **Web does not bundle it.** It downloads the identical file from the public Hugging Face repo
  [`ber4444/maia3-5m-litert`](https://huggingface.co/ber4444/maia3-5m-litert), pinned to commit `d80145dd10ca56bbe4453c667c10ea56c3c9a99b`, and checks its SHA-256 before use.
  The repo's model card carries the AGPL-3.0 licence and this provenance.
- **ONNX** on desktop (ONNX Runtime), the one platform LiteRT publishes no runtime for:
  `app/src/desktopMain/resources/maia/`.
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

## Bundled ONNX conversion (desktop)

- File: `app/src/desktopMain/resources/maia/maia3-5m.onnx`
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

## LiteRT conversion (bundled on Android and iOS, downloaded on web)

- File: `maia3-5m.tflite` (the bundled copies and the Hugging Face file are identical)
- Size: `20813796` bytes
- SHA-256: `f0acad2d11db917c10ecc4418a5ef5600c00e21dcac1f54b77008acfeeed998e`
- Converter: `litert-torch 0.10.0` (`litert-converter 0.5.0`) on torch `2.14.1`, with `jax`/`jaxlib`
  pinned to `0.11.2`. With jax 0.12, conversion fails: "failed to convert VHLO to v1.19.0".
- Signature `serving_default`:
  - inputs: `args_0` tokens float32 `[1, 64, 97]`, `args_1`/`args_2` self/opponent Elo **int32** `[1]`;
  - output: `output_0` move logits `[1, 4352]`.
- Ops: ADD, BATCH_MATMUL, BROADCAST_TO v2, CAST, CONCATENATION, FULLY_CONNECTED v5, GELU, MAXIMUM,
  MEAN, MINIMUM, MUL, RESHAPE, RSQRT, SLICE, SOFTMAX, SQUARED_DIFFERENCE, SUB and TRANSPOSE. All are
  low op versions, which is why the iOS runtime can be TensorFlowLiteC 2.17.0, the last stable iOS
  release.
- Parity: `tools/maia3/export_litert.py` checks the file against the **unmodified** PyTorch model and
  refuses to write it unless every legal-move logit matches within 1e-3. The measured difference is
  `9.5e-06`, which is tighter than the ONNX export.

## Integrity checks

These fail if a bundled file's SHA-256 differs from the values above:
- `OnnxMaiaRuntimeTest` (desktop);
- `MaiaLiteRtSimulatorTest` (iOS);
- `MaiaOnDeviceTest` (Android device).

`MaiaModelFiles` pins the same hashes and sizes, and the desktop and Android loaders refuse a file of
the wrong size.

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

For the LiteRT file, use a **separate** Python 3.12 environment from `tools/maia3/requirements-litert.txt`
(litert-torch needs torch < 2.15):

```sh
python3.12 -m venv /tmp/maia-litert-venv
/tmp/maia-litert-venv/bin/pip install -r tools/maia3/requirements-litert.txt
PYTHONPATH=/tmp/maia3 /tmp/maia-litert-venv/bin/python tools/maia3/export_litert.py /tmp/maia3-5m.pt /tmp/maia3-5m.tflite
shasum -a 256 /tmp/maia3-5m.tflite  # must match the LiteRT SHA-256 above
```

On 2026-11-21, two clean exports of each format were byte-for-byte identical to the bundled files.
To regenerate the encoder fixtures as well, see `tools/maia3/README.md`.
