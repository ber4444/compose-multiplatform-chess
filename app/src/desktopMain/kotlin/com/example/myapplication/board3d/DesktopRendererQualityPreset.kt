package com.example.myapplication.board3d

/**
 * Phase D.2 / D.5 — selectable desktop renderer quality preset.
 *
 * `DEFAULT` keeps the original shipped path: 1× MSAA, no shadow map, exposure 4.5. Functionally
 * identical to the pre-preset desktop renderer (the WGSL now has a `let shadow = 1.0;` constant
 * folded into the direct-light term, but the rendered pixels are unchanged because the constant
 * is multiplied in at full strength).
 *
 * `HIGH_QUALITY` is the demo preset: 4× MSAA + a real shadow mapping pass (depth-only pipeline
 * into a 2048² Depth32Float texture, hardware-comparison PCF sampling in the fragment shader) + a
 * small exposure bump to 5.0. This is the path that actually closes the dominant visual gaps
 * versus Android Filament per `docs/graphics/baseline-notes.md`.
 *
 * The shadow-pass cost is the main reason HIGH_QUALITY exists as a separate preset — adding a
 * second full-scene render per frame roughly doubles geometry-bound GPU work, which is fine on
 * desktop hardware but would regress WASM/mobile without a knob. The env-var gate
 * (`CHESS_DESKTOP_QUALITY=HIGH_QUALITY`) keeps it developer-only.
 */
enum class DesktopRendererQualityPreset(
    /** WebGPU pipeline `multisample.count`. 1 = no MSAA; 4 = 4× MSAA (with auto resolve). */
    val msaaSampleCount: Int,
    /** Substituted into the WGSL fragment/sky shaders' `let exposure = …` line. */
    val tonemapExposure: Float,
    /** If true, the renderer builds a depth-only pipeline + shadow texture and the WGSL gains
     *  `texture_depth_2d` / `sampler_comparison` bindings with PCF shadow sampling. */
    val shadowsEnabled: Boolean,
) {
    DEFAULT(msaaSampleCount = 1, tonemapExposure = 4.5f, shadowsEnabled = false),
    HIGH_QUALITY(msaaSampleCount = 4, tonemapExposure = 5.0f, shadowsEnabled = true);

    companion object {
        const val ENV_VAR: String = "CHESS_DESKTOP_QUALITY"

        /** Read [ENV_VAR]; unknown / missing values fall back to [DEFAULT] so a typo can't break rendering. */
        fun fromEnv(): DesktopRendererQualityPreset {
            val raw = runCatching { System.getenv(ENV_VAR) }.getOrNull() ?: return DEFAULT
            if (raw.isBlank()) return DEFAULT
            return entries.firstOrNull { it.name.equals(raw.trim(), ignoreCase = true) } ?: DEFAULT
        }
    }
}

