[← Back to index](README.md)

# Installation

Check the [requirements](requirements.md) first: GPU, system and loader version. Radiante is
client-only: the server does not need it, and joining a server without it works the same.

There is one jar per loader (`radiante-fabric-…`, `radiante-neoforge-…`, `radiante-forge-…`), and
each works on Windows and Linux. Get it from [Modrinth](https://modrinth.com/project/radiante),
[CurseForge](https://www.curseforge.com/minecraft/mc-mods/radiante) or
[GitHub](https://github.com/Gabrieli2806/Radiante-26.3-Fabric/releases).

## Fabric

1. Install [Fabric Loader](https://fabricmc.net/use/) `0.19.5` or newer for Minecraft 26.3.
2. Put **Fabric API** `0.160.5+26.3` or newer in `mods`.
3. Put the Radiante Fabric jar in `mods`.
4. Start the Fabric profile.

## NeoForge

1. Install [NeoForge](https://neoforged.net/) `26.3.0.10-beta` or newer.
2. Put the Radiante NeoForge jar in `mods`.
3. Start the NeoForge profile.

## Forge

1. Install [Forge](https://files.minecraftforge.net/) `26.3-66.0.3` or newer.
2. Put the Radiante Forge jar in `mods`.
3. Start the Forge profile.

## Which loader

Whichever your modpack uses. All three run the same code, frame generation and Reflex included.
Fabric is the most tested; NeoForge and Forge share the code but have been tested less.

## On Linux

Same jar, same steps. If your launcher is a Flatpak, read [Linux](linux.md) first: the sandbox has
to see your GPU's driver.

## First start

### The renderer is unpacked

The first time, Radiante unpacks its native libraries into the game's `radiante/` folder and
checks each against its SHA-256. It takes a moment; later starts only repeat it when the version
changed.

### DLSS or XeSS is offered

FSR is built in and is the default upscaler. On the title screen, an NVIDIA GPU gets a window
offering DLSS (~115 MB) and an Intel GPU on Windows, XeSS (~73 MB). You can download it, leave it
for later, or never see it again. Details in [Upscalers](upscalers.md).

### The game has to be on Vulkan

Minecraft 26.3 lets you pick the graphics API. Radiante needs Vulkan. If the game starts on OpenGL,
a screen explains it and offers to switch and restart. See
[Common problems](problems.md#the-game-is-on-opengl).

### If your GPU has no ray tracing

You will see "Radiante: ray tracing unavailable" and the game carries on with Minecraft's renderer.
This also happens with software Vulkan (`llvmpipe`). See
[Common problems](problems.md#my-gpu-does-not-support-ray-tracing).

<!-- Screenshot pending: the full "Radiante: ray tracing unavailable" screen -->

### If all goes well

Radiante turns on when you enter a world. Default pipeline: RT-DLSS if DLSS is already installed,
otherwise RT-NRD-FSR.

### Keys

| Key | Action |
|---|---|
| `F6` | Opens Radiante's settings. |
| `F7` | Turns ray tracing on or off instantly. |

Both can be rebound in Minecraft's controls, under Miscellaneous.

## After installing

- [Settings](settings.md): the settings screen.
- [Bedrock RTX](bedrock-rtx.md): resource packs with more detail.
- [Common problems](problems.md) if something does not start or looks wrong.
