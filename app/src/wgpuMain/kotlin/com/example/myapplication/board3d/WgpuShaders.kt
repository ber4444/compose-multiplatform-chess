package com.example.myapplication.board3d

internal const val WGPU_SHADER = """
struct UBO {
    viewProj: mat4x4<f32>,
    lightViewProj: mat4x4<f32>,
    camPos: vec4<f32>,
    invViewProj: mat4x4<f32>,
};

@group(0) @binding(0) var tex: texture_2d<f32>;
@group(0) @binding(1) var<uniform> ubo: UBO;
@group(0) @binding(2) var samp: sampler;
@group(0) @binding(3) var envTex: texture_cube<f32>;
@group(0) @binding(4) var envSamp: sampler;

struct Material {
    roughness: f32,
};
@group(0) @binding(5) var<uniform> material: Material;

struct VertexInput {
    @location(0) inPos: vec3<f32>,
    @location(1) inNormal: vec3<f32>,
    @location(2) inUv: vec2<f32>,
    @location(3) inTint: vec3<f32>,
};

struct VertexOutput {
    @builtin(position) Position: vec4<f32>,
    @location(0) vNormal: vec3<f32>,
    @location(1) vUv: vec2<f32>,
    @location(2) vTint: vec3<f32>,
    @location(3) vWorldPos: vec3<f32>,
};

@vertex
fn vs_main(input: VertexInput) -> VertexOutput {
    var output: VertexOutput;
    output.Position = ubo.viewProj * vec4<f32>(input.inPos, 1.0);
    output.vNormal = input.inNormal;
    output.vUv = input.inUv;
    output.vTint = input.inTint;
    output.vWorldPos = input.inPos;
    return output;
}
struct FragmentInput {
    @location(0) vNormal: vec3<f32>,
    @location(1) vUv: vec2<f32>,
    @location(2) vTint: vec3<f32>,
    @location(3) vWorldPos: vec3<f32>,
};

const PI = 3.1415926535;

fn F_SchlickR(cosTheta: f32, F0: vec3<f32>, roughness: f32) -> vec3<f32> {
    let oneMinusR = 1.0 - roughness;
    return F0 + (max(vec3<f32>(oneMinusR), F0) - F0) * pow(1.0 - cosTheta, 5.0);
}

fn F_Schlick(cosTheta: f32, F0: vec3<f32>) -> vec3<f32> {
    return F0 + (vec3<f32>(1.0) - F0) * pow(1.0 - cosTheta, 5.0);
}

fn D_GGX(NoH: f32, roughness: f32) -> f32 {
    let a = roughness * roughness;
    let a2 = a * a;
    let d = NoH * NoH * (a2 - 1.0) + 1.0;
    return a2 / (PI * d * d + 1e-5);
}

fn G_SchlickSmithGGX(NoL: f32, NoV: f32, roughness: f32) -> f32 {
    let r = roughness + 1.0;
    let k = (r * r) / 8.0;
    let gl = NoL / (NoL * (1.0 - k) + k);
    let gv = NoV / (NoV * (1.0 - k) + k);
    return gl * gv;
}

// Karis analytic environment BRDF, standing in for vkChess's precomputed BRDF LUT.
fn envBRDFApprox(NoV: f32, roughness: f32) -> vec2<f32> {
    let c0 = vec4<f32>(-1.0, -0.0275, -0.572, 0.022);
    let c1 = vec4<f32>(1.0, 0.0425, 1.04, -0.04);
    let r = roughness * c0 + c1;
    let a004 = min(r.x * r.x, exp2(-9.28 * NoV)) * r.x + r.y;
    return vec2<f32>(-1.04, 1.04) * a004 + vec2<f32>(r.z, r.w);
}

fn uncharted2(x: vec3<f32>) -> vec3<f32> {
    let A = 0.15; let B = 0.50; let C = 0.10; let D = 0.20; let E = 0.02; let F = 0.30;
    return ((x * (A * x + C * B) + D * E) / (x * (A * x + B) + D * F)) - E / F;
}

// Ported from vkChess pbr.frag: Cook-Torrance direct light + IBL (env cube), Uncharted2 tonemap.
@fragment
fn fs_main(input: FragmentInput) -> @location(0) vec4<f32> {
    let N = normalize(input.vNormal);
    let V = normalize(ubo.camPos.xyz - input.vWorldPos);
    let R = reflect(-V, N);
    let NoV = max(dot(N, V), 0.0);

    let albedoData = textureSample(tex, samp, input.vUv);
    let albedo = pow(albedoData.rgb, vec3<f32>(2.2)) * input.vTint;
    let roughness = clamp(material.roughness, 0.04, 1.0);
    let F0 = vec3<f32>(0.04);

    // Direct Cook-Torrance for one key directional light (gives the crisp specular highlight).
    let L = normalize(vec3<f32>(0.5, 0.9, 0.45));
    let H = normalize(V + L);
    let NoL = max(dot(N, L), 0.0);
    let NoH = max(dot(N, H), 0.0);
    let D = D_GGX(NoH, roughness);
    let G = G_SchlickSmithGGX(NoL, NoV, roughness);
    let Fd = F_Schlick(max(dot(H, V), 0.0), F0);
    let specD = (D * G) * Fd / (4.0 * NoL * NoV + 1e-4);
    let kDl = vec3<f32>(1.0) - Fd;
    let Lo = (kDl * albedo / PI + specD) * NoL * 2.5;

    // Image-based lighting from the env cube (simplified: sample mips directly, no precompute).
    let maxMip = 9.0;
    // Cube faces are converted to WebGPU row orientation during upload. Keep world +Y here;
    // flipping it a second time put the garden floor above the board and the sky below it.
    let irradiance = textureSampleLevel(envTex, envSamp, N, maxMip).rgb;
    let diffuse = irradiance * albedo;
    let prefiltered = textureSampleLevel(envTex, envSamp, R, roughness * maxMip).rgb;
    let Fr = F_SchlickR(NoV, F0, roughness);
    let brdf = envBRDFApprox(NoV, roughness);
    let specularIBL = prefiltered * (Fr * brdf.x + brdf.y);
    let kD = vec3<f32>(1.0) - Fr;
    let ambient = kD * diffuse + specularIBL;

    // Keep everything in HDR through the tonemap (NO pre-clamp; clamping here flattened the image).
    var color = ambient + Lo;
    color = color + max(input.vTint - vec3<f32>(1.0), vec3<f32>(0.0)) * albedoData.rgb * 1.6; // selection glow

    let exposure = 4.5;
    let gamma = 2.2;
    color = uncharted2(color * exposure);
    color = color * (1.0 / uncharted2(vec3<f32>(11.2)));
    color = pow(color, vec3<f32>(1.0 / gamma));
    return vec4<f32>(color, 1.0);
}
"""

/** Skybox: samples the papermill HDR environment cubemap as the background (vkChess look). */
internal const val SKY_SHADER = """
struct UBO {
    viewProj: mat4x4<f32>,
    lightViewProj: mat4x4<f32>,
    camPos: vec4<f32>,
    invViewProj: mat4x4<f32>,
};

@group(0) @binding(0) var<uniform> ubo: UBO;
@group(0) @binding(1) var envTex: texture_cube<f32>;
@group(0) @binding(2) var envSamp: sampler;

struct SkyOut {
    @builtin(position) pos: vec4<f32>,
    @location(0) dir: vec3<f32>,
};

@vertex
fn vs_sky(@builtin(vertex_index) vid: u32) -> SkyOut {
    var output: SkyOut;
    let p = vec2<f32>(f32((vid << 1u) & 2u), f32(vid & 2u));
    let ndc = p * 2.0 - 1.0;
    let unprojected = ubo.invViewProj * vec4<f32>(ndc, 1.0, 1.0);
    output.dir = unprojected.xyz / unprojected.w - ubo.camPos.xyz;
    output.pos = vec4<f32>(ndc, 1.0, 1.0); // far plane, behind everything
    return output;
}

fn uncharted2(x: vec3<f32>) -> vec3<f32> {
    let A = 0.15; let B = 0.50; let C = 0.10; let D = 0.20; let E = 0.02; let F = 0.30;
    return ((x * (A * x + C * B) + D * E) / (x * (A * x + B) + D * F)) - E / F;
}

@fragment
fn fs_sky(input: SkyOut) -> @location(0) vec4<f32> {
    let exposure = 4.5;
    let gamma = 2.2;
    let sampleDir = normalize(input.dir);
    // Sample a high (blurry) mip so the environment reads as a defocused bokeh backdrop behind the
    // in-focus board, instead of the sharp garden photo. The cube has ~10 mips; mip 5 ~ 32x blur.
    var color = textureSampleLevel(envTex, envSamp, sampleDir, 5.0).rgb;
    color = uncharted2(color * exposure);
    color = color * (1.0 / uncharted2(vec3<f32>(11.2, 11.2, 11.2)));
    color = pow(color, vec3<f32>(1.0 / gamma, 1.0 / gamma, 1.0 / gamma));
    return vec4<f32>(color, 1.0);
}
"""
