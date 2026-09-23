# Radiante

Path-traced rendering for Minecraft 26.3 (Fabric, NeoForge and Forge), built on Minecraft's own Vulkan backend.

[GitHub](https://github.com/Gabrieli2806/Radiante-26.3-Fabric) · [Modrinth](https://modrinth.com/project/radiante) · [Discord](https://discord.gg/DhBbAzugZ9)

Radiante is a fork of [Radiance](https://github.com/Minecraft-Radiance/Radiance) and its native renderer
[MCVR](https://github.com/Minecraft-Radiance/MCVR), rewritten for the render pearl (`com.mojang.renderpearl`)
Vulkan backend introduced in Minecraft 26.3. Instead of creating its own Vulkan device next to the game, it
shares the device Minecraft already created, so the ray tracer and the vanilla GUI draw into the same frame.

## Features

- Hardware ray tracing (`VK_KHR_ray_tracing_pipeline`) for terrain, with path-traced direct lighting,
  shadows and global illumination.
- Physically based sky, sun and atmospheric scattering.
- One built-in shader pack, `vanilla-pt`, with direct sampling of block lights (torches, lava, lamps).
- Upscaling through DLSS, FSR 3 and XeSS, plus NRD denoising.
- Runs on the device Minecraft creates: no second Vulkan instance, no duplicated swapchain.

## Requirements

- Windows x64 (only platform supported for now).
- A GPU with Vulkan ray tracing support (`VK_KHR_ray_tracing_pipeline` and
  `VK_KHR_acceleration_structure`).
- Minecraft 26.3 with one of:
  - Fabric Loader 0.19.5+ and Fabric API 0.160.5+26.3,
  - NeoForge 26.3.0.10-beta+,
  - Forge 26.3-66.0.3+.
- Frame generation and NVIDIA Reflex are Fabric only for now (see ROADMAP).
- Java 25.

## Building

The mod bundles a native library (`core.dll`) built from `native/`.

```sh
# 1. native renderer (Visual Studio 2026 toolchain, x64)
cmake -S native -B build/native -G "Visual Studio 18 2026" -A x64 -DMCVR_ENABLE_NRD=ON -DUSE_AMD=ON
cmake --build build/native --config Release -j 16

# 2. the mod, one jar per loader in fabric/, neoforge/ and forge/ build/libs
./gradlew.bat build
```

The CMake install step copies the shaders and modules into `common/src/main/resources/radiante-native/`; the
built `core.dll` goes into the same folder. `./gradlew.bat :fabric:runClient`, `:neoforge:runClient` or
`:forge:runClient` launches a development client; all three share the `run/` folder.

### Project layout

- `common/` - everything that is plain Minecraft: the renderer, mixins, settings, assets, native files. It is
  compiled against vanilla Minecraft only, so loader-specific code cannot creep in.
- `fabric/`, `neoforge/`, `forge/` - each compiles the common sources together with its own small glue: the
  entrypoint (key bindings, client tick, settings screen) and a `RadiantePlatform` implementation (game
  directory, Fabric's mesh submissions, Streamline support), registered under `META-INF/services`.
- Versions for all of them live in the root `gradle.properties`.

Useful run flags (Fabric):

- `-PquickPlay="<world name>"` — boot straight into a world.
- `-PvulkanValidation` — enable Vulkan validation layers and render debug labels.

## How it works

- `VulkanInstance` / `VulkanDevice` creation is redirected into the native `createMerged` entry points, which
  add the ray tracing, descriptor indexing and upscaler extensions to whatever Minecraft asked for and merge
  the feature chains.
- Access to the graphics queue is serialised between Minecraft and the renderer through hooks installed over
  volk's function pointers.
- Each frame the renderer records its upload / world / composite command buffers and hands them to
  Minecraft's `VulkanCommandEncoder.execute`, then waits on Minecraft's timeline semaphore.
- Terrain is compiled into the renderer's own PBR vertex format from `ModelBlockRenderer` / `FluidRenderer`,
  mirroring Minecraft's section storage.
- Atlases are stitched on the GPU in 26.3, so the sampled copy is rebuilt from the sprite images, honouring
  the stitcher's per-sprite padding.

## Licence

GPL-3.0, inherited from the upstream projects. Radiance and MCVR are by LJIONG and Interstellarss; this fork
keeps their licence and credits.
