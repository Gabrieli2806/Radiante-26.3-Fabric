[← Back to index](README.md)

# Frame Generation and NVIDIA Reflex

Two NVIDIA features, both in the [Quality & Upscaling](settings/quality-and-upscaling.md) category,
and both sharing the same limitation for now: **they only work on the Fabric build**. On NeoForge
and Forge, loading Streamline (the NVIDIA library that enables them) makes native device creation
crash, so both options are hidden there — see
[ROADMAP.md](../../ROADMAP.md#frame-generation-and-reflex-on-forge--neoforge--investigate) for the
state of that investigation.

## Frame Generation

| | |
|---|---|
| Requires | Pipeline = RT-DLSS, NVIDIA GPU, Fabric |
| Values | Off · 2x · 3x … up to whatever maximum your GPU reports |
| Default | Off |
| Applies | The first time you turn it on, after restarting the game; changes after that, instantly |

DLSS generates additional frames between the ones the engine actually renders — at 2x, for every
real frame you see one extra generated one; at higher multipliers, more generated frames per real
one. The result is smoother motion on a high refresh-rate monitor, at the cost of a little extra
input latency (generated frames do not react any faster to your mouse or keyboard, they only fill in
the motion between the ones that do).

**Why the first time-on needs a restart:** Streamline has to load before Minecraft creates its
Vulkan device, so turning on Frame Generation for the first time in a session cannot take effect
until the next game launch. Once Streamline is already loaded, raising or lowering the multiplier
applies instantly.

![Generated frame counter](../images/frame-generation-contador.png)
<!-- TODO: the F3 overlay showing real vs. generated FPS -->

### Best used with Reflex on

Generating frames adds some input latency; Reflex cuts it down. Using both together is what NVIDIA
recommends for this combination, and is also the intended use in Radiante.

## NVIDIA Reflex

| | |
|---|---|
| Requires | NVIDIA GPU, Fabric |
| Values | On/Off |
| Default | Off |
| Applies | The first time you turn it on, after restarting the game; after that, instantly |

Low-latency mode: it holds the CPU back so each frame starts as late as the GPU allows, instead of
getting ahead and queueing up frames. The result is that your click or mouse movement reaches the
screen faster, most noticeable with Frame Generation on or with FPS already high enough that the CPU
has spare headroom.

Unlike Frame Generation, Reflex does not depend on the chosen Pipeline — only on having an NVIDIA GPU
and being on Fabric. Just like Frame Generation, turning it on for the first time in a session needs
a restart, because it also depends on Streamline loading before Minecraft creates its Vulkan device.
