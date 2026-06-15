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

fn F_SchlickR(cosTheta: f32, F0: vec3<f32>, roughness: f32) -> vec3<f32> {
    let oneMinusR = 1.0 - roughness;
    return F0 + (max(vec3<f32>(oneMinusR, oneMinusR, oneMinusR), F0) - F0) * pow(1.0 - cosTheta, 5.0);
}

fn uncharted2(x: vec3<f32>) -> vec3<f32> {
    let A = 0.15; let B = 0.50; let C = 0.10; let D = 0.20; let E = 0.02; let F = 0.30;
    return ((x * (A * x + C * B) + D * E) / (x * (A * x + B) + D * F)) - E / F;
}

@fragment
fn fs_main(input: FragmentInput) -> @location(0) vec4<f32> {
    let N = normalize(input.vNormal);
    let L = normalize(vec3<f32>(0.45, 1.0, 0.35));
    let ndl = max(dot(N, L), 0.0);
    let V = normalize(ubo.camPos.xyz - input.vWorldPos);
    let R = reflect(-V, N);
    
    let sh = 1.0;
    
    let albedoData = textureSample(tex, samp, input.vUv);
    let base = pow(albedoData.rgb, vec3<f32>(2.2, 2.2, 2.2));
    let albedo = base * input.vTint;
    
    let roughness = material.roughness;
    
    let F0 = vec3<f32>(0.04, 0.04, 0.04);
    let F = F_SchlickR(max(dot(N, V), 0.0), F0, roughness);
    
    let kD = (vec3<f32>(1.0, 1.0, 1.0) - F);
    
    let irradiance = textureSampleLevel(envTex, envSamp, N, 8.0).rgb;
    let diffuse = irradiance * albedo;
    
    let prefiltered = textureSampleLevel(envTex, envSamp, R, roughness * 9.0).rgb;
    let specular = prefiltered * (F * 1.0 + 0.0);
    
    let ambient = kD * diffuse + specular;
    let lit = ambient + albedo * (0.75 * ndl * sh);
    
    let glow = max(input.vTint - vec3<f32>(1.0, 1.0, 1.0), vec3<f32>(0.0, 0.0, 0.0)) * base * 1.6;
    var color = clamp(lit + glow, vec3<f32>(0.0, 0.0, 0.0), vec3<f32>(1.0, 1.0, 1.0));
    
    let exposure = 4.5;
    let gamma = 2.2;
    color = uncharted2(color * exposure);
    color = color * (1.0 / uncharted2(vec3<f32>(11.2, 11.2, 11.2)));
    color = pow(color, vec3<f32>(1.0 / gamma, 1.0 / gamma, 1.0 / gamma));
    
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
    var color = textureSampleLevel(envTex, envSamp, normalize(input.dir), 0.0).rgb;
    color = uncharted2(color * exposure);
    color = color * (1.0 / uncharted2(vec3<f32>(11.2, 11.2, 11.2)));
    color = pow(color, vec3<f32>(1.0 / gamma, 1.0 / gamma, 1.0 / gamma));
    return vec4<f32>(color, 1.0);
}
"""
