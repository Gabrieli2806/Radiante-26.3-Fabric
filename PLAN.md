# Radiante 26.3 — Port plan

Fork of [Radiance](https://github.com/Minecraft-Radiance/Radiance) (Java, Fabric 1.21.4) + [MCVR](https://github.com/Minecraft-Radiance/MCVR) (C++ Vulkan, RT/DLSS/FSR/XeSS/NRD) to Minecraft **26.3** using the game's native Vulkan backend.

Reference code cloned into `upstream/Radiance` and `upstream/MCVR` (with submodules).

## 1. Starting point (upstream, v0.1.5, April 2026)

| Part | Size | What it does |
|---|---|---|
| Radiance (Java) | ~18k lines, 86 mixins, Yarn 1.21.4 | Replaces OpenGL: intercepts GlStateManager/ShaderProgram/BufferRenderer, extracts chunks/entities, 80 JNI methods |
| MCVR core (C++) | ~36k lines | Creates **its own** VkInstance/VkDevice/swapchain on top of the game's GLFW window, emulates the UI's GL pipeline, RT-renders the world |
| MCVR shaders | ~28k lines GLSL | `vanilla-pt` and `advanced` packs (ReSTIR, volumetric clouds, Disney BSDF) |
| Modules | yaml under `resources/modules` | ray_tracing, nrd, dlss, fsr_upscaler, xess_sr, temporal_accumulation, tone_mapping, post_render |

## 2. What changed in 26.3 (verified against the 26.3 client.jar)

1. **Code is unobfuscated, Yarn is dead.** Fabric uses Mojang mappings. Plugin `net.fabricmc.fabric-loom` (no remap), Loom 1.18.x, Loader 0.19.5, Fabric API `0.160.5+26.3`, **Java 25**. Every mixin has to be rewritten against Mojang names (`WorldRenderer`→`LevelRenderer`, `MinecraftClient`→`Minecraft`, etc.).
2. **GLFW removed → SDL3** (`lwjgl-sdl 3.4.3`, `Window` uses `SDL_Event`, surface via `SDL_Vulkan_CreateSurface`). `MCVR/src/core/glfw_bind.cpp` and Radiance's `WindowMixins` are dead code.
3. **New render layer `com.mojang.renderpearl`** with `opengl` and `vulkan` backends (`api.device.GpuBackend` / `GpuDevice`). The mixins on `GlStateManager`, `GLX`, `ShaderProgram`, `BufferRenderer`, `RenderPhase*` no longer have a target.
4. **The Vulkan backend exposes its handles** (public methods):
   - `VulkanDevice.vkDevice()`, `.vma()`, `.graphicsQueue()/computeQueue()/transferQueue()`, `.instance().vkInstance()`
   - `VulkanPhysicalDevice.vkPhysicalDevice()`, `VulkanGpuTexture.vkImage()`
   - `VulkanCommandEncoder.allocateAndBeginTransientCommandBuffer()`, `waitSemaphore/signalSemaphore` (to sync with another renderer)
5. **But the vanilla device does NOT have what RT/DLSS need.** Extensions it enables (`VulkanFeatureSets`): `swapchain, dynamic_rendering, push_descriptor, synchronization2, multi_draw, vertex_attribute_divisor, calibrated_timestamps` (+ debug checkpoints). Missing: `KHR_acceleration_structure`, `KHR_ray_tracing_pipeline`, `KHR_deferred_host_operations`, `KHR_spirv_1_4`, `EXT_extended_dynamic_state2/3`, 1.2 features (`bufferDeviceAddress`, `descriptorIndexing`, `runtimeDescriptorArray`…) and the extensions NGX requires for DLSS.

## 3. Proposed architecture: shared device

```
 Minecraft 26.3 (renderpearl, Vulkan backend)
   ├─ VulkanInstance  ← mixin: + NGX/XeSS instance extensions, apiVersion ≥ 1.3
   ├─ VulkanBackend.createDevice ← mixin: + RT extensions + pNext features
   ├─ UI / text / GUI / swapchain / present → vanilla, untouched
   └─ LevelRenderer (world) → cancelled, delegated to:
         MCVR (JNI) adopts the game's VkInstance/VkPhysicalDevice/VkDevice/queue
           ├─ TLAS/BLAS from chunk sections + entities
           ├─ RT → NRD / DLSS-RR / FSR3 / XeSS → tone mapping
           └─ writes into the main render target's VkImage (VulkanGpuTexture.vkImage())
```

**Why this way (and not a direct port):**
- Removes all of MCVR's and Radiance's GL emulator: `PipelineStateProxy`, `DrawCommandProxy`, `ShaderProxy`, overlay pipeline, its own swapchain/window, ~30 GL mixins. This is the actual "simplification."
- UI, HUD mods and resource packs work unmodified because vanilla draws them.
- A single VkDevice → no cross-device copies, no SDL window conflict.

**Risks to resolve:**
- **Submits on the same queue**: `VkQueue` is not thread-safe. MCVR has to use the game's `VulkanQueue.Submission` or a shared lock (mixin on `VulkanQueue.beginSubmit`).
- **Frame ordering**: MCVR renders after frame preparation and before the UI; synchronize with `VulkanCommandEncoder`'s semaphores.
- **Two VMAs** on the same device: valid; MCVR keeps its own (requires `bufferDeviceAddress`).
- **OpenGL**: 26.3 still lets the player pick OpenGL. The mod forces the Vulkan backend (`graphicsApi` option) and shows an error if the GPU doesn't support RT.
- **Hardware**: RT-capable GPUs only (RTX 20+, RX 6000+, Arc). No raster fallback.

## 4. What's kept / rewritten / dropped

| Component | Action |
|---|---|
| GLSL shaders, ray_tracing/nrd/dlss/fsr/xess/tone_mapping/post_render modules | **Keep** almost as-is |
| `core/vulkan/instance, device, physical_device` | **Rewrite**: "adopt external handles" mode |
| `core/vulkan/window, swapchain, framebuffer, render_pass`, `glfw_bind.cpp` | **Drop** |
| JNI middleware for overlay/pipeline-state/shader/draw | **Drop** |
| `chunks`, `entities`, `textures`, `emission`, `world` (C++) | Keep, adjust data input |
| `ChunkProxy`, `EntityProxy`, `PBRVertexConsumer`, `EmissionRecorder`, `AuxiliaryTextures` (Java) | **Port** to Mojmap + 26.x's `SectionRenderDispatcher` |
| `vanilla_resource_tracker` mixins (textures/atlas/fonts) | Port; fonts may be unnecessary (vanilla UI) |
| GUI `RenderPipelineScreen`, `ShaderPackScreen`, options | Port to 26.3's screen API |
| `temporal_accumulation`, `svgf` | Evaluate dropping (covered by NRD/DLSS-RR) |
| `minecraft/models`, emission texture assets | Keep |

## 5. Phases

| Phase | Deliverable | Success criteria |
|---|---|---|
| 0 | Empty Fabric 26.3 project (`com.g2806.radiante`, Java 25, Loom 1.18) | `runClient` opens the game |
| 1 | Mixins on `VulkanInstance`/`VulkanBackend` adding RT extensions; handle logging | Vanilla game runs with the extended device |
| 2 | MCVR refactor: CMake without GLFW/swapchain, init from external handles, minimal JNI | Native loads, draws an RT triangle onto the main image |
| 3 | Chunks → BLAS/TLAS, textures/atlas, camera | Path-traced terrain visible |
| 4 | Entities, particles, hand, fluids, sky/clouds | Visual parity with Radiance 0.1.5 |
| 5 | DLSS-RR / NRD / FSR3 / XeSS + pipeline GUI | In-game upscaler selection |
| 6 | GitHub Actions CI (Windows + Linux natives), packaging | Single jar with natives |

Phases 3 and 4 are the bulk of the work.

## 6. License (blocking)

Radiance and MCVR are **GPL-3.0** (some Apache-2.0/MIT parts listed in `MCVR/LICENSE.md`). A fork is a derivative work: it **must stay GPL-3.0**, publish source, and keep the original copyright notices. It cannot be relicensed as MIT. DLSS binaries (`nvngx_dlss*.dll`) cannot be redistributed; the player downloads them, same as upstream.
