# Third-Party Notices

This project uses the following third-party materials:

## Chess 3D Model
The `chess.glb` 3D model is derived from the `chess.gltf` asset in the
[vkChess](https://github.com/jpbruyere/vkChess) project (repository: MIT License).
The piece models are credited there to Matt Joos (https://sketchfab.com/mathiasjoos).

⚠️ **License unverified — DO NOT ship this asset until resolved.** The vkChess README
credits the artist but states **no license** for the models themselves, and the MIT
license on the repository does not necessarily cover them. Before release, confirm the
Sketchfab model's actual license (commonly CC-BY 4.0, which requires attribution). If it
is NonCommercial/NoDerivatives or cannot be verified, replace it with a CC0 chess set or
procedurally generated meshes. See `docs/plans/issue-32-3d-ui-m1-foundation.md` (Phase E).

## LWJGL (Lightweight Java Game Library)
Used for the Vulkan Desktop backend.
License: BSD-3-Clause

## jgltf-model
Used for parsing GLTF/GLB models on Desktop.
License: MIT

## JOML (Java OpenGL Math Library)
Used for view/projection/model matrices in the Desktop Vulkan renderer.
License: MIT
