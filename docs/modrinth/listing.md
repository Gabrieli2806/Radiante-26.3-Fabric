# Modrinth listing fields

## Project

| Field | Value |
|---|---|
| Name | Radiante |
| Slug | `radiante` |
| Summary | Real-time path tracing for Minecraft 26.3 with DLSS, frame generation, FSR and XeSS. |
| Project type | Mod |
| Categories | Optimization, Utility |
| Client side | Required |
| Server side | Unsupported |
| License | GPL-3.0-only |
| Source code | *(link to your repository)* |
| Issues | *(link to your repository issues)* |

Summary is 88 characters (Modrinth limit: 96).

## Version 0.1.0

| Field | Value |
|---|---|
| Version name | Radiante 0.1.0 |
| Version number | `0.1.0+26.3` |
| Release channel | **Alpha** |
| Loaders | Fabric |
| Game versions | 26.3 |
| Dependencies | Fabric API — required |
| File | `build/libs/radiante-0.1.0+26.3.jar` (115 MB) |

### Changelog

First public release.

- Path-traced lighting, shadows and global illumination on Minecraft 26.3's native Vulkan backend
- DLSS Ray Reconstruction, DLSS Frame Generation, FSR 3 and XeSS
- Emissive blocks, burning mobs, glowing eyes and lightning light the world
- LabPBR resource pack support (`_n` / `_s` maps)
- Two shader packs, optional volumetric clouds, settings screen on F6

Not ported yet: weather, vanilla clouds, block outline, breaking animation, name tags, enchantment glint, sign text. Windows only.
