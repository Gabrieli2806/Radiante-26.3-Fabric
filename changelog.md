# Changelog

All notable changes to this project are documented in this file.

## [0.2.0] - 2026-09-26

### Added

- NeoForge and Forge builds alongside Fabric, from one shared codebase.
- Experimental Distant Horizons support: its far terrain is path traced with the rest of the world, and DH keeps
  generating with ray tracing on.
- Held light sources light the world from the hand holding them, with their own brightness slider.
- LabPBR maps for entities and held items; Bedrock `.mcpack` resource packs (fog and water included).
- Volumetric fog improvements, rain/snow motion vectors, motion blur and depth of field toggles, pixelated
  lighting option.
- Light from end crystals (purple), end portal frame eyes, glow item frames.

### Fixed

- Noise on moving mobs and players, and on the held item and map, under DLSS.
- Grass block side overlays flickering at edges and corners.
- Compatibility with mods that hook the vanilla world renderer (Not Enough Animations).
- Vanilla clouds follow the player's settings when Distant Horizons changes them.
- Chunk loading stutter, crashes on quit.

## [0.1.0] - 2026-09-16

### Added

- Initial release: Radiance/MCVR ported to Minecraft 26.3 on Fabric, running on Minecraft's own
  `com.mojang.renderpearl` Vulkan backend.
- Shared Vulkan instance and device: ray tracing, descriptor indexing and upscaler extensions are merged into
  the ones Minecraft requests, with the feature chains combined.
- Serialised access to the graphics queue between Minecraft and the renderer.
- Ray-traced terrain: sections compiled into the renderer's PBR vertex format and kept in sync with
  Minecraft's section update tracker.
- Path-traced lighting, shadows and physically based sky driven by the vanilla sky and fog state.
- Texture mirroring for the GPU-stitched 26.3 atlases, including per-sprite stitcher padding.
- DLSS, FSR 3 and XeSS upscaling plus NRD denoising, with the `vanilla-pt` and `advanced` shader packs.
