package com.example.myapplication.board3d

/**
 * Phase D.4 — single source of truth for shared wgpu PBR constants.
 *
 * The wgpu renderer path is shared between the desktop (`DesktopWgpuChessRenderer`) and wasm
 * (`WebGpuChessRenderer`) targets via the `wgpuMain` source set, so both backends read the **same**
 * material constants here. This keeps desktop and web visually locked together, and gives the
 * `DesktopRendererQualityPreset` (Phase D.2) a clean place to inherit defaults from.
 *
 * Centralized values:
 * - [DEFAULT_TONEMAP_EXPOSURE] — referenced by `WgpuShaders.wgpuShader` / `skyShader` so the
 *   DEFAULT preset is the only place the exposure number is written.
 * - [ROUGHNESS_BOARD] / [ROUGHNESS_PIECE] / [ROUGHNESS_FRAME] — referenced by
 *   [wgpuMaterialRoughness] so all three (roughness values for the board surface, the pieces, and
 *   the engraved stone rim) are named rather than buried in a `when` arm.
 *
 * Values themselves are NOT changed by this object — every constant matches the pre-Phase-D
 * literal so DEFAULT remains byte-identical to the shipped path. The object exists so future
 * tuning (e.g. matching Android ACES more closely) has one place to edit and a name to reference
 * in commit messages and docs.
 */
internal object WgpuMaterialDefaults {
    /** Uncharted2 tonemap exposure used by both scene + sky shaders on the DEFAULT preset. */
    const val DEFAULT_TONEMAP_EXPOSURE: Float = 4.5f

    /** PBR roughness for the marble board surface (`ChessTexture.BOARD`). */
    const val ROUGHNESS_BOARD: Float = 0.1f

    /** PBR roughness for chess pieces (`ChessTexture.WHITES` / `ChessTexture.BLACKS`). */
    const val ROUGHNESS_PIECE: Float = 0.4f

    /** PBR roughness for the engraved stone frame (`ChessTexture.FRAME`). */
    const val ROUGHNESS_FRAME: Float = 0.68f
}
