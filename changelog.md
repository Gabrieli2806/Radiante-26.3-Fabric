# Changelog

All notable changes to this project are documented in this file.

## [0.5.0] - 2026-10-02

First beta. Covers everything since 0.2.0 (0.3.0 and 0.4.0 were never documented here).

### Added

- **Linux support** (x86-64, glibc 2.35+). One jar per loader carries both the Windows and the Linux renderer and
  picks the right one at start. Linux-specific fixes in the bundled FidelityFX and minizip builds, and a
  `libcore.so` that only exports its JNI entry points.
- **Smaller jars (about 15 MB, was 125 MB).** The native libraries ship xz-packed and are unpacked into the game
  folder on first start, checked against their SHA-256.
- **DLSS and XeSS on demand.** They are no longer in the jar. FSR (upscaling and frame generation) is built in and is
  the default everywhere; on NVIDIA the first menu offers DLSS, on Intel XeSS. They are downloaded from NVIDIA's and
  Intel's own repositories (fixed version, hash checked) with a progress bar. The Pipeline selector lists them as
  `(Install)` / `(Restart required)`, and the settings bar has Install, Delete and Restart buttons. Installing or
  deleting is done outside a world and always ends in a forced restart.
- **NVIDIA Reflex without Streamline**, straight on `VK_NV_low_latency2`: no restart to toggle it, works on Linux, and
  Streamline is gone.
- **Distant Horizons: per-column coverage hiding**, as in Radiance's Vista. Far terrain is no longer remeshed when
  chunks load; it is hidden per ray where the near terrain is built. Far terrain only counts as replaced once the
  renderer actually draws what replaces it.
- **Frame generation** on its own present thread, with native DLSS and FSR providers.
- Froxel volumetric fog (Radiance style) with a fog-style toggle, rain refraction, light from dropped and emissive
  entities (with a reach slider), seamless glass including panes, and a "Cache Deep Bounces" performance toggle.
- New settings: Upscaling Mode for FSR and XeSS (the render resolution, as DLSS Mode is for DLSS, set by the
  Quality level too), Frame Generation Type (Auto, DLSS or FSR, where the GPU runs both), ReSTIR Block Lights, Shader
  Execution Reordering (RTX 40 and newer, experimental), Far Bounce Distance and Far Light Bounces, Cloud Shadows,
  Wet Ground in Rain, Glowing Item Light Distance, Seamless Glass and Rain Refraction.
- Settings screen: a search box, a performance impact label on the options that cost the most, an "Applying
  settings" screen while the renderer rebuilds, and a notice listing what needs a restart when leaving.
- Debug logging is on by default, and the renderer's own messages (errors always) now reach `latest.log` as
  `[native] ...`, so one log is enough to report a problem.
- GitHub Actions builds both renderers and the three loader jars, and publishes a release when `mod_version`
  changes (and to Modrinth / CurseForge when configured).

### Changed

- Translucent particles (campfire smoke, dust) are kept by their alpha like solid surfaces instead of looking like
  glass.
- Tinted glass is dark and see-through but keeps its texture; glass reflections are capped.
- Reset to Defaults now falls back to FSR instead of no upscaler.
- Light Bounces goes up to 8 (was 4). The Low quality level uses 2 bounces instead of 1, which left rooms flat.
- Frame generation is no longer tied to the DLSS pipeline or to Fabric: FSR frame generation works with any
  upscaler. It leaves V-Sync to the player and is hidden while HDR output is on.
- Light grid and the Streamline frame generation were tried and removed (flicker, no gain).

### Fixed

- Black world on Linux with FSR: FidelityFX binding names were truncated on Linux, so FSR could not be created; and
  minizip lacked deflate there, so the shader pack could not be extracted.
- Black screen with no explanation on machines without hardware ray tracing (software Vulkan): the renderer now stays
  off and says why.
- Far-terrain blinking and holes when moving fast with Distant Horizons.
- Fog glow on block edges and a bedrock-atmosphere glow on glass.
- Crash on quit from static destructors running after the Vulkan driver was gone.

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
