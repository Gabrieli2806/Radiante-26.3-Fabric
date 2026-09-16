# Changelog

All notable changes to this project are documented in this file.

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
