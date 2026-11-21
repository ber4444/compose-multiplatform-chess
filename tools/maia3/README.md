# Maia-3 export and golden fixtures

The human-move explorer's offline source is Maia-3 5M. Its ONNX export is bundled at
`app/src/jvmCommonMain/resources/maia/maia3-5m.onnx`. These scripts reproduce that file and the
encoder fixtures; nothing here runs in CI. The pinned revisions, hashes and exact package versions
are in [`docs/maia3-model-provenance.md`](../../docs/maia3-model-provenance.md), and that document's
reproduction steps are the ones to follow.

`export_onnx.py` applies the two fixes from
`docs/benchmarks/on-device-ai/maia3-conversion-spike-2026-08.md`. It decomposes `torch.nn.RMSNorm`
with torch's default `eps`, which is what `RMSNorm(d_model)` resolves to: the input dtype's machine
epsilon, *not* 1e-6. It also exports with `dynamo=False`.

## Regenerate the golden fixtures

```bash
PYTHONPATH=/tmp/maia3 /tmp/maia-venv/bin/python tools/maia3/make_golden.py \
  /tmp/maia3-5m.pt app/src/jvmCommonMain/resources/maia/maia3-5m.onnx \
  app/src/commonTest/kotlin/com/example/myapplication/explorer/MaiaGolden.kt
```

The script refuses to write the fixture if the ONNX logits differ from the **unpatched** PyTorch
model's by more than 1e-3.

- `MaiaEncoderTest` (commonTest, every target) compares the Kotlin encoder's token indices and
  legal-move indices against the fixture. It needs no model.
- `OnnxMaiaRuntimeTest` (desktopTest) runs the bundled model end to end against the fixture's top-5
  moves and checks the file's SHA-256 against the provenance document.
- `MaiaOnDeviceTest` (androidDeviceTest) does the same through onnxruntime-android on a device and
  logs load and inference times under the `MaiaOnDevice` tag.

If you change the model, update all of these together: the file, `BundledMaiaModel.SHA256`/`BYTES`,
the provenance document, and `MaiaGolden.kt`.
