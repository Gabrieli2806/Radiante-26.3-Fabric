# DLSS Frame Generation (DLSS-G / Multi Frame Generation)

Plan for adding NVIDIA DLSS Frame Generation (2x) and Multi Frame Generation (3x–6x) to Radiante in a way
that stays optional, isolated and easy to update.

## Why `nvngx_dlssg.dll` alone is not enough

Ray Reconstruction and Super Resolution run inside our own frame: we hand NGX images and get an image back.
Frame Generation instead sits **between rendering and presentation**. It paces and presents extra frames on
its own, so it has to own the swapchain present path. NVIDIA only exposes it through **Streamline**
(`sl.interposer.dll` plus the `sl.dlss_g` and `sl.reflex` plugins), which loads `nvngx_dlssg.dll` itself.

On Minecraft 26.3 the swapchain belongs to the render pearl backend (`VulkanGpuSurface`), not to us, so the
integration has to hook Minecraft's instance, device, swapchain and present calls.

## Requirements

| What | Detail |
| --- | --- |
| GPU | RTX 40 for 2x; RTX 50 for 3x/4x; 5x/6x need the DLSS 4.5 dynamic MFG runtime |
| SDK | NVIDIA Streamline (Vulkan support, recent version with MFG) in `native/extern/streamline` |
| Runtime files | `sl.interposer.dll`, `sl.common.dll`, `sl.dlss_g.dll`, `sl.reflex.dll`, `nvngx_dlssg.dll` in `run/radiante/dlss` |
| Reflex | Mandatory with frame generation (latency markers every frame) |

## Design

Everything lives behind a compile flag (`MCVR_ENABLE_STREAMLINE`) and a runtime check, so builds without the
SDK or GPUs without support keep working unchanged.

### 1. Vulkan creation (already shared, extend it)
- `slInit` before Minecraft creates its instance (hook next to `VulkanInstanceMixin`).
- `Instance::createMerged` / `Device::createMerged` already merge extensions and features. Add the ones
  Streamline reports through `slGetFeatureRequirements(kFeatureDLSS_G)`: instance/device extensions and the
  extra compute/graphics queues frame generation needs.
- Tell Streamline the device through `slSetVulkanInfo` (device, physical device, instance, queue indices).

### 2. Present path
- Wrap `VulkanGpuSurface` swapchain creation and `present` (a mixin like `VulkanQueueLockMixins$SurfaceMixin`)
  so the swapchain is created and presented through Streamline's hooked `vkCreateSwapchainKHR` /
  `vkQueuePresentKHR`. The native queue lock keeps working because presents still go through our hook.

### 3. Per frame data (`native/src/core/render/framegen/`)
A new `FrameGenerationModule`, independent of the world pipeline modules, fed at the end of `renderFrame`:
- `slSetConstants`: view/projection matrices (the same ones already in `WorldUBO`), jitter, camera position,
  reset flag on teleports and world changes.
- `slSetTag`:
  - **Depth** and **motion vectors**: the ray tracer's `first_hit_depth` and `motion_vector` outputs
    (upscaled versions when DLSS RR/FSR/XeSS is active).
  - **HUD-less color**: our tone-mapped world image, i.e. the image `fuseInto` blits into the main render
    target, *before* Minecraft draws the GUI. This keeps the generated frames free of HUD artifacts.
  - **UI color + alpha** (optional): later, if we route the GUI into its own target.
- `slDLSSGSetOptions`: mode, number of generated frames (1–5 for 2x–6x), flags.
- Reflex markers from Java: simulation start/end around the client tick, render submit start/end around
  `GameRenderer.render`, present start/end around `VulkanGpuSurface.present`.

### 4. Settings
- F6 option **Frame Generation: Off / 2x / 3x / 4x / 5x / 6x**, only listing what `slDLSSGGetState`
  reports as supported. Applied on **Done** like the other settings.
- Changing it does not rebuild the ray tracing pipeline, only calls `slDLSSGSetOptions`.

## Work items

1. Add Streamline SDK to `native/extern`, CMake option `MCVR_ENABLE_STREAMLINE`, copy runtime DLLs.
2. `slInit`/`slShutdown` and requirement merging in `createMerged`.
3. Swapchain/present hooks in the Minecraft backend.
4. `FrameGenerationModule`: constants, tags, options, Reflex markers.
5. Java: availability query, F6 option, Reflex marker calls.
6. Testing: menus, window resize (swapchain recreation), fullscreen toggle, world rejoin, DLSS RR + FG,
   NRD/FSR + FG, and performance overlay.

## Risks

- **Swapchain ownership**: Minecraft recreates its swapchain on resize and fullscreen toggles; every
  recreation must go through Streamline or generated frames break.
- **HUD-less image**: until the GUI is split out, menus opened over the world may smear in generated frames;
  frame generation should be paused while a screen is open.
- **Internal Minecraft classes**: the surface and present hooks rely on render pearl internals, so they are
  the part most likely to need changes on Minecraft updates.
