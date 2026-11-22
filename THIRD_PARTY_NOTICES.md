# Third-Party Notices

This project vendors third-party code and assets used by the chess app.

## Stockfish

Android vendors official Stockfish 17 executables under `app/src/androidMain/jniLibs/`.
iOS uses ChessKitEngine (MIT), which builds Stockfish 17 from source and also bundles Lc0 0.31.1;
the NNUE networks it loads are checked in under `iosApp/iosApp/Resources/`. Stockfish is licensed
under GPLv3 or later, Lc0 under GPLv3. The app's **Settings → Open-source licences** screen shows
the GPL text and links to the Stockfish `sf_17` source.

See:

- `docs/Stockfish.md`
- `docs/Stockfish-COPYING.txt`
- `docs/Stockfish-README.md`

## 2D chess pieces

The piece drawables in `app/src/commonMain/composeResources/drawable/` (`{king,queen,rook,bishop,knight,pawn}_{light,dark}.xml`)
are Colin M.L. Burnett's chess set from Wikimedia Commons, converted to Android vector drawables.
Burnett released the set under GPLv2 or later, BSD and GFDL; this project uses it under the GPL.

- https://commons.wikimedia.org/wiki/Category:SVG_chess_pieces

`no_winner.xml` in the same directory is a Material Icons glyph (Google, Apache-2.0).

## Chess 3D Model

The 3D chess model is stored at:

- `app/src/commonMain/composeResources/files/models/chess.glb`

The currently checked-in model was originally derived from the `chess.gltf` asset in the
`vkChess` project. That repository is MIT licensed, but its README credits the piece models to
Matt Joos without a complete model-asset license grant, which is why issue #32 selected a
replacement source.

Approved replacement source:

- Model: "Chess"
- Author: Verfassen
- License: Creative Commons Attribution 4.0 (`CC BY 4.0`)
- License requirements: author credit required; commercial use allowed
- Model page: https://sketchfab.com/3d-models/chess-e54c2d04d4f74823b69ba4a794fb4500
- License URL: http://creativecommons.org/licenses/by/4.0/

This metadata was verified through Sketchfab's public model API:

- https://api.sketchfab.com/v3/models/e54c2d04d4f74823b69ba4a794fb4500

When `chess.glb` is replaced with the approved asset, keep this attribution with the shipped app.
Only CC0 or attribution-compatible Creative Commons model assets should be committed.

## Papermill Environment Assets

The 3D renderers use offline-generated papermill environment maps:

- `app/src/commonMain/composeResources/files/env/face_0.exr` through `face_5.exr`
- `app/src/commonMain/composeResources/files/env/papermill_ibl.ktx`
- `app/src/commonMain/composeResources/files/env/papermill_skybox.ktx`

These are generated offline for platform renderers and are not produced at runtime.

## SceneView

Android 3D rendering uses SceneView (`io.github.sceneview:sceneview`) as the Compose-native
Filament host.

- https://github.com/SceneView/sceneview-android

## Filament

Desktop, Web (Wasm), and iOS 3D rendering use Google's Filament renderer: desktop fetches the native
C++ release with `tools/fetch_filament_desktop.sh`, web loads `filament.js` at runtime, and iOS
fetches Filament xcframeworks with `tools/fetch_filament_ios.sh`. Android uses Filament via SceneView
(above). Filament is licensed under Apache-2.0.

- https://github.com/google/filament

## LiteRT

Android, iOS and web run the Maia-3 model through LiteRT (formerly TensorFlow Lite), licensed under
Apache-2.0:
- Android: `com.google.ai.edge.litert:litert`.
- iOS: the TensorFlowLiteC framework, fetched at build time and linked statically.
- Web: LiteRT.js (`@litertjs/core`), loaded from jsDelivr at runtime.

- https://github.com/google-ai-edge/LiteRT

## ONNX Runtime

The desktop build runs the Maia-3 model through ONNX Runtime (`com.microsoft.onnxruntime:onnxruntime`),
licensed under MIT. LiteRT has no desktop JVM runtime.

- https://github.com/microsoft/onnxruntime

## Maia-3

- Project: <https://github.com/CSSLab/maia3>
- Model: <https://huggingface.co/UofTCSSLab/Maia3-5M>
- Copyright: University of Toronto CSSLab contributors
- Licence: GNU Affero General Public License v3.0

`maia3-5m.tflite` (bundled on Android and iOS; downloaded by the web build from
[`ber4444/maia3-5m-litert`](https://huggingface.co/ber4444/maia3-5m-litert)) and `maia3-5m.onnx` (desktop) are converted forms of the released
Maia-3 5M checkpoint. They add no training data and do not change the learned weights.
Exact revisions, checkpoint and conversion hashes, and reproduction instructions are in
[docs/maia3-model-provenance.md](docs/maia3-model-provenance.md). The conversion tools are
`tools/maia3/export_litert.py` and `tools/maia3/export_onnx.py`.

`app/src/commonMain/kotlin/com/example/myapplication/explorer/MaiaEncoder.kt` adapts Maia-3's input
and output encoding (`maia3/dataset.py`, `maia3/utils.py`) to Kotlin. `MaiaGolden.kt` holds
reference outputs that `tools/maia3/make_golden.py` generates from the checkpoint. Both remain under
the AGPL-3.0, combined with this GPL-3.0-or-later app as section 13 of both licences permits; see
COPYING.md.

## Lichess Chess Openings

The opening explainer corpus includes the checked-in `a.tsv` through `e.tsv` files from
`lichess-org/chess-openings`. The collection is released under the CC0 Public Domain Dedication.

- https://github.com/lichess-org/chess-openings
- https://creativecommons.org/publicdomain/zero/1.0/

## all-MiniLM-L6-v2

The opening explainer Docker image downloads the pinned `sentence-transformers/all-MiniLM-L6-v2`
ONNX model and vocabulary during the image build. The model is licensed under Apache-2.0.

- https://huggingface.co/sentence-transformers/all-MiniLM-L6-v2
- https://www.apache.org/licenses/LICENSE-2.0

## Wikibooks Chess rules corpus

The offline rules Q&A corpus in `onDeviceAi/src/commonMain/resources/rulesCorpus/` contains
project-authored, condensed adaptations of the Wikibooks **Chess/Rules** material. Wikibooks text
is available under Creative Commons Attribution-ShareAlike 4.0 and the GNU Free Documentation
License; the adapted corpus is distributed under CC BY-SA 4.0.

- https://en.wikibooks.org/wiki/Chess/Rules
- https://en.wikibooks.org/wiki/Wikibooks:Copyrights
- https://creativecommons.org/licenses/by-sa/4.0/
