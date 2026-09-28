# Radiante documentation

![Radiante](../images/logo-radiante.png)
<!-- TODO: Radiante icon/logo, or drop this line if there isn't one yet -->

Languages: [ES](../es/README.md) · **EN**

Radiante is a hardware ray tracing mod for Minecraft 26.3 (Fabric, NeoForge and Forge), built on
Minecraft's own Vulkan backend instead of creating a second Vulkan device next to it. It is a fork of
[Radiance](https://github.com/Minecraft-Radiance/Radiance) and its native renderer
[MCVR](https://github.com/Minecraft-Radiance/MCVR), by LJIONG and Interstellarss.

Documentation version: **0.2.0** (alpha).

## Getting started

1. **[Requirements](requirements.md)** — which GPU, Minecraft version and Java you need.
2. **[Installation](installation.md)** — per-loader steps (Fabric, NeoForge, Forge) and what to
   expect on first launch.
3. **[Settings](settings.md)** — how the settings screen is organised, the Reset to Defaults button,
   and a link to every category.

## Features in depth

- **[Picture styles and quality levels](styles-and-quality.md)** — the Low/Medium/High/Ultra
  presets, and the Natural/Bedrock/Vivid styles.
- **[Bedrock RTX resource packs](bedrock-rtx.md)** — using a Bedrock `.mcpack`, and reading files
  from a Bedrock install on the same PC.
- **[Distant Horizons](distant-horizons.md)** — experimental far-terrain support.
- **[HDR](hdr.md)** — output on HDR displays, peak brightness and paper white.
- **[Frame Generation and NVIDIA Reflex](frame-generation-reflex.md)** — DLSS Frame Generation and
  the low-latency mode.
- **[Performance](performance.md)** — what costs the most FPS and how to tune it for your GPU.
- **[Common problems](problems.md)** — "unavailable" screens, graphics API switches, and what to do
  when something does not work.

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

- Hardware ray tracing (`VK_KHR_ray_tracing_pipeline`) for terrain: path-traced direct lighting,
  soft shadows and global illumination.
- Physically based sky, sun and moon, at their real angular size and colour.
- One shader pack, `vanilla-pt`: direct sampling of block lights, volumetric fog and clouds,
  rain/snow with proper motion vectors, and optional pixelated lighting.
- LabPBR support (`_n`/`_s`) for blocks, entities and held items, with per-entity emission.
- Upscaling through DLSS, FSR 3 and XeSS, with NRD (RELAX) denoising.
- Motion blur and depth of field, both optional.
- Experimental Distant Horizons support and Bedrock `.mcpack` resource pack support.
- Runs on the Vulkan device Minecraft already creates: no second instance, no duplicated swapchain.

## Known limitations (alpha)

This is alpha-stage software. Be upfront with yourself about what does not work yet:

- NRD-based presets (everything that is not DLSS Ray Reconstruction) have somewhat more denoiser
  noise than DLSS-RR. If you have an NVIDIA GPU, DLSS is the stable path right now.
- Light from a held item does not spread far beyond a small radius around the hand.
- Vanilla (non-volumetric) clouds, weather, the block-breaking animation and the enchantment glint
  are not ported to this renderer yet.
- Frame Generation and NVIDIA Reflex only work on Fabric for now — see
  [frame-generation-reflex.md](frame-generation-reflex.md).
- Distant Horizons may show the odd seam or flicker in far terrain while it is still being tuned —
  see [distant-horizons.md](distant-horizons.md).

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
