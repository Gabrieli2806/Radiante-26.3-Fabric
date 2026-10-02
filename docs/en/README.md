# Radiante documentation

![Radiante](../images/logo-radiante.png)
<!-- TODO: Radiante icon/logo, or drop this line if there isn't one yet -->

Languages: [ES](../es/README.md) · **EN**

Radiante is a hardware ray tracing mod for Minecraft 26.3 (Fabric, NeoForge and Forge),
on Windows and Linux, built on
Minecraft's own Vulkan backend instead of creating a second Vulkan device next to it. It is a fork of
[Radiance](https://github.com/Minecraft-Radiance/Radiance) and its native renderer
[MCVR](https://github.com/Minecraft-Radiance/MCVR), by LJIONG and Interstellarss.

Documentation version: **0.5.0** (first beta).

## Getting started

1. **[Requirements](requirements.md)** — system, GPU by vendor, loader and Java.
2. **[Installation](installation.md)** — per-loader steps (Fabric, NeoForge, Forge) and what to
   expect on first launch.
3. **[Upscalers](upscalers.md)** — FSR built in; DLSS and XeSS downloaded in game.
4. **[Settings](settings.md)** — how the settings screen is organised, the Reset to Defaults button,
   and a link to every category.

## Features in depth

- **[Picture styles and quality levels](styles-and-quality.md)** — the Low/Medium/High/Ultra
  presets, and the Natural/Bedrock/Vivid styles.
- **[Bedrock RTX resource packs](bedrock-rtx.md)** — using a Bedrock `.mcpack`, and reading files
  from a Bedrock install on the same PC.
- **[Distant Horizons](distant-horizons.md)** — experimental far-terrain support.
- **[HDR](hdr.md)** — output on HDR displays, peak brightness and paper white.
- **[Frame Generation and NVIDIA Reflex](frame-generation-reflex.md)** — DLSS or FSR, and the
  low-latency mode.
- **[Performance](performance.md)** — what costs the most FPS and how to tune it for your GPU.
- **[Linux](linux.md)** — glibc, drivers, Flatpak launchers and what to check if it does not start.
- **[Common problems](problems.md)** — "unavailable" screens, graphics API switches, and what to do
  when something does not work.

## For developers

- **[Building and releasing](building.md)** — Windows, Linux, GitHub Actions and how a release goes out.

## Settings reference by category

Radiante's settings screen (`F6` in game) has six categories. Each one has its own page with every
control, its default value, and what it does:

| In-game category | Document |
|---|---|
| Quality & Upscaling | [settings/quality-and-upscaling.md](settings/quality-and-upscaling.md) |
| Image | [settings/image.md](settings/image.md) |
| Lighting | [settings/lighting.md](settings/lighting.md) |
| Sky, Fog & Water | [settings/sky-fog-water.md](settings/sky-fog-water.md) |
| Performance | [settings/performance.md](settings/performance.md) |
| Other | [settings/other.md](settings/other.md) |

## What Radiante is, in short

- Hardware ray tracing for terrain: direct light, soft shadows and global illumination, with up to
  8 bounces and ReSTIR for block lights.
- Physical sky, volumetric fog (Radiance-style froxel grid or per pixel), volumetric clouds with
  shadows, rain with refraction and wet ground, seamless glass.
- LabPBR (`_n`/`_s`) for blocks, entities and items; Bedrock RTX `.mcpack` resource packs.
- FSR built in; DLSS and XeSS downloadable. DLSS or FSR frame generation and NVIDIA Reflex.
- HDR output, motion blur and depth of field.
- Experimental Distant Horizons support.
- Runs on the Vulkan device Minecraft already creates. The jar is about 15 MB.

## Known limitations (beta)

- NRD pipelines (FSR, XeSS, native) are somewhat noisier than DLSS Ray Reconstruction.
- AMD and Intel work but are less tested than NVIDIA.
- Frame generation and Reflex are tested on Fabric; on NeoForge and Forge they run the same code,
  untested so far. Frame generation is not available with HDR.
- XeSS is Windows only. Installing DLSS does not change the pipeline: pick RT-DLSS.
- Shader Execution Reordering is experimental (water may look white).
- Distant Horizons is still experimental: see [distant-horizons.md](distant-horizons.md).
- The settings' "Advanced..." button does nothing yet.

The full, up-to-date backlog lives in [ROADMAP.md](../../ROADMAP.md), at the repository root.

## Links

- [GitHub](https://github.com/Gabrieli2806/Radiante-26.3-Fabric) — source code and bug reports.
- [Modrinth](https://modrinth.com/project/radiante) · [CurseForge](https://www.curseforge.com/minecraft/mc-mods/radiante)
- [Discord](https://discord.gg/DhBbAzugZ9) — for questions, reports, and discussing large changes
  before a PR.
- [Ko-fi](https://ko-fi.com/gabrieli2806)

## Licence

GPL-3.0, inherited from the upstream projects. Radiance and MCVR are by LJIONG and Interstellarss;
this fork keeps their licence and credits.
