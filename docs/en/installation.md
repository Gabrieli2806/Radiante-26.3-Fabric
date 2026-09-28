[← Back to index](README.md)

# Installation

Check [requirements](requirements.md) first — in particular the GPU and the loader's minimum
version. Radiante is client-only: it does not need installing on the server, and joining a server
without it works fine, just without ray tracing.

## Fabric

1. Install [Fabric Loader](https://fabricmc.net/use/) `0.19.5` or newer for Minecraft 26.3.
2. Download **Fabric API** `0.160.5+26.3` or newer and drop it into the `mods` folder.
3. Download Radiante and drop it into `mods` too.
4. Launch the game with the Fabric profile.

## NeoForge

1. Install [NeoForge](https://neoforged.net/) `26.3.0.10-beta` or newer.
2. Download Radiante and drop it into `mods`.
3. Launch the game with the NeoForge profile.

NeoForge does not need a Fabric API equivalent; NeoForge already ships what Radiante needs.

## Forge

1. Install [Forge](https://files.minecraftforge.net/) `26.3-66.0.3` or newer.
2. Download Radiante and drop it into `mods`.
3. Launch the game with the Forge profile.

## Which one to pick

All three run the same mod and look identical. The only functional difference today: **Frame
Generation and NVIDIA Reflex are only available on Fabric** (see
[frame-generation-reflex.md](frame-generation-reflex.md)). If those two matter to you and your
modpack does not force NeoForge or Forge, Fabric is the more complete option for now.

## First launch

### The game has to be running on Vulkan

Minecraft 26.3 lets you pick the graphics API (OpenGL or Vulkan). Radiante needs Vulkan — ray
tracing does not exist on OpenGL. If the game starts on OpenGL, you will see a screen explaining
that, with the option to switch to Vulkan and restart. See
[problems.md](problems.md#the-game-is-running-on-opengl) for more detail.

### If your GPU does not support ray tracing

If the game starts on Vulkan but your GPU or driver does not report
`VK_KHR_ray_tracing_pipeline`, you will see the "Radiante: ray tracing unavailable" screen with two
options: continue without ray tracing (Minecraft's own renderer, as if Radiante were not there) or
switch to OpenGL — where it is not available either, so in practice it is the same option. See
[problems.md](problems.md#my-gpu-does-not-support-ray-tracing).

![Ray tracing unavailable screen](../images/pantalla-rt-no-disponible.png)
<!-- TODO: the full "Radiante: ray tracing unavailable" screen -->

### If everything goes well

With a compatible GPU and the game on Vulkan, Radiante turns on by itself as soon as you enter a
world. The first time it uses its defaults: the **RT-DLSS** preset if your GPU is NVIDIA and
supports it (otherwise, the best preset available among RT-NRD-FSR, RT-NRD-XeSS or RT-NRD, in that
order), with picture quality not tuned to any particular level yet.

### Keybinds

| Key | Action |
|---|---|
| `F6` | Opens Radiante's settings. |
| `F7` | Toggles ray tracing on/off without opening the menu — falls back to Minecraft's renderer instantly. |

Neither has an in-game rebind control on the Radiante screen yet; use Minecraft's standard Controls
menu ("Open Radiante Settings" / "Toggle Ray Tracing", under Misc) if you want a different key.

## After installing

- The settings screen is covered in [settings.md](settings.md).
- If you want more visual detail, see [bedrock-rtx.md](bedrock-rtx.md) for a resource pack, or check
  which LabPBR maps Radiante already ships for vanilla textures.
- If something fails to launch or looks wrong, [problems.md](problems.md) covers the most common
  cases.
