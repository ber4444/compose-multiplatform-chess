# Maia-3 export and golden fixtures

Maia-3 5M ships twice: as LiteRT (`maia3-5m.tflite`, bundled on Android/iOS and downloaded by web from
`ber4444/maia3-5m-litert` on Hugging Face, made by
`export_litert.py`) and as ONNX (`maia3-5m.onnx`, desktop, made by `export_onnx.py`). These scripts
reproduce both files and the encoder fixtures; nothing here runs in CI. The pinned revisions, hashes and exact package versions
are in [`docs/maia3-model-provenance.md`](../../docs/maia3-model-provenance.md), and that document's
reproduction steps are the ones to follow.

`export_onnx.py` applies the two fixes from
`docs/benchmarks/on-device-ai/maia3-conversion-spike-2026-08.md`. It decomposes `torch.nn.RMSNorm`
with torch's default `eps`, which is what `RMSNorm(d_model)` resolves to: the input dtype's machine
epsilon, *not* 1e-6. It also exports with `dynamo=False`.

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
  top-5 moves. It is called by `OnnxMaiaRuntimeTest` (desktop, ONNX) and `MaiaLiteRtSimulatorTest`
  (iOS, LiteRT); both also check the file's SHA-256.
- `MaiaOnDeviceTest` (androidDeviceTest) does the same through LiteRT on a device and logs load and
  inference times under the `MaiaOnDevice` tag.
- Web has no automated runtime test: the karma browser would need the CDN and the served model. It
  was checked in Chrome: the same logits within 9.5e-06, and the real app opening a game with Maia.

If you change the model, update all of these together: every bundled copy, the Hugging Face upload and
`MODEL_REVISION` in `MaiaRuntime.wasm.kt`, `MaiaModelFiles`, the provenance document, and `MaiaGolden.kt`.
