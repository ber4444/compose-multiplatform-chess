# Maia-3 model provenance

Every build runs a conversion of the released Maia-3 5M model: as the default opponent, and in the
History screen's human-move explorer.

- **LiteRT int8** (`maia3-5m-int8.tflite`) on Android (`org.tensorflow.lite.Interpreter`) and iOS
  (the TFLite C API). Both bundle the same file, so Maia works with no network:
  - `app/src/androidMain/resources/maia/`;
  - `app/src/iosMain/composeResources/files/maia/`.
- **LiteRT float** (`maia3-5m.tflite`) on web (LiteRT.js). It is the file the int8 one is quantized
  from. **Web does not bundle it.** It downloads it from the public Hugging Face repo
  [`ber4444/maia3-5m-litert`](https://huggingface.co/ber4444/maia3-5m-litert), pinned to commit
  `d80145dd10ca56bbe4453c667c10ea56c3c9a99b`, and checks its SHA-256 before use. The repo's model card
  carries the AGPL-3.0 licence and this provenance. Web moves to the int8 file once it is uploaded to
  that repo.
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

## LiteRT conversion (downloaded on web; the input to the int8 file)

- File: `maia3-5m.tflite` (the Hugging Face file; no longer bundled)
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

## LiteRT int8 quantization (bundled on Android and iOS)

- File: `maia3-5m-int8.tflite`
- Size: `5889056` bytes
- SHA-256: `bb8c8247a41fb6eccbbf7b717f496d9d02e723a43c1fe484c0b0f16a63f9f122`
- Input: the float `maia3-5m.tflite` above, checked against its SHA-256.
- Quantizer: `litert-quantizer 0.11.0` on `ai-edge-litert 2.3.0`, recipe `weight_only_wi8_afp32`
  (`tools/maia3/requirements-quantize.txt`). The deprecated `ai-edge-quantizer 0.10.1` writes the same
  quantized model.
- Layout: every constant is stored **inside** the flatbuffer, as in the float export. The quantizer
  itself writes each weight matrix after the flatbuffer and points at it by offset, and iOS cannot run
  that file: TensorFlowLiteC 2.17.0's `TfLiteInterpreterCreate` builds its interpreter without the
  model's allocation, so offset buffers load as tensors with no data and inference fails with "Input
  tensor 150 lacks data" (later releases pass the allocation; 2.17.0 is the last iOS one).
  `quantize_litert.py` therefore rewrites the quantizer's output inline (`inline_buffers`) and runs
  its checks on the rewritten file. The tensors are the same bytes in a different place: the two
  layouts give bit-identical logits on `ai-edge-litert`.
- What changes: each weight matrix is stored as int8 with a float scale per output channel, and is
  dequantized to float32 once, when the interpreter is prepared. Every op still computes in float32;
  the only new op is DEQUANTIZE (53 instances), so the iOS TensorFlowLiteC 2.17.0 runtime still
  applies. Inputs, output and signature are unchanged. Rounding the weights is a modification of the
  model, so this file is a modified form of the checkpoint under the same AGPL-3.0; it adds no
  training data.
- Why: the float file was 18.9 MB of the Android download (weights barely compress); this one is
  5.0 MB compressed.
- Parity: `tools/maia3/quantize_litert.py` refuses to write the file unless, against `MaiaGolden`
  (the PyTorch reference), the top move agrees and every top-5 share is within 0.02 (measured:
  `0.0077`), and, over 598 positions from Maia's own self-play scored at 1100, 1500, 1900 and 2350,
  the total-variation distance from the float model averages at most 0.01 (measured: `0.0047`) and
  never exceeds 0.05 (measured: `0.0259`). The same top move was chosen in 99.5% of those positions;
  the rest were near-ties.
- Rejected alternatives, measured the same way: dynamic-range int8 (`dynamic_wi8_afp32`) is the same
  size and about 3x faster on a desktop CPU, but drifts twice as far (mean TV `0.0105`); weight-only
  int4 (3.4 MB) chose a different top move in 12% of positions.

## Integrity checks

These fail if a bundled file's SHA-256 differs from the values above:
- `OnnxMaiaRuntimeTest` (desktop, ONNX);
- `MaiaLiteRtSimulatorTest` (iOS, int8);
- `MaiaOnDeviceTest` (Android device, int8).

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

The int8 file needs a third environment from `tools/maia3/requirements-quantize.txt` (Python 3.12, no
torch), and the float file from the previous step:

```sh
python3.12 -m venv /tmp/maia-quantize-venv
/tmp/maia-quantize-venv/bin/pip install -r tools/maia3/requirements-quantize.txt
/tmp/maia-quantize-venv/bin/python tools/maia3/quantize_litert.py /tmp/maia3-5m.tflite /tmp/maia3-5m-int8.tflite
shasum -a 256 /tmp/maia3-5m-int8.tflite  # must match the LiteRT int8 SHA-256 above
```

On 2026-11-21, two clean exports of each format were byte-for-byte identical to the bundled files.
On 2026-11-23, two clean quantizations were byte-for-byte identical to the bundled int8 file.
To regenerate the encoder fixtures as well, see `tools/maia3/README.md`.
