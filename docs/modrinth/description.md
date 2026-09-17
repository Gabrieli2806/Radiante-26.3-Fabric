# Radiante

**Real-time path tracing for Minecraft 26.3, running on the game's own Vulkan renderer.**

Radiante replaces Minecraft's lighting with hardware ray tracing: sunlight that casts real shadows, torches and lava that light up the room around them, reflections, and global illumination that bounces light off every surface. It runs on the Vulkan backend Minecraft 26.3 ships with, sharing the game's own device instead of starting a second renderer next to it.

> **Early release.** This is version 0.1.0. It is playable, but some things are not ported yet — read *Known issues* before installing.

---

## Features

**Lighting**
- Path-traced direct light, soft shadows and global illumination
- Emissive blocks light their surroundings: torches, lava, glowstone, redstone, nether portals, beacons
- Burning mobs, glowing mob eyes and lightning give off light
- Physically based sky, sun, moon and atmospheric scattering

**Upscaling and performance**
- **DLSS** Ray Reconstruction (Quality / Balanced / Performance / Ultra Performance)
- **DLSS Frame Generation**, including multi frame generation on GPUs that support it
- **FSR 3** and **XeSS** upscaling, plus NRD denoising
- In-game counter of the frames actually reaching your screen (F3), so you can see frame generation working

**Look and feel**
- Two shader packs: **vanilla-pt**, close to vanilla's look, and **advanced**, with parallax and FFT water
- Optional volumetric clouds
- **LabPBR resource pack support**: packs that ship `_n` and `_s` maps get proper normals, roughness, metalness and emission

All options live in the **Radiante settings screen (F6)** and apply when you press Done.

---

## Requirements

- **Windows x64** — the only supported platform for now
- **A GPU with hardware ray tracing** (NVIDIA RTX 20 series or newer, AMD RX 6000 series or newer, Intel Arc)
- **Minecraft 26.3**, **Fabric Loader 0.19.5+**, **[Fabric API](https://modrinth.com/mod/fabric-api)**
- **Java 25**

### Enabling DLSS Ray Reconstruction

NVIDIA's DLSS libraries are not bundled. To use the DLSS pipeline, put `nvngx_dlss.dll` and `nvngx_dlssd.dll` in:

```
.minecraft/radiante/dlss/
```

then restart the game. DLSS will appear in F6. FSR and XeSS work without any extra files.

### Enabling Frame Generation

Frame Generation needs an NVIDIA RTX GPU that supports it. Turn it on in F6, then **restart the game** — it has to load before Minecraft starts its renderer. Changing the multiplier afterwards does not need a restart.

---

## Known issues

This first release does not render everything yet:

- **Not ported yet:** weather, vanilla clouds, block selection outline, block breaking animation, name tags, enchantment glint, red damage flash
- **Sign text** is not readable yet
- **End portals** are visible but do not look like vanilla yet
- **Volumetric clouds** can flicker
- **Held light sources** (a torch in your hand) glow but do not light up their surroundings
- Only Windows is supported

Please report problems on the issue tracker with your GPU, driver version and the `latest.log`.

---

## Compatibility

Radiante replaces how the world is rendered, so it is **not compatible with other rendering mods** such as Sodium or Iris.

---

## Credits

Radiante is a fork of **[Radiance](https://github.com/Minecraft-Radiance/Radiance)** and its native renderer **MCVR**, by **LJIONG** and **Interstellarss**, rebuilt for Minecraft 26.3's Vulkan backend.

Licensed under **GPL-3.0**, inherited from the upstream projects.
