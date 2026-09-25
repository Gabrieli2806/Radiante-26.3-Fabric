# Radiante

Path-traced rendering for Minecraft 26.3 (Fabric, NeoForge and Forge), built on Minecraft's own Vulkan backend.

[GitHub](https://github.com/Gabrieli2806/Radiante-26.3-Fabric) · [Modrinth](https://modrinth.com/project/radiante) · [CurseForge](https://www.curseforge.com/minecraft/mc-mods/radiante) · [Discord](https://discord.gg/DhBbAzugZ9)

Languages: **EN** · [ES](README.es.md)

Radiante is a fork of [Radiance](https://github.com/Minecraft-Radiance/Radiance) and its native renderer
[MCVR](https://github.com/Minecraft-Radiance/MCVR), rewritten for the render pearl (`com.mojang.renderpearl`)
Vulkan backend introduced in Minecraft 26.3. Instead of creating its own Vulkan device next to the game, it
shares the device Minecraft already created, so the ray tracer and the vanilla GUI draw into the same frame.

## Features

- Hardware ray tracing (`VK_KHR_ray_tracing_pipeline`) for terrain, with path-traced direct lighting,
  shadows and global illumination.
- Physically based sky, sun and atmospheric scattering.
- One built-in shader pack, `vanilla-pt`, with direct sampling of block lights (torches, lava, lamps),
  volumetric fog and clouds, rain/snow with proper motion vectors, and pixelated lighting for entities and
  blocks.
- LabPBR support (`_s`/`_n` maps) for blocks, atlases and entities/held items, with per-entity emission
  (glow item frames, end crystals, self-lit mobs). A custom resource pack with PBR maps (or a Bedrock
  `.mcpack`) is recommended to get the most out of these features.
- Upscaling through DLSS, FSR 3 and XeSS, plus NRD denoising.
- Motion blur and depth of field, both toggleable.
- Bedrock `.mcpack` resource pack support (including fog and water), detected directly in the pack list.
- Runs on the device Minecraft creates: no second Vulkan instance, no duplicated swapchain.

## Requirements

- Windows x64 (only platform supported for now).
- A GPU with Vulkan ray tracing support (`VK_KHR_ray_tracing_pipeline` and
  `VK_KHR_acceleration_structure`).
- Minecraft 26.3 with one of:
  - Fabric Loader 0.19.5+ and Fabric API 0.160.5+26.3,
  - NeoForge 26.3.0.10-beta+,
  - Forge 26.3-66.0.3+.
- Frame generation and NVIDIA Reflex are Fabric only for now (see [ROADMAP.md](ROADMAP.md)).
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
- `native/` - the C++ Vulkan renderer (ray tracing pipelines, shaders under `native/src/shader/`, upscaler
  and denoiser integration) built with CMake, and installed into `common/`'s resources.
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

## Contributing

Bug reports, shader tweaks and pull requests are welcome.

### Getting set up

1. Fork the repo and clone your fork.
2. Follow [Building](#building) above to get a native build and a dev client running.
3. VS Code users: `.vscode/` ships tasks for each loader's client (build and debug), plus attach configs on
   port 5005. `File > Open Workspace` on the repo root picks these up automatically.

### Making changes

- Branch off `main`; give the branch a short, descriptive name (`fix/rain-motion-vectors`,
  `feat/end-crystal-tint`).
- Keep `common/` loader-agnostic. If a change needs loader-specific behaviour, add it behind
  `RadiantePlatform` and implement it in `fabric/`, `neoforge/` and `forge/`, not with `instanceof` checks
  against a loader's classes in `common/`.
- Shader changes live under `native/src/shader/`; native renderer/C++ changes under `native/src/core/` and
  `native/src/common/`. Match the surrounding code's comment density and naming — comments here explain *why*
  a value or check exists, not what the line does.
- Mod compatibility fixes should be general, not tied to one mod. If mod X breaks because Radiante skips or
  replaces something vanilla does (e.g. `LevelRenderer.render`, whose head hooks still run through
  `LevelRendererSkipMixin`), restore that vanilla behaviour so every mod relying on it benefits, instead of
  special-casing X by name. Only fall back to a mod-specific patch when no general fix exists, and keep it
  optional (reflection, no hard dependency).
- Test in a `Radiante*`-prefixed world under `run/saves/` (a fresh superflat is usually enough) — never point
  a dev/test run at a world you actually play in. `-PquickPlay="<world>"` boots straight into one.
- Before opening a PR: rebuild natives (`cmake --build ... --target INSTALL`), run `./gradlew.bat build` for
  all three loaders, and sanity-check the change in game (screenshot it if it's visual).
- No AI attribution (co-author lines, "Generated with ..." trailers, etc.) in commit messages or PR
  descriptions — write them as your own.

### Pull requests

- One logical change per PR; keep unrelated formatting/reflow out of the diff.
- Describe what changed and why, and how you tested it (screenshots for visual changes are especially
  helpful).
- Reference the relevant [ROADMAP.md](ROADMAP.md) item if the PR closes or advances one.
- Large or architectural changes (new render passes, new upscaler backends, loader-parity work) are easier to
  land if discussed in an issue or on [Discord](https://discord.gg/DhBbAzugZ9) first.

## Licence

GPL-3.0, inherited from the upstream projects. Radiance and MCVR are by LJIONG and Interstellarss; this fork
keeps their licence and credits.
