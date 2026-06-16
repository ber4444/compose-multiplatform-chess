# Third-Party Notices

This project vendors third-party code and assets used by the chess app.

## Stockfish

Android vendors official Stockfish 17 executables under `app/src/androidMain/jniLibs/`.
iOS uses ChessKitEngine, which includes Stockfish. Stockfish is licensed under GPLv3.

See:

- `docs/Stockfish.md`
- `docs/Stockfish-COPYING.txt`
- `docs/Stockfish-README.md`

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

## LWJGL

Desktop 3D rendering uses LWJGL modules, including Vulkan and shaderc bindings.
LWJGL is licensed under BSD-3-Clause.

- https://www.lwjgl.org/license

## jgltf-model

Desktop GLB parsing uses `de.javagl:jgltf-model`.
The library is licensed under MIT.

- https://github.com/javagl/JglTF

## JOML

Desktop 3D rendering uses JOML for vector and matrix math.
JOML is licensed under MIT.

- https://github.com/JOML-CI/JOML

## SceneView

Android 3D rendering uses SceneView (`io.github.sceneview:sceneview`) as the Compose-native
Filament host.

- https://github.com/SceneView/sceneview-android

## wgpu4k

Desktop and Web 3D rendering use `io.ygdrasil:wgpu4k-toolkit`.

- https://github.com/wgpu4k/wgpu4k
