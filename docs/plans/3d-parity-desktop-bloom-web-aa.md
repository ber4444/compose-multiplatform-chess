# 3D Parity Pass 2 — Desktop materials/lighting/bloom + Web/iOS AA/bloom

> **Status: PLAN ONLY — not implemented.** Implementation-ready spec for an autonomous coding agent
> (Z.AI). Repo: `compose-multiplatform-chess`. All paths relative to repo root. Work on a branch off
> `3d-animation-driver-web-parity` (suggested: `3d-parity-desktop-bloom-web-aa`).

> [!CAUTION]
> This plan deliberately edits the **frozen 3D platform actuals** (`VulkanChessRenderer`,
> `chess3d-renderer.js`) that `CLAUDE.md` marks DO-NOT-TOUCH. That carve-out is authorized **for this
> task only**. Do **not** unify rendering paths, port one backend onto another, or change Android
> (`AndroidSceneViewChessRenderer`) — Android is the golden reference and stays as-is. Do not touch the
> wgpu4k migration track. Do not change any `commonMain` public API or the
> `Chess3DBoardRenderer`/`Board3DScene`/`Board3DSurface` contract.

---

## 0. Goal (what "done" looks like)

Close two visual-parity gaps vs the Android Filament reference (the golden), per eyeballed review:

- **Desktop (Vulkan)** — bigger gap, in **materials + lighting**: pieces are matte (weak specular/
  gloss → wood-grain banding dominates, no polished "wet" look); lighting/post is flat, cool, neutral
  with **no bloom** (loses Android's atmospheric depth); AO/contact shadows weak (pieces feel floaty);
  board reflections muted. **AA is already good (8× MSAA + SSAA) — do not change AA on desktop.**
- **Web + iOS (three.js, one shared renderer)** — materials are close, but the whole frame is **soft**
  (MSAA-only AA, no supersample/post-AA — the main regression), and bloom/specular punch is slightly
  subdued. Gloss/reflections are already close — preserve them.

Scope decision (already made): **Desktop = shader tuning (Part A) + a real HDR bloom post pipeline
(Part B). Web/iOS = AA + bloom via EffectComposer (Part C).**

---

## 1. How to work (execution rules for the agent)

1. **Edit only the source-of-truth files. Never hand-edit generated copies.**
   - Web/iOS renderer: edit `tools/chess3d-renderer/chess3d-renderer.js`, then run
     `node tools/chess3d-renderer/build.mjs` to regenerate **both** copies (the iOS bundle and the
     `CHESS3D_RENDERER_JS` Kotlin string). Hand-editing `iosApp/iosApp/Resources/chess3d-bundle.js` or
     the string in `ThreeJsChessRenderer.kt` is forbidden.
   - Desktop renderer: all shaders + pipeline code live in
     `app/src/desktopMain/kotlin/com/example/myapplication/board3d/VulkanChessRenderer.kt`.
2. **Land in the commit order at the bottom (Part A, then B, then C). Each part builds + eyeballs
   green before the next.** Bloom (B) is the highest-risk change — do not start it until A is verified.
3. **Eyeball loops (this is how you verify rendering — there is no pixel-diff CI):**
   - Desktop: `./gradlew :app:desktopTest --tests "*DesktopRendererSmokeTest*" -Dchess3d.smoke=true`
     renders start / selected / after-e4 and writes `app/build/chess3d-*.png` (downscaled = what the
     user sees) + `app/build/raw-chess3d-*.png` (full supersampled). Needs MoltenVK (present on macOS).
     **Capture a baseline before Part A** (`cp app/build/chess3d-start.png app/build/baseline-start.png`)
     and visually compare after each step.
   - Web: `./gradlew :app:wasmJsBrowserDevelopmentRun`, inspect in a real browser.
   - iOS (verifies the *same* shared JS): `tools/ios_3d_screenshot.sh` → `build/ios-3d-screenshot.png`.
4. **Full-build gate (a change is not done until all targets build):**
   ```bash
   ./gradlew :androidApp:assembleDebug :app:assembleAndroidDeviceTest :app:check \
     :app:desktopJar :app:packageDistributionForCurrentOS :app:wasmJsBrowserDistribution
   ```
   Do not regress existing 3D tests: `./gradlew :app:desktopTest --tests "*board3d*"`.
5. **Expose every magic number as a named constant/tunable next to the existing look knobs**
   (`VulkanChessRenderer.kt:73`–`76`; module consts near `PIECE_SCALE` in the JS), with a one-line
   comment, so they can be tuned without spelunking.

---

## 2. Current architecture (verified facts the plan relies on)

### 2.1 Desktop Vulkan (`VulkanChessRenderer.kt`)
- **Headless, single render thread** (`renderDispatcher`), one reusable primary command buffer
  (`commandBuffer`), one `fence`. Per frame: `renderFrame()` (`:215`) → `recordCommandBuffer()`
  (`:230`) → submit + wait → `readbackToImageBitmap()` (`:349`) → `surf.onFrame(...)`.
- **All shaders are GLSL string consts compiled at runtime via shaderc** (`createShaderModule()`
  `:1040`). A fullscreen-triangle vertex shader already exists: `FSQ_VERT` (`:1857`, emits UV via
  `gl_VertexIndex`, used by `BRDF_LUT_FRAG`). **Reuse `FSQ_VERT` for all new post passes.**
- **Frame record order** (`recordCommandBuffer` `:230`): (1) shadow depth pass into `shadowFramebuffer`
  (`:238`), (2) main pass into `framebuffer` (`:263`): bind `skyPipeline`, draw fullscreen sky tri
  (`:282`), then bind `pipeline` and draw each `ChessTexture` group (`:284`–`309`), (3)
  `vkCmdCopyImageToBuffer(resolveImage → readbackBuffer)` (`:317`).
- **Main render pass** `createRenderPass()` (`:884`): 3 attachments — [0] MSAA color (`colorImage`,
  `samples`, format `colorFormat`=`VK_FORMAT_R8G8B8A8_UNORM`, finalLayout COLOR_ATTACHMENT_OPTIMAL,
  storeOp DONT_CARE), [1] MSAA depth, [2] **single-sample resolve** (`resolveImage`, `colorFormat`,
  storeOp STORE, **finalLayout `VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL`**). Subpass resolves [0]→[2].
  **No explicit `VkSubpassDependency` today.** The copy reads `resolveImage` as TRANSFER_SRC.
- **Targets are (re)allocated in `ensureTargets(w,h)` (`:1247`)** and freed in `destroyTargets()`
  (`:1396`). `width/height` store the **SSAA-scaled** dims (`ssaaScale` `:1243`, default 2, env
  `CHESS_DESKTOP_SSAA`). Framebuffer attaches `colorView, depthView, resolveView` (`:1263`).
  `readbackBuffer` sized `width*height*4`.
- **Scene fragment shader `FRAG_GLSL` (`:1518`)** outputs tonemapped LDR. Key lines:
  - sun color `vec3(3.8,3.4,2.8)` (`:1608`).
  - IBL diffuse: `irradiance *= vec3(1.25,1.08,0.85)` (`:1659`); `diffuse = irradiance*albedo*0.6` (`:1660`).
  - **IBL specular: `vec3 specular = reflection*(F*brdf.x+brdf.y)*0.5;` (`:1667`) ← deliberately
    halved; main cause of matte pieces + muted reflections.**
  - `metallic = mat.metallicFactor*mr.b` (`:1637`); `roughness = clamp(mat.roughnessFactor*mr.g, 0.05, 1.0)` (`:1638`).
  - `ambient = kD*diffuse + specular` (`:1671`); `color = ambient + Lo` (`:1673`).
  - Uncharted2 tonemap + gamma at `:1680`–`1682`. `SKY_FRAG` tonemaps identically (`:1726`–`1730`).
  - **No AO is sampled today.** `mrTex` is bound at binding 8; per glTF, its **R channel = occlusion**.
- **Per-draw push constants** (`MaterialParams`, 32 bytes, `:1535` / written `:292`–`300`):
  `vec4 baseColorFactor; float metallicFactor; float roughnessFactor; vec2 pad;` — **the `pad` at
  byte offset 24 is free real estate** for a per-material roughness scale (Part A.2).
- **Material groups**: `ChessTexture.entries` (board/marble, WHITE wood, BLACK wood). The loop at
  `:285` knows `tex`, so per-group constants (e.g. piece-only roughness scale) are trivial to push.
  `GltfChessTextures.kt:87` notes piece MR has **B pinned to 255** (so metallic is forced 0 for
  pieces) — meaning for pieces only `mr.g` (roughness) and `mr.r` (AO) are meaningful from that tex.
- **Helpers you will reuse verbatim** (do not reinvent): `createImage(w,h,format,usage,samples=1)`
  (`:1301`), `createImageView(image,format,aspect,...)` (`:1314`), `createBuffer` (`:1378`),
  `transition(cmd,stack,image,old,new,srcAccess,dstAccess,srcStage,dstStage)` (`:1214`),
  `createShaderModule` (`:1040`), the descriptor-write pattern in `uploadTexture` (`:1062`).
- **Format support**: `getMaxUsableSampleCount()` (`:874`) picks `samples` from
  `framebufferColorSampleCounts & framebufferDepthSampleCounts` — format-agnostic. `envFormat =
  VK_FORMAT_R16G16B16A16_SFLOAT` (`:80`) is already created+sampled as a cube, so RGBA16F sampling is
  proven on the device; you additionally need it as a **COLOR_ATTACHMENT** (and MSAA) — see B.0 check.

### 2.2 Web + iOS three.js (`tools/chess3d-renderer/chess3d-renderer.js`, 231 lines)
- `WebGLRenderer({antialias:true, alpha:false})`, `setPixelRatio(devicePixelRatio||1)` (`:75`),
  `ACESFilmicToneMapping`, exposure 1.0, `outputColorSpace=SRGBColorSpace`, `shadowMap.enabled=false`.
- Lighting = papermill HDR skybox + PMREM IBL (`loadEnvironment` `:47`); pieces use the glb's own
  `white`/`black` materials; whole glb shown at scale 0.5.
- **Render loop is a bare `renderer.render(scene,camera)`** in `animate()` (`:228`). Resize =
  `renderer.setSize(w,h,false)` (`:157`). MSAA-only AA = the softness.
- Web resolves `three` + `three/addons/` via the **static import map** in
  `app/src/wasmJsMain/resources/index.html:11`, pinned to **`three@0.169.0`** (jsDelivr). iOS bundles
  via esbuild (`bundle:true`, `format:'iife'`) from `tools/chess3d-renderer/node_modules` — so any new
  addon import is bundled offline automatically. **Pin all new imports to the same `three@0.169.0`.**

---

## Part A — Desktop Vulkan: material/lighting shader tuning (low risk)

Land these **first**, one at a time, re-running the smoke test after each. They are confined to
`FRAG_GLSL`, three new tunables, and one extra push-constant byte slot — **no render-pass/pipeline/
attachment changes**, so each is independently revertible.

### A.1 New tunables
Add next to `exposure`/`gamma` (`VulkanChessRenderer.kt:75`):
```kotlin
// Part A look tunables (Pass-2 parity). Eyeball via DesktopRendererSmokeTest.
private val iblSpecularScale = 0.85f   // was hard-coded 0.5 in FRAG_GLSL; restores gloss + board reflections
private val aoStrength = 1.0f          // how strongly mrTex.r (glTF occlusion) darkens the IBL ambient term
private val contactStrength = 0.35f    // how much the shadow factor deepens ambient at piece/board contact
```
Raise `exposure` `4.0f → 4.3f` (warmer, less flat). These four floats feed the shader via a small
UBOParams extension **or** simpler: bake `iblSpecularScale`/`aoStrength`/`contactStrength` directly as
GLSL literals (they are constants, not animated). Prefer literals to avoid touching the 32-byte
UBOParams layout; keep the Kotlin vals as the documented source of the chosen numbers.

### A.2 Per-material roughness scale (pieces glossier, board unchanged)
- In `recordCommandBuffer` material loop (`:292`), set the free pad slot to a per-group roughness
  scale:
  ```kotlin
  val roughScale = if (tex == ChessTexture.WHITE || tex == ChessTexture.BLACK) 0.8f else 1.0f
  matPush.putFloat(24, roughScale)   // was pad; offset 24
  matPush.putFloat(28, 0f)
  ```
- In `FRAG_GLSL` rename the push-constant tail and use it:
  ```glsl
  layout(push_constant) uniform MaterialParams {
      vec4 baseColorFactor;
      float metallicFactor;
      float roughnessFactor;
      float roughnessScale;   // offset 24 (was pad.x)
      float pad;
  } mat;
  ...
  float roughness = clamp(mat.roughnessFactor * mr.g * mat.roughnessScale, 0.04, 1.0); // was floor 0.05
  ```

### A.3 IBL specular + AO + contact grounding in `FRAG_GLSL`
Replace the IBL/ambient block (`:1658`–`1673`) with (note the three new factors and the AO sample):
```glsl
    vec3 irradiance = texture(irradiance, N).rgb;
    irradiance *= vec3(1.25, 1.08, 0.85);                 // existing warm tint
    vec3 diffuse = irradiance * albedo * 0.6;

    vec3 reflection = prefilteredReflection(R, roughness);
    vec2 brdf = texture(brdfLUT, vec2(max(dot(N, V), 0.0), roughness)).rg;
    vec3 F = F_SchlickR(max(dot(N, V), 0.0), F0, roughness);
    vec3 specular = reflection * (F * brdf.x + brdf.y) * 0.85;   // A.1: was 0.5 -> gloss + reflections

    vec3 kD = 1.0 - F;
    kD *= 1.0 - metallic;
    vec3 ambient = kD * diffuse + specular;

    // A.3: glTF occlusion (mrTex.r) darkens only the indirect/ambient term. Piece MR.r is ~flat so
    // this mostly grounds the marble board/frame; it is a no-op where R==1, so it is safe globally.
    float ao = mr.r;
    ambient *= mix(1.0, ao, 1.0);                          // aoStrength = 1.0
    // A.3: deepen contact shadow on the ambient fill so pieces sit on the board instead of floating.
    ambient *= mix(1.0 - 0.35, 1.0, sh);                  // contactStrength = 0.35

    vec3 color = ambient + Lo;
```
(`mr` is already fetched at `:1636`; `sh` at `:1647`. No new bindings.)

### A.4 Tone
Keep `gamma=2.2`. With `exposure=4.3` and the gloss/AO above, re-check the smoke PNGs; if whites clip,
trim `exposure` toward `4.1`. Do **not** change the tonemap operator here — bloom (Part B) supplies
the remaining "atmosphere".

**Part A acceptance:** `chess3d-start.png` vs `baseline-start.png` shows glossier pieces, visible dark-
square board reflections, grounded contact, slightly warmer frame; no blown highlights, board not
mirror-slick. The smoke test still passes its "many colours / board fills frame" asserts. **Commit A.**

### A.5 De-band the piece materials (VERIFIED — added after Part A made it worse)

**Root cause (confirmed by extracting the glb textures, `app/build/glb-tex/`):** the chess set's
`whites`/`blacks` **albedo** maps bake very high-contrast horizontal **wood-grain banding**, and the
shared `metallicRoughness` map's **green (roughness) channel is also striped** (blue/metallic pinned
at 255). On the lathe-turned geometry both wrap into hard concentric rings. Part A's specular boost
(0.5→0.85) *amplified* the striped-roughness rings (alternating glossy/matte bands, blue sky-reflection
catching the glossy ones) — so the pieces looked **more** unnatural, not less. This is an **asset
problem**; no lighting/bloom tuning fixes it. The renderer must actively tame the grain.

**Fix (per-material, pieces only; board untouched):**
- **Soften albedo grain:** pull albedo toward the per-material mean wood colour —
  `albedoLin = mix(grainMeanLin, albedoLin, grainStrength)`. Means measured from the glb albedos:
  white `(0.427,0.361,0.263)` sRGB, black `(0.176,0.114,0.075)` sRGB. `grainStrength = 0.5` for
  pieces keeps half the grain (subtle character); `1.0` for the board (no-op).
- **Flatten roughness:** ignore the striped `mr.g` for pieces — `roughnessOverride = 0.4` gives an even
  polished sheen; `0` (sentinel) for the board keeps its textured roughness.
- Pushed via three new `MaterialParams` fields (`grainStrength`, `grainMean*`, `roughnessOverride`),
  bumping the fragment push-constant range 32→48 bytes. Implemented in `FRAG_GLSL` (`main`) +
  `recordCommandBuffer` material loop + `createPipeline` push range.

**Verified:** rebuilt + ran `DesktopRendererSmokeTest -Dchess3d.smoke=true` on this branch — the rings
are gone; pieces read as smooth ivory/ebony with subtle grain. `grainStrength`/`roughnessOverride` are
the tuning knobs (lower grainStrength → cleaner; raise → more wood character).

---

## Part B — Desktop Vulkan: HDR bloom post pipeline (higher risk, staged)

Bloom must extract bright regions **in HDR, before tonemapping**. Today the scene fragment tonemaps
to LDR and resolves straight to `resolveImage`. The change: **resolve the scene to an HDR texture,
run a fullscreen post chain (bright → blur → composite), and tonemap in the final composite pass that
writes the existing LDR `resolveImage`** (so `vkCmdCopyImageToBuffer` is untouched). Architecture
mirrors the existing offscreen passes (BRDF LUT / irradiance) and reuses `FSQ_VERT`.

```
shadow → SCENE(MSAA, linear HDR) --resolve--> sceneHdr(R16F)
                                                  │ sample
                                bright(½ R16F) ◄──┘  (threshold + soft knee)
                                   │ sample
          blurH→A(½)  blurV→B(½)  blurH→A(½)  blurV→B(½)   (separable, 2 iters)
                                                  │ bloom=B
   composite: sceneHdr + bloom*intensity → Uncharted2 tonemap+gamma → resolveImage(LDR R8) → copy
```

### B.0 Pre-flight (must do first)
- Add `private val hdrFormat = VK_FORMAT_R16G16B16A16_SFLOAT`.
- **Verify the device supports `hdrFormat` as a multisampled color attachment.** In `initVulkan`
  after `samples` is chosen, query `vkGetPhysicalDeviceImageFormatProperties(hdrFormat, OPTIMAL,
  COLOR_ATTACHMENT|SAMPLED|TRANSFER_SRC)` and clamp `samples` to its `sampleCounts` (MoltenVK
  supports 4×/8× RGBA16F; this guards weird drivers). Also gate the whole bloom path behind
  `CHESS_DESKTOP_BLOOM` (env, default on; `0` = keep the current LDR single-pass path) so it is a
  one-line kill switch and the smoke test stays bisectable.
- Add an env tunable block by the others:
  ```kotlin
  private val bloomEnabled = System.getenv("CHESS_DESKTOP_BLOOM")?.trim() != "0"
  private val bloomThreshold = 1.1f   // HDR luma above which pixels bloom
  private val bloomKnee = 0.5f        // soft-knee width below threshold
  private val bloomIntensity = 0.5f   // additive bloom strength in composite
  private val bloomIterations = 2     // H+V blur passes
  ```

### B.1 Fields (add near `colorImage`/`resolveImage`, `:86`–`88`)
```kotlin
// HDR scene resolve target (sampled by the bloom chain).
private var sceneHdrImage = VK_NULL_HANDLE; private var sceneHdrMem = VK_NULL_HANDLE; private var sceneHdrView = VK_NULL_HANDLE
// Half-res ping-pong bloom targets (all hdrFormat, SAMPLED|COLOR_ATTACHMENT).
private var bloomW = 0; private var bloomH = 0
private var bloomBright = VK_NULL_HANDLE; private var bloomBrightMem = VK_NULL_HANDLE; private var bloomBrightView = VK_NULL_HANDLE
private var bloomA = VK_NULL_HANDLE; private var bloomAMem = VK_NULL_HANDLE; private var bloomAView = VK_NULL_HANDLE
private var bloomB = VK_NULL_HANDLE; private var bloomBMem = VK_NULL_HANDLE; private var bloomBView = VK_NULL_HANDLE
// Post render passes + pipelines (created once; format-fixed).
private var postRenderPass = VK_NULL_HANDLE      // single hdr color attachment, finalLayout SHADER_READ_ONLY
private var compositeRenderPass = VK_NULL_HANDLE // single ldr color attachment, finalLayout TRANSFER_SRC
private var postSetLayout = VK_NULL_HANDLE; private var postPool = VK_NULL_HANDLE
private var brightPipelineLayout = VK_NULL_HANDLE; private var brightPipeline = VK_NULL_HANDLE
private var blurPipelineLayout = VK_NULL_HANDLE; private var blurPipeline = VK_NULL_HANDLE
private var compositePipelineLayout = VK_NULL_HANDLE; private var compositePipeline = VK_NULL_HANDLE
// Framebuffers + per-input descriptor sets (resize-dependent; rebuilt in ensureTargets).
private var sceneFramebuffer = VK_NULL_HANDLE     // color(MSAA hdr) + depth + sceneHdr(resolve)
private var brightFb = VK_NULL_HANDLE; private var aFb = VK_NULL_HANDLE; private var bFb = VK_NULL_HANDLE
private var compositeFb = VK_NULL_HANDLE
private var dsSceneHdr = VK_NULL_HANDLE; private var dsBright = VK_NULL_HANDLE
private var dsA = VK_NULL_HANDLE; private var dsB = VK_NULL_HANDLE; private var dsComposite = VK_NULL_HANDLE
```
> Rename the existing `framebuffer` usage in the scene pass to `sceneFramebuffer` (the scene pass now
> resolves into `sceneHdr`, not the LDR target). `resolveImage`/`resolveMem`/`resolveView` are
> **repurposed** as the composite (LDR) output — keep the names to minimize churn.

### B.2 Render passes
- **Scene pass** (`createRenderPass`, `:884`): change attachment[0] (MSAA color) and attachment[2]
  (resolve) `format(colorFormat)` → `format(hdrFormat)`; change attachment[2] `finalLayout`
  `TRANSFER_SRC_OPTIMAL` → `VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL`. Add **one** subpass dependency
  `srcSubpass=0, dst=EXTERNAL; srcStage=COLOR_ATTACHMENT_OUTPUT, dstStage=FRAGMENT_SHADER;
  srcAccess=COLOR_ATTACHMENT_WRITE, dstAccess=SHADER_READ; flags=BY_REGION` so the bright pass can
  sample `sceneHdr`.
- **`postRenderPass`** (new): 1 color attachment, `hdrFormat`, `VK_SAMPLE_COUNT_1_BIT`, loadOp
  DONT_CARE, storeOp STORE, initialLayout UNDEFINED, finalLayout SHADER_READ_ONLY_OPTIMAL. Add **two**
  external dependencies (in: `EXTERNAL→0`, FRAGMENT_SHADER/SHADER_READ → COLOR_ATTACHMENT_OUTPUT/
  COLOR_ATTACHMENT_WRITE; out: `0→EXTERNAL`, COLOR_ATTACHMENT_OUTPUT/WRITE → FRAGMENT_SHADER/READ,
  BY_REGION) so consecutive bright/blur passes serialize correctly. One render pass object is shared
  by bright + all blur passes (same format), with different framebuffers.
- **`compositeRenderPass`** (new): 1 color attachment, `colorFormat` (LDR), 1 sample, loadOp
  DONT_CARE, storeOp STORE, finalLayout `TRANSFER_SRC_OPTIMAL`. Dependencies: in `EXTERNAL→0`
  (FRAGMENT_SHADER/SHADER_READ → COLOR_ATTACHMENT_OUTPUT/WRITE); out `0→EXTERNAL`
  (COLOR_ATTACHMENT_OUTPUT/WRITE → **TRANSFER**/TRANSFER_READ) so the trailing
  `vkCmdCopyImageToBuffer` is correctly ordered.

### B.3 Post descriptor layout + pool + pipelines (create once in `initVulkan`)
- `postSetLayout`: 2 bindings, both `COMBINED_IMAGE_SAMPLER`, fragment stage (binding 0 = primary
  input, binding 1 = second input for composite; bright/blur bind the same view to both — harmless).
- `postPool`: `maxSets=5`, `COMBINED_IMAGE_SAMPLER` count `= 5*2`.
- Three pipelines, all using `FSQ_VERT` (no vertex buffers, `vkCmdDraw(cmd,3,1,0,0)`), no depth, no
  blend, `rasterizationSamples(VK_SAMPLE_COUNT_1_BIT)`, dynamic viewport+scissor (mirror
  `buildBrdfLut`'s pipeline setup at `:543`–`:566`):
  - `brightPipeline` (`postRenderPass`, `BRIGHT_FRAG`, push: `vec4{threshold,knee,_,_}`).
  - `blurPipeline` (`postRenderPass`, `BLUR_FRAG`, push: `vec4{texelX,texelY,dirX,dirY}`).
  - `compositePipeline` (`compositeRenderPass`, `COMPOSITE_FRAG`, push:
    `vec4{bloomIntensity,exposure,gamma,_}`).
  - Each push-constant range is `VK_SHADER_STAGE_FRAGMENT_BIT`, size 16, offset 0. Each pipeline layout
    uses `postSetLayout`.

### B.4 Target creation in `ensureTargets` (`:1247`) and teardown in `destroyTargets` (`:1396`)
After computing `rw,rh` and before building the framebuffer:
- `colorImage` MSAA: change its `createImage(... colorFormat ...)` (`:1256`) to `hdrFormat`.
- Create `sceneHdrImage` (1 sample, `hdrFormat`, `COLOR_ATTACHMENT|SAMPLED`) + view.
- Keep `resolveImage` but recreate it as `colorFormat` (LDR), usage `COLOR_ATTACHMENT|TRANSFER_SRC`
  (it is now the composite output, no longer an MSAA resolve target).
- `bloomW=max(1,rw/2); bloomH=max(1,rh/2)`. Create `bloomBright/bloomA/bloomB` (1 sample, `hdrFormat`,
  `COLOR_ATTACHMENT|SAMPLED`) + views.
- Framebuffers: `sceneFramebuffer`(colorView, depthView, **sceneHdrView**) over `renderPass`;
  `brightFb`(bloomBrightView)/`aFb`(bloomAView)/`bFb`(bloomBView) over `postRenderPass` at bloom size;
  `compositeFb`(resolveView) over `compositeRenderPass` at full size.
- (Re)allocate the 5 descriptor sets from `postPool` (free/reset on resize) and write them with the
  `brdfLutSampler` (linear, clamp-to-edge, no mip — perfect for these 1-mip targets):
  - `dsSceneHdr` → binding0,1 = `sceneHdrView`
  - `dsBright` → `bloomBrightView`; `dsA` → `bloomAView`; `dsB` → `bloomBView`
  - `dsComposite` → binding0 = `sceneHdrView`, binding1 = `bloomBView`
- `destroyTargets`: destroy all the new images/views/mem + the 5 framebuffers; free the post DSs
  (or `vkResetDescriptorPool(postPool)`). Mirror the existing destroy ordering.

### B.5 Record order in `recordCommandBuffer` (replace the main pass + copy, `:263`–`317`)
Keep the shadow pass unchanged. Then:
1. Scene pass into `sceneFramebuffer` (everything currently at `:263`–`311`, unchanged draws). The
   scene shaders now output **linear HDR** (see B.6).
2. If `bloomEnabled`:
   - **bright**: begin `postRenderPass`/`brightFb` at (bloomW,bloomH); bind `brightPipeline` + `dsSceneHdr`;
     push `{bloomThreshold,bloomKnee,0,0}`; `vkCmdDraw(3,1,0,0)`; end.
   - **blur loop** (`bloomIterations` × H then V), texel = `(1f/bloomW, 1f/bloomH)`:
     - H: `postRenderPass`/`aFb`, `blurPipeline` + (iter 0 ? `dsBright` : `dsB`), push `{texelX,texelY,1,0}`.
     - V: `postRenderPass`/`bFb`, `blurPipeline` + `dsA`, push `{texelX,texelY,0,1}`.
   - **composite**: `compositeRenderPass`/`compositeFb` at (width,height); `compositePipeline` +
     `dsComposite`; push `{bloomIntensity,exposure,gamma,0}`; draw.
   - Else (bloom off): a trivial composite that samples only `sceneHdr` and tonemaps (so the LDR path
     still works) — or keep the old single-pass path behind the `bloomEnabled` branch.
3. `vkCmdCopyImageToBuffer(resolveImage, TRANSFER_SRC_OPTIMAL, readbackBuffer, region)` — **unchanged**
   (`resolveImage` is now the composite output). The render-pass out-dependency (B.2) orders it.

> Render-pass `finalLayout`s do the image transitions; the subpass dependencies (B.2) do the execution/
> memory ordering. **Do not add manual `vkCmdPipelineBarrier`s between passes** unless validation
> complains — if it does, add a barrier matching the dependency you missed.

### B.6 Move tonemapping out of the scene shaders into composite
- In `FRAG_GLSL` (`:1680`–`1682`) and `SKY_FRAG` (`:1726`–`1730`), **delete** the
  `Uncharted2Tonemap(...) * (1/Uncharted2Tonemap(11.2))` and `pow(color, 1/gamma)` lines so both
  output **linear HDR radiance**. Keep `clamp(color*exposure, 0, 256)`? No — move the `* exposure`
  into composite too; scene/sky now write raw linear radiance. The selection glow (`:1676`) stays
  additive in HDR (it will bloom — desirable).

### B.7 New shaders (append by the other GLSL consts, ~`:1855`)
```glsl
// BRIGHT_FRAG — threshold with soft knee (Karis/UE style).
#version 450
layout(location = 0) in vec2 inUV;
layout(set = 0, binding = 0) uniform sampler2D src;
layout(push_constant) uniform PC { vec4 p; } pc; // p.x=threshold, p.y=knee
layout(location = 0) out vec4 outColor;
void main() {
    vec3 c = texture(src, inUV).rgb;
    float br = max(c.r, max(c.g, c.b));
    float knee = max(pc.p.y, 1e-4);
    float soft = clamp((br - pc.p.x + knee) / (2.0 * knee), 0.0, 1.0);
    float w = max(soft * soft, step(pc.p.x, br)); // soft knee below, hard above
    outColor = vec4(c * w, 1.0);
}
```
```glsl
// BLUR_FRAG — separable 9-tap Gaussian; direction in push constant.
#version 450
layout(location = 0) in vec2 inUV;
layout(set = 0, binding = 0) uniform sampler2D src;
layout(push_constant) uniform PC { vec4 p; } pc; // p.xy=texel, p.zw=dir (1,0) or (0,1)
layout(location = 0) out vec4 outColor;
const float W[5] = float[](0.227027, 0.1945946, 0.1216216, 0.054054, 0.016216);
void main() {
    vec2 step = pc.p.xy * pc.p.zw;
    vec3 c = texture(src, inUV).rgb * W[0];
    for (int i = 1; i < 5; i++) {
        c += texture(src, inUV + step * float(i)).rgb * W[i];
        c += texture(src, inUV - step * float(i)).rgb * W[i];
    }
    outColor = vec4(c, 1.0);
}
```
```glsl
// COMPOSITE_FRAG — scene + bloom, then Uncharted2 tonemap + gamma (the only tonemap now).
#version 450
layout(location = 0) in vec2 inUV;
layout(set = 0, binding = 0) uniform sampler2D sceneHdr;
layout(set = 0, binding = 1) uniform sampler2D bloomTex;
layout(push_constant) uniform PC { vec4 p; } pc; // p.x=intensity, p.y=exposure, p.z=gamma
layout(location = 0) out vec4 outColor;
vec3 Uncharted2Tonemap(vec3 x) {
    float A=0.15,B=0.50,C=0.10,D=0.20,E=0.02,F=0.30;
    return ((x*(A*x+C*B)+D*E)/(x*(A*x+B)+D*F))-E/F;
}
void main() {
    vec3 hdr = texture(sceneHdr, inUV).rgb + texture(bloomTex, inUV).rgb * pc.p.x;
    vec3 col = Uncharted2Tonemap(clamp(hdr * pc.p.y, 0.0, 256.0));
    col *= 1.0 / Uncharted2Tonemap(vec3(11.2));
    col = pow(col, vec3(1.0 / pc.p.z));
    outColor = vec4(col, 1.0);
}
```

### B.8 Staged rollout (do NOT write it all at once)
1. **B-step 1 (HDR round-trip, no bloom):** B.0/B.1/B.2 scene-pass format change + a *composite-only*
   pass (`dsComposite` binding1 also = sceneHdr, intensity 0) + B.6 tonemap move. Skip bright/blur.
   Run smoke test — image must look ~identical to end-of-Part-A. This isolates "did HDR resolve +
   composite break the picture" from blur bugs.
2. **B-step 2 (bloom on):** add bright + blur passes and point composite binding1 at `bloomBView`.
   Tune `bloomThreshold`/`bloomIntensity` against Android (subtle halo on bright highlights, no
   ringing/banding).
3. Run `VulkanFpsBenchmark` (`-Dchess3d.bench`); half-res bloom should be cheap even at SSAA×2. If
   slow, drop bloom to quarter-res (`/4`) or reduce `bloomIterations`.

### B.9 Risks / rollback
- Highest-risk part: subpass dependencies + descriptor-set rewrite-on-resize. Enable Vulkan
  validation layers while developing if available; the layout/ordering mistakes surface there.
- `CHESS_DESKTOP_BLOOM=0` reverts to the LDR path at runtime; reverting the Part B commit reverts
  entirely. Part A stands alone.

**Part B acceptance:** bright pieces/highlights bleed a soft halo, scene gains Android-like
atmospheric depth, no banding/ringing, FPS within budget, smoke-test asserts still pass, and
`CHESS_DESKTOP_BLOOM=0` reproduces the Part-A look.

---

## Part C — Web + iOS three.js: AA + bloom (EffectComposer)

All edits in `tools/chess3d-renderer/chess3d-renderer.js`; then `node tools/chess3d-renderer/build.mjs`.
Replace the bare `renderer.render()` with an `EffectComposer` chain that supplies **both** crisper AA
(MSAA HDR target + supersample + SMAA) and bloom.

### C.1 Imports (top of file, pinned to three@0.169.0 via the import map / esbuild)
```js
import { EffectComposer }  from 'three/addons/postprocessing/EffectComposer.js'
import { RenderPass }      from 'three/addons/postprocessing/RenderPass.js'
import { UnrealBloomPass } from 'three/addons/postprocessing/UnrealBloomPass.js'
import { SMAAPass }        from 'three/addons/postprocessing/SMAAPass.js'
import { OutputPass }      from 'three/addons/postprocessing/OutputPass.js'
```

### C.2 Tunables (module consts near `PIECE_SCALE`, `:23`)
```js
const SS = 1.5            // render-buffer supersample factor (sharpness); costs fill rate
const SS_CAP = 2.5        // hard cap on effective pixel ratio
const BLOOM_STRENGTH = 0.25
const BLOOM_RADIUS = 0.4
const BLOOM_THRESHOLD = 0.85
let composer, bloomPass, smaaPass   // module-scope, alongside `renderer, scene, camera`
```

### C.3 Build the composer in `init` (after renderer/scene/camera, replacing nothing else)
```js
renderer.setPixelRatio(Math.min((window.devicePixelRatio || 1) * SS, SS_CAP))
// Keep renderer.toneMapping = ACESFilmicToneMapping and outputColorSpace = SRGB as-is; OutputPass
// applies them. RenderPass renders the scene LINEAR into a HalfFloat MSAA target (sharp AA), then
// bloom works in HDR, SMAA cleans remaining edges, OutputPass tonemaps + converts to sRGB last.
const size = renderer.getDrawingBufferSize(new THREE.Vector2())
const rt = new THREE.WebGLRenderTarget(size.x, size.y, {
  type: THREE.HalfFloatType, samples: 4,                 // MSAA-resolved input = crisp
})
composer = new EffectComposer(renderer, rt)
composer.addPass(new RenderPass(scene, camera))
bloomPass = new UnrealBloomPass(new THREE.Vector2(size.x, size.y), BLOOM_STRENGTH, BLOOM_RADIUS, BLOOM_THRESHOLD)
composer.addPass(bloomPass)
smaaPass = new SMAAPass(size.x, size.y)
composer.addPass(smaaPass)
composer.addPass(new OutputPass())
```
> Wrap composer construction in the existing `try/catch` of `init` (`:98`) so an addon/load failure
> falls back to the bare `renderer.render` loop rather than a black canvas. Set a module flag
> `usePost = !!composer` to branch in `animate`.

### C.4 Render loop + resize + dispose
- `animate()` (`:228`): `if (usePost && composer) composer.render(); else renderer.render(scene, camera)`.
- `resize(w, h)` (`:157`): after `renderer.setSize(w,h,false)` and the camera update, add:
  ```js
  renderer.setPixelRatio(Math.min((window.devicePixelRatio || 1) * SS, SS_CAP))
  const s = renderer.getDrawingBufferSize(new THREE.Vector2())
  if (composer) composer.setSize(s.x, s.y)
  if (bloomPass) bloomPass.setSize(s.x, s.y)
  if (smaaPass) smaaPass.setSize(s.x, s.y)
  ```
- `dispose()` (`:164`): `if (composer) { composer.dispose(); composer = null }` before disposing the
  renderer; null `bloomPass`/`smaaPass`.

### C.5 Regenerate + verify
```bash
node tools/chess3d-renderer/build.mjs            # rewrites ios bundle + ThreeJsChessRenderer.kt string
git diff --stat                                  # MUST show both generated copies changed
tools/ios_3d_screenshot.sh                       # eyeball build/ios-3d-screenshot.png (shared renderer)
./gradlew :app:wasmJsBrowserDevelopmentRun       # eyeball web in a real browser
```
- Confirm the **iOS bundle runs fully offline** — esbuild must have inlined EffectComposer + bloom +
  SMAA (SMAA ships its search/area textures as embedded base64, so no runtime fetch). Bundle grows
  ~80–120 KB; that is expected.
- Web only: the new addon imports resolve through the existing `three/addons/` import-map entry
  (`index.html:15`) — no `index.html` change needed, but verify no 404s in the browser console.

**Part C acceptance:** web + iOS frame is crisp (sharp silhouettes/sculpt detail, no upscaled
softness), bloom adds subtle Android-matching punch, gloss/reflections preserved, iOS runs offline.

### C.6 De-band the piece materials on web/iOS (VERIFIED)

Same root cause + fix as desktop A.5, ported into the shared `chess3d-renderer.js`: `debandPieceMaterial()`
flattens the striped roughness (`roughnessMap=null`, `roughness=0.4`, `metalness=0`) and softens the
albedo grain toward the per-material mean via an `onBeforeCompile` patch
(`diffuseColor.rgb = mix(uGrainMean, diffuseColor.rgb, uGrainStrength)` after `<map_fragment>`). Applied
to the glb `white`/`black` materials in `loadGlb`. Regenerated into both copies via `build.mjs`.

### C.7 iOS blank-board fix — custom URL-scheme handler (VERIFIED; discovered during verification)

**Symptom:** on iOS the 3D board rendered **blank navy** (`0x1a2a3a` = `loadEnvironment()`'s catch
fallback). Instrumenting `chess3d-host.html` to surface the JS console showed:
`papermill HDR load failed … Load failed` and `chess.glb not found`. **Root cause:** WKWebView blocks
`XMLHttpRequest`/`fetch` of `file://` resources even when the page is loaded via
`loadFileURL(allowingReadAccessToURL:)`, so three.js's GLTFLoader/RGBELoader can't fetch the glb/HDRs.
Pre-existing, unrelated to the de-band (reproduced on the committed bundle) and to the EffectComposer
(reproduced with post disabled).

**Fix (iOS platform glue):** the KVC `allowFileAccessFromFileURLs` pref is **not exposed in
Kotlin/Native** (`setValue(_:forKey:)` isn't bound on `NSObject`), so instead register a
`WKURLSchemeHandler` (`BundleAssetSchemeHandler` in `IosBoard3D.kt`) on the config for a custom
`chessasset://` scheme and load the host via that scheme (`WKWebViewChessRenderer.attach`). Custom
schemes are not subject to the `file://` XHR restriction, so the loaders work. Note the two
`webView(…)` protocol overrides need `@kotlinx.cinterop.ObjCSignatureOverride` (same Kotlin signature),
and bundle bytes are read with `NSFileManager.contentsAtPath` (`NSData.dataWithContentsOfFile` isn't
bound). **Verified:** `tools/ios_3d_screenshot.sh` now renders the full board with de-banded pieces.

> Follow-up (out of scope here): the Part C web/iOS bloom (`BLOOM_STRENGTH=0.7`, `THRESHOLD=0.6`) reads
> as a strong central blow-out on the iOS capture — likely wants toning down to match Android.

---

## 3. Verification matrix (must all pass before "done")

| Check | Command |
|---|---|
| All targets build | `./gradlew :androidApp:assembleDebug :app:assembleAndroidDeviceTest :app:check :app:desktopJar :app:packageDistributionForCurrentOS :app:wasmJsBrowserDistribution` |
| Desktop eyeball + asserts | `./gradlew :app:desktopTest --tests "*DesktopRendererSmokeTest*" -Dchess3d.smoke=true` → inspect `app/build/chess3d-*.png` |
| Desktop 3D unit tests intact | `./gradlew :app:desktopTest --tests "*board3d*"` |
| Desktop perf | `./gradlew :app:desktopTest --tests "*VulkanFpsBenchmark*" -Dchess3d.bench=true` |
| Web | `./gradlew :app:wasmJsBrowserDevelopmentRun` (browser) |
| iOS/web shared renderer eyeball | `tools/ios_3d_screenshot.sh` → `build/ios-3d-screenshot.png` |
| Apple targets | iOS/macOS build + simulator tests (CI `apple` job) |

## 4. Guardrails (do not violate)
- No Android changes; no `commonMain` API changes; no changes to the
  `Chess3DBoardRenderer`/`Board3DScene` abstraction; no wgpu4k changes.
- Desktop AA stays as-is (8× MSAA + SSAA) — only web needed AA work.
- Generated three.js copies are produced **only** by `build.mjs`.

## 5. Commit sequence
1. `feat(3d/desktop): glossier pieces + AO/contact grounding + warmer tone (shader tuning)` — Part A.
2. `feat(3d/desktop): HDR bloom post pipeline` — Part B (B-step 1 then B-step 2 may be two commits).
3. `feat(3d/web): EffectComposer AA + bloom for web/iOS parity` — Part C (JS source + both regenerated
   copies in one commit).
