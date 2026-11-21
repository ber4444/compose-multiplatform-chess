# Maia-3 export and golden fixtures

The human-move explorer's offline source is Maia-3 5M, exported to ONNX. Nothing here runs in CI and
no model is checked in. Hugging Face hosts only the PyTorch checkpoint, and the weights' licence
needs a decision before the app downloads them for users (see the PR that added this).

## Export the model (desktop)

```bash
python3 -m venv /tmp/maia-venv
/tmp/maia-venv/bin/pip install torch numpy onnx onnxscript onnxruntime python-chess
git clone --depth 1 https://github.com/CSSLab/maia3.git /tmp/maia3
curl -sL -o /tmp/maia3-5m.pt https://huggingface.co/UofTCSSLab/Maia3-5M/resolve/main/maia3-5m.pt
PYTHONPATH=/tmp/maia3 /tmp/maia-venv/bin/python tools/maia3/export_onnx.py /tmp/maia3-5m.pt /tmp/maia3-5m.onnx
CHESS_MAIA_MODEL=/tmp/maia3-5m.onnx ./gradlew :app:run
```

`export_onnx.py` applies the two fixes from
`docs/benchmarks/on-device-ai/maia3-conversion-spike-2026-08.md`: it decomposes `torch.nn.RMSNorm`
(with torch's default `eps`, the input dtype's machine epsilon, which is what `RMSNorm(d_model)`
resolves to) and exports with `dynamo=False`.

## Regenerate the golden fixtures

```bash
PYTHONPATH=/tmp/maia3 /tmp/maia-venv/bin/python tools/maia3/make_golden.py \
  /tmp/maia3-5m.pt /tmp/maia3-5m.onnx \
  app/src/commonTest/kotlin/com/example/myapplication/explorer/MaiaGolden.kt
```

It refuses to write the fixture if the ONNX logits differ from the **unpatched** PyTorch model's by
more than 1e-3. Measured on 2026-11-21 with torch 2.15 and onnxruntime 1.32: max diff 1.8e-05.

- `MaiaEncoderTest` (commonTest, every target) compares the Kotlin encoder's token indices and
  legal-move indices against the fixture. It needs no model.
- `OnnxMaiaRuntimeTest` (desktopTest) compares the end-to-end top-5 moves and probabilities. It is
  skipped unless `CHESS_MAIA_MODEL` names the exported file.
