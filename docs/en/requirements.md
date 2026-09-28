[← Back to index](README.md)

# Requirements

## System

| | |
|---|---|
| Operating system | Windows x64. It is the only supported platform for now (see [ROADMAP.md](../../ROADMAP.md), "Investigate: backport to 26.1" section for the status of other versions; Linux/macOS are not planned in the short term). |
| Minecraft | 26.3 |
| Java | 25 |
| GPU | A card with hardware ray tracing support in Vulkan: `VK_KHR_ray_tracing_pipeline` and `VK_KHR_acceleration_structure`. In practice, NVIDIA RTX 20-series or newer, AMD RX 6000 or newer, or Intel Arc. |
| Graphics API | Vulkan. The game has to be using the `com.mojang.renderpearl` Vulkan backend, not OpenGL — see [installation](installation.md#first-launch) and [common problems](problems.md#the-game-is-running-on-opengl). |

If your GPU does not report ray tracing support, the game keeps running with Minecraft's own
renderer; Radiante simply does not turn on. There is no software fallback path.

## One loader, each with its own minimum version

You need Minecraft 26.3 with **one** of these three:

| Loader | Minimum version |
|---|---|
| Fabric | Fabric Loader `0.19.5+` and Fabric API `0.160.5+26.3` |
| NeoForge | `26.3.0.10-beta+` |
| Forge | `26.3-66.0.3+` |

All three builds come from the same code and look the same visually. The main difference between
them today is that **Frame Generation and NVIDIA Reflex only work on Fabric**, due to a Streamline
loading issue on NeoForge and Forge — see [frame-generation-reflex.md](frame-generation-reflex.md)
and [ROADMAP.md](../../ROADMAP.md#frame-generation-and-reflex-on-forge--neoforge--investigate).

## What you do not need to download separately

DLSS, FSR 3, XeSS and NRD ship inside the mod itself — there are no NVIDIA, AMD or Intel DLLs to
download separately or copy by hand. Which options show up as available on the settings screen
depends on your GPU and driver, not on installing anything extra:

- **DLSS** mode (the "RT-DLSS" preset) only appears on NVIDIA GPUs with a driver that supports it.
- **FSR 3** and **XeSS** work on any vendor — you do not need an AMD GPU to use FSR, or an Intel one
  to use XeSS.
- **NVIDIA Reflex** and **DLSS Frame Generation** are, as their name says, NVIDIA-exclusive.

If a preset does not show up in the Pipeline dropdown, your GPU or driver do not meet what that
preset needs — it is not an install step you are missing.

## Recommended, not required

- A **resource pack with LabPBR maps** (`_n`/`_s`) or a Bedrock RTX `.mcpack` converted by Radiante
  itself, to get the most out of relief and materials — see [bedrock-rtx.md](bedrock-rtx.md).
  Without one, the mod still works with the maps it ships for 154 vanilla textures.
- [Distant Horizons](https://modrinth.com/mod/distanthorizons) if you also want far terrain traced —
  experimental support, see [distant-horizons.md](distant-horizons.md).
- An **HDR** display if you want to use HDR output — see [hdr.md](hdr.md).
