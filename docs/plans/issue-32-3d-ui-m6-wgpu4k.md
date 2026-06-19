# Issue #32 — M6: wgpu4k desktop/web renderer — implementation notes & decisions

The desktop **wgpu4k** WebGPU renderer is **implemented and working** (`DesktopWgpuChessRenderer`):
build wiring (GitLab repo, `wgpu4k-toolkit` dep, JDK-26 toolchain for desktop), runtime (offscreen
`CAMetalLayer` → adapter/device via Panama FFM → render → `ImageBitmap` readback), **F1 (papermill
environment skybox)**, and **F2 (PBR + IBL piece lighting)** are all done and verified via
`Wgpu4kFrameDumpTest` → `build/wgpu-frame.png`.
(Spike/implementation detail lives in git history.)

The web backend is also implemented (`WebGpuChessRenderer`), reusing the same `wgpuMain` WGSL and geometry
while rendering directly to the overlay canvas instead of doing desktop's CPU readback.

This doc now tracks the **standing decisions** that downstream WebGPU work depends on and the historical
shape of the F2 implementation. Apple/Android fidelity is [M7 (folded into graphics-quality.md)](graphics-quality.md).

## Implemented: F2 — PBR + IBL (the piece lighting)

`app/src/wgpuMain/.../WgpuShaders.kt` now carries the vkChess look (Sascha-Willems glTF PBR +
image-based lighting), mirroring this repo's `VulkanChessRenderer` PBR fragment as a simplified
cross-target WebGPU port — not vkChess's full IBL precompute (see decisions).

- Cook-Torrance direct light: `D_GGX`, `G_SchlicksmithGGX`, `F_Schlick`/`F_SchlickR` (one directional light).
- IBL ambient from the **env cube already bound for the skybox**: irradiance ≈
  `textureSampleLevel(env, N, highMip)`; prefiltered ≈ `textureSampleLevel(env, R, roughness*maxMip)`;
  specular = `prefiltered*(F*brdf.x+brdf.y)` with an analytic BRDF approx (no BRDF-LUT).
- **Uncharted2 tonemap + gamma 2.2 + exposure (~4.5)** replacing the current Reinhard (match the sky pass).
- Materials by draw group: pieces = wood dielectric (metallic 0, roughness ~0.45), board marble (~0.25).
- Wiring: add the env cube + env sampler to the **main** pipeline's bind group (bindings 3,4), the way
  the sky bind group already does.
- Files: `WgpuShaders.kt` (fragment), `DesktopWgpuChessRenderer.kt` and `WebGpuChessRenderer.kt`
  (bind-group entries).
- Verify with `Wgpu4kFrameDumpTest` → `build/wgpu-frame.png`, plus `:app:wasmJsTest` and
  `:app:wasmJsBrowserDistribution`.

## Standing decisions / tradeoffs

- **Simplified IBL, not full precompute** (decision). Sample the env cube's mips directly instead of
  generating irradiance / prefiltered-env / BRDF-LUT maps. Visually ~equivalent for this scene; far less
  code. Full precompute is a possible later upgrade.
- **No dynamic shadows** (decision). vkChess itself has none (grounding is IBL/AO). The repo's Vulkan
  renderer added soft shadows as an extra — out of scope for parity (would be a separate F3).
- **wgpu4k is a pre-release SNAPSHOT** (`io.ygdrasil:wgpu4k-toolkit:0.2.0-SNAPSHOT`) from a **GitLab
  Maven repo** (not Maven Central). **Decision Resolved:** We accept the stability risk of the SNAPSHOT and will proceed (see [issue-32-3d-ui-unresolved-questions.md](issue-32-3d-ui-unresolved-questions.md)).
- **Mobile stays native.** The shared WebGPU/WGSL work covers **Desktop + Web**. Native fidelity goes
  through the existing SceneKit/Filament engines ([M7, folded into graphics-quality.md](graphics-quality.md));
  M8 records why Android replacement would require separate JNI/NDK surface work.
- **Desktop keeps CPU readback** (offscreen texture → `ImageBitmap`); Compose Desktop has no zero-copy
  surface interop. Negligible cost for a near-static board. (Web renders straight to the canvas — no readback.)
- **Desktop uses JVM target 24 and a JDK-26 launcher** (Panama FFM). `:app:desktopTest` and `:app:run`
  use a scoped JDK-26 toolchain launcher + Rococoa `--add-opens=java.base/java.lang=ALL-UNNAMED`.

## WebGPU renderer gotchas (also apply to M4 wasm — same WGSL)

These were found the hard way on desktop; M4 reuses the same shaders, so they carry over:

- **No projection Y-flip.** WebGPU clip-space Y points up (unlike Vulkan). The Vulkan renderer's
  `proj.m11 *= -1` must NOT be carried over — it flips the image *and* the winding.
- **`cullMode = None`.** The shared `ChessSceneGeometry` winds the flat board/ground quads front-down
  while glTF pieces are front-out; no single cull setting shows both. (Solid pieces rely on depth.)
- **Skybox is the background → `ChessSceneGeometry.build(includeGround = false)`.** The giant grey ground
  plane otherwise occludes the environment.
- **UBO std140 offsets.** `camPos` is a `vec4` (bytes 128–143), so `invViewProj` (mat4) starts at **byte
  144 = float 36**, not float 48. Getting this wrong silently zeroes the sky's view directions.
- **WGSL must be ASCII.** naga mis-tokenizes non-ASCII (e.g. an em-dash) inside `//` comments and swallows
  the next token → "expected expression" parse error / device panic.
- **Adapter needs a surface** (`requestAdapter` has no surfaceless overload). Desktop builds an offscreen
  `CAMetalLayer` (Rococoa); web gets its surface from the `<canvas>` (no such dance).
