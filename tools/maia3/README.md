# Maia-3 export and golden fixtures

Maia-3 5M is built in three forms, all from the same checkpoint:

- `maia3-5m-int8.tflite`, bundled on Android and iOS and downloaded on web from
  `ber4444/maia3-5m-litert` on Hugging Face: `quantize_litert.py` applied to the float LiteRT file.
  Weight-only int8, 5.9 MB instead of 20.8 MB, still computed in float32.
- `maia3-5m.tflite`, the float LiteRT export made by `export_litert.py`: the quantizer's input. No
  build runs it; it stays on Hugging Face for older web builds.
- `maia3-5m.onnx`, desktop, made by `export_onnx.py`.

These scripts reproduce every file and the encoder fixtures; nothing here runs in CI. The pinned
revisions, hashes and exact package versions are in [`docs/maia3-model-provenance.md`](../../docs/maia3-model-provenance.md), and that document's
reproduction steps are the ones to follow.

`export_onnx.py` applies the two fixes from
`docs/benchmarks/on-device-ai/maia3-conversion-spike-2026-08.md`. It decomposes `torch.nn.RMSNorm`
with torch's default `eps`, which is what `RMSNorm(d_model)` resolves to: the input dtype's machine
epsilon, *not* 1e-6. It also exports with `dynamo=False`.

`quantize_litert.py` refuses to write the int8 file unless it stays close to the float one: against
`MaiaGolden`, the same top move and every PyTorch top-5 share within 0.02; over ~600 positions from
Maia's own self-play at the four difficulty ratings, a mean total-variation distance of at most 0.01
and a worst case of at most 0.05. It checks its encoder port against `MaiaGolden` first. It also
rewrites the quantizer's output so the weights sit inside the flatbuffer rather than after it: iOS's
TensorFlowLiteC 2.17 cannot read weights stored by offset (see the provenance document). It needs only
`requirements-quantize.txt` (no torch).

## Regenerate the golden fixtures

```bash
PYTHONPATH=/tmp/maia3 /tmp/maia-venv/bin/python tools/maia3/make_golden.py \
  /tmp/maia3-5m.pt app/src/desktopMain/resources/maia/maia3-5m.onnx \
  app/src/commonTest/kotlin/com/example/myapplication/explorer/MaiaGolden.kt
```

The script refuses to write the fixture if the ONNX logits differ from the **unpatched** PyTorch
model's by more than 1e-3.

- `MaiaEncoderTest` (commonTest, every target) compares the Kotlin encoder's token indices and
  legal-move indices against the fixture. It needs no model.
- `BundledMaiaParity` (commonTest helper) runs a bundled model end to end against the fixture's
  top-5 moves: the same top move, and each PyTorch share within a tolerance (1e-3 for a float
  export, 0.02 for the int8 file). It is called by `OnnxMaiaRuntimeTest` (desktop, float ONNX) and
  `MaiaLiteRtSimulatorTest` (iOS, int8 LiteRT); both also check the file's SHA-256.
- `MaiaOnDeviceTest` (androidDeviceTest) does the same through LiteRT on a device and logs load and
  inference times under the `MaiaOnDevice` tag.
- Web has no automated runtime test: the karma browser would need the CDN and the served model. It
  was checked in Chrome: the float file within 9.5e-06 of PyTorch, then the int8 file from Hugging
  Face within 4e-06 of `ai-edge-litert` on the same file, and the real app playing a game with Maia.

If you change the model, update all of these together: every bundled copy (re-quantized), the Hugging
Face upload and `MODEL_REVISION` in `MaiaRuntime.wasm.kt`, `MaiaModelFiles`, the provenance document,
and `MaiaGolden.kt`. The int8 tolerance lives in three places that must agree:
`GOLDEN_SHARE_TOLERANCE` in `quantize_litert.py`, `BundledMaiaParity.INT8_SHARE_TOLERANCE`, and
`MaiaOnDeviceTest`'s copy of it.
